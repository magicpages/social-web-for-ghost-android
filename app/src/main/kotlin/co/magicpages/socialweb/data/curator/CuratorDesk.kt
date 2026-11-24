package co.magicpages.socialweb.data.curator

import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.Moderation
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.activitypub.Topic
import co.magicpages.socialweb.data.activitypub.handleKey
import co.magicpages.socialweb.data.settings.AppSettings
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The slug of Ghost's default topic: it lists who is popular, not a subject, so nobody writes about it. */
const val TopTopicSlug = "top"

/** How many posts the desk puts in front of the writer in a day. */
const val CuratorDayLimit = 5

/** How many topics a writer can say they write about. */
const val CuratorMaxTopics = 3

/** Reposts in one day after which the desk offers a thought as the better answer. */
const val CuratorRepostsBeforeThought = 3

/** How long a shown post stays out of the picks; older ones have moved on from the topics' feeds anyway. */
private const val SeenKeepMillis = 14L * 24 * 60 * 60 * 1000

/** Pages read per topic while looking for a day's posts; a feed full of what is filtered out must not be read to its end. */
private const val MaxPagesPerTopic = 3

/**
 * The curator desk: a few posts a day from the topics a site's writer picked, to repost or answer, one at a time.
 * It decides what is on the desk and remembers it per site ([CuratorStore]); showing it is up to the screens.
 *
 * The day's posts are picked once per local date and kept, so the desk doesn't reshuffle and survives a restart.
 * Nothing here counts, ranks or nags: it keeps no streak, only what it needs to not show the same post twice.
 */
@Singleton
class CuratorDesk @Inject constructor(
    private val store: CuratorStore,
    private val client: ActivityPubClient,
    private val moderation: Moderation,
    private val settings: AppSettings,
) {
    // Two screens asking at once would pick two different days and keep the later one.
    private val picking = Mutex()

    /** What the desk remembers for [accountId]. */
    fun state(accountId: String): Flow<CuratorState> = store.states.map { it[accountId] ?: CuratorState() }.distinctUntilChanged()

    /**
     * The topics [account]'s writer can choose from: the service's, but the default one. Throws
     * [SocialWebException] with status 403 for anyone but an owner or administrator, who have no desk.
     */
    suspend fun availableTopics(account: StoredAccount): List<Topic> =
        client.topics(account.site, account.credential).filter { it.slug != TopTopicSlug }

    /**
     * Records that the writer of [accountId] writes about [topics] (at most [CuratorMaxTopics]). A changed choice
     * brings the desk back if it was sent away, and has today's posts picked anew, unless the day is already
     * through: five a day is five a day, whatever the topics.
     */
    fun chooseTopics(accountId: String, topics: List<Topic>) {
        val chosen = topics.distinctBy { it.slug }.take(CuratorMaxTopics)
        if (chosen.isEmpty()) return
        store.update(accountId) { state ->
            if (state.topics.map { it.slug } == chosen.map { it.slug }) return@update state.copy(topics = chosen)
            val day = state.day?.let { if (it.finished) it else it.copy(picks = null, handled = 0, fellBack = false) }
            state.copy(topics = chosen, day = day, hiddenOn = null)
        }
    }

    /**
     * Makes sure [account] has its posts for [today], picking them when it hasn't. Does nothing before the writer
     * chose topics, or once the day has been picked, even when there was nothing to pick. Throws what the service's
     * requests throw, and keeps nothing then, so the next call tries again.
     */
    suspend fun prepare(account: StoredAccount, today: LocalDate = LocalDate.now()) {
        picking.withLock {
            val date = today.toString()
            val state = store.states.value[account.id] ?: return
            if (state.topics.isEmpty() || state.day?.takeIf { it.date == date }?.picks != null) return
            val now = System.currentTimeMillis()
            val picked = pick(account, state, now)
            store.update(account.id) { current ->
                // The topics may have been changed while the posts were loading; these would be the old topics' posts.
                if (current.topics.map { it.slug } != state.topics.map { it.slug }) return@update current
                val day = current.day?.takeIf { it.date == date } ?: CuratorDay(date)
                current.copy(
                    day = day.copy(picks = picked.posts, handled = 0, fellBack = picked.fellBack),
                    seen = current.seen.kept(now) + listOfNotNull(picked.posts.firstOrNull()?.let { it.id to now }),
                )
            }
        }
    }

    /**
     * The post on show was dealt with: reposted when [reposted], else answered or skipped. Only counts when it was the
     * [from]th (so a double tap can't skip a post unseen). The next post is then shown, and so remembered.
     */
    fun advance(accountId: String, from: Int, reposted: Boolean) {
        val now = System.currentTimeMillis()
        store.update(accountId) { state ->
            val day = state.day ?: return@update state
            val picks = day.picks ?: return@update state
            if (day.handled != from || from >= picks.size) return@update state
            val handled = from + 1
            state.copy(
                day = day.copy(handled = handled, reposts = day.reposts + if (reposted) 1 else 0),
                seen = state.seen.kept(now) + listOfNotNull(picks.getOrNull(handled)?.let { it.id to now }),
            )
        }
    }

    /** Ends the day: the posts not looked at are left unshown, and come back another day. */
    fun skipAll(accountId: String) {
        store.update(accountId) { state ->
            val day = state.day ?: return@update state
            val picks = day.picks ?: return@update state
            state.copy(day = day.copy(handled = picks.size))
        }
    }

    /** Sends the desk away until tomorrow. */
    fun hide(accountId: String, today: LocalDate = LocalDate.now()) {
        store.update(accountId) { it.copy(hiddenOn = today.toString()) }
    }

    /** Called once the writer has been told the day is through: the next time the desk comes up today, it stays away. */
    fun acknowledgeFinished(accountId: String, today: LocalDate = LocalDate.now()) {
        store.update(accountId) { state ->
            if (state.day?.takeIf { it.date == today.toString() }?.finished == true) state.copy(hiddenOn = today.toString()) else state
        }
    }

    private class Picked(val posts: List<Post>, val fellBack: Boolean)

    private suspend fun pick(account: StoredAccount, state: CuratorState, now: Long): Picked {
        val own = handleKey(account.profile.handle)
        val hidden = moderation.hidden.value
        val filter = settings.feedFilter.value
        val usable: (Post) -> Boolean = { post ->
            post.isArticle && !post.isDeleted && !post.authoredByMe && handleKey(post.author.handle) != own &&
                !post.repostedByMe && post.id !in state.seen && !hidden.hides(post) && !filter.hides(post)
        }
        val chosen = coroutineScope {
            state.topics.map { async { fromTopic(account, it.slug, usable) } }.awaitAll()
        }.flatten()
        val posts = chosen.newestFirst()
        if (posts.isNotEmpty()) return Picked(posts, fellBack = false)
        val top = fromTopic(account, TopTopicSlug, usable).newestFirst()
        return Picked(top, fellBack = top.isNotEmpty())
    }

    /** The usable posts of [slug]'s feed, read until there are enough for a day. A topic the service doesn't have has none. */
    private suspend fun fromTopic(account: StoredAccount, slug: String, usable: (Post) -> Boolean): List<Post> {
        val found = mutableListOf<Post>()
        var next: String? = null
        repeat(MaxPagesPerTopic) {
            val page = try {
                client.discoverFeed(account.site, account.credential, slug, next)
            } catch (e: SocialWebException) {
                if (e.status == 404) return found
                throw e
            }
            // Muted words parse each post's HTML.
            found += withContext(Dispatchers.Default) { page.posts.filter(usable) }
            next = page.next ?: return found
            if (found.size >= CuratorDayLimit) return found
        }
        return found
    }

    /** The newest [CuratorDayLimit] posts, each once, and without their bodies: the desk shows titles, and a post opens by its id. */
    private fun List<Post>.newestFirst(): List<Post> =
        distinctBy { it.id }
            .sortedByDescending { publishedMillis(it) }
            .take(CuratorDayLimit)
            .map { it.copy(content = "", metadata = null) }

    private fun Map<String, Long>.kept(now: Long): Map<String, Long> = filterValues { now - it < SeenKeepMillis }
}

/** When [post] came out, in epoch milliseconds; posts without a readable date count as the oldest. */
fun publishedMillis(post: Post): Long =
    post.publishedAt?.let {
        try {
            Instant.parse(it).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    } ?: Long.MIN_VALUE
