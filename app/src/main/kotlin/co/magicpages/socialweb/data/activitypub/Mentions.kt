package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.di.ApplicationScope
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the writer has to do with someone, as far as the app knows. */
internal data class Acquaintance(
    val author: Author,
    val following: Boolean = false,
    val follower: Boolean = false,
    val inThread: Boolean = false,
    /** Replied to or mentioned the writer lately. */
    val talkedRecently: Boolean = false,
    /** Liked, reposted or followed the writer lately. */
    val noticedRecently: Boolean = false,
) {
    val closeness: Int
        get() = (if (inThread) 60 else 0) +
            (if (following && follower) 50 else if (following) 35 else if (follower) 15 else 0) +
            (if (talkedRecently) 40 else if (noticedRecently) 15 else 0)
}

/** `@user@domain`, lower-cased and without the leading `@`, so handles compare whatever their form. */
internal fun handleKey(handle: String): String = handle.trim().removePrefix("@").lowercase()

/** Lower-case and without accents, so "corlu" finds "Çorlu". */
private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Marks, "").lowercase()

private val Marks = Regex("\\p{Mn}+")
private val WordBreak = Regex("[^\\p{L}\\p{N}]+")

/**
 * How well [query] (what follows the `@`, lower-case) fits [author]; 0 is no fit. A full handle
 * wins outright; then the username, the name and its words, the domain, and text inside them.
 */
internal fun matchScore(query: String, author: Author): Int {
    if (query.isEmpty()) return 1
    val handle = handleKey(author.handle)
    val user = handle.substringBefore('@')
    val domain = handle.substringAfter('@', "")
    val q = fold(query)
    val name = fold(author.name)
    return when {
        handle == query -> 1000
        '@' in query -> if (handle.startsWith(query)) 120 else 0
        user.startsWith(q) -> 100
        name.startsWith(q) -> 90
        name.split(WordBreak).any { it.startsWith(q) } -> 80
        domain.startsWith(q) -> 50
        q.length >= 2 && user.contains(q) -> 40
        q.length >= 2 && name.contains(q) -> 30
        else -> 0
    }
}

/**
 * The people to offer for [query]: those the writer knows first ([known]: the thread, mutuals,
 * follows, followers, recent replies), each by how well they fit plus how close they are, then
 * accounts found by searching ([found], in the service's order). Never the writer ([ownHandle]).
 */
internal fun rankMentions(
    query: String,
    known: Collection<Acquaintance>,
    found: List<Author>,
    ownHandle: String?,
    limit: Int = 6,
): List<Author> {
    val own = ownHandle?.let(::handleKey)
    val byHandle = LinkedHashMap<String, Acquaintance>()
    for (person in known) byHandle[handleKey(person.author.handle)] = person
    found.forEach { author ->
        val key = handleKey(author.handle)
        val person = byHandle[key]
        // The search knows the current follow state; a known person keeps everything else.
        byHandle[key] = person?.copy(following = person.following || author.followedByMe)
            ?: Acquaintance(author, following = author.followedByMe)
    }
    val order = found.withIndex().associate { (index, author) -> handleKey(author.handle) to index }
    return byHandle.entries
        .filter { (key, _) -> key != own }
        .mapNotNull { (key, person) ->
            val fit = matchScore(query, person.author)
            if (fit == 0) null else Triple(key, person, fit + person.closeness)
        }
        .sortedWith(
            compareByDescending<Triple<String, Acquaintance, Int>> { it.second.closeness > 0 }
                .thenByDescending { it.third }
                .thenBy { order[it.first] ?: Int.MAX_VALUE }
                .thenBy { fold(it.second.author.name.ifBlank { it.first }) },
        )
        .take(limit)
        .map { (_, person, _) -> person.author }
}

/**
 * Finds people to mention for the active account. Its follows, followers and latest notifications
 * load once per account (again after [StaleAfterMillis]) and fill in page by page, so the first
 * suggestions show at once; the service's account search joins in after a short pause in typing.
 */
@Singleton
class MentionDirectory @Inject constructor(
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private class Circle(
        val accountId: String,
        val following: List<Author> = emptyList(),
        val followers: List<Author> = emptyList(),
        val recent: List<Notification> = emptyList(),
    )

    private val circle = MutableStateFlow<Circle?>(null)
    private val warming = Mutex()
    private var loading: Job? = null
    private var loadedAt = 0L

    init {
        // Another account knows other people.
        scope.launch {
            accounts.activeId.collect {
                loading?.cancel()
                circle.value = null
            }
        }
    }

    /** Starts loading the active account's people, unless they are loaded and fresh. */
    fun warmUp() {
        scope.launch {
            warming.withLock {
                val account = accounts.account.first() ?: return@launch
                val current = circle.value
                val fresh = System.currentTimeMillis() - loadedAt < StaleAfterMillis
                if (current?.accountId == account.id && (fresh || loading?.isActive == true)) return@launch
                loading?.cancel()
                loadedAt = System.currentTimeMillis()
                circle.value = Circle(account.id)
                loading = scope.launch { load(account) }
            }
        }
    }

    /**
     * Ranked people for [query] (what follows the `@`), with [thread] (the people of the post being
     * answered) first among equals. Emits again as follows load and when the search answers.
     */
    fun suggestions(query: String, thread: List<Author>): Flow<List<Author>> {
        val q = query.lowercase()
        val searched = flow {
            emit(emptyList())
            val term = searchTerm(q) ?: return@flow
            delay(SearchPauseMillis)
            val account = accounts.account.first() ?: return@flow
            emit(
                try {
                    client.searchAccounts(account.site, account.credential, term)
                } catch (_: SocialWebException) {
                    emptyList()
                } catch (_: GhostException) {
                    emptyList()
                },
            )
        }
        return combine(circle, searched) { circle, found ->
            val own = accounts.account.first()?.profile?.handle
            rankMentions(q, acquaintances(circle, thread), found, own)
        }.onStart { warmUp() }
    }

    private suspend fun load(account: StoredAccount) = coroutineScope {
        launch { loadList(account, FollowList.Following, MaxFollowing) }
        launch { loadList(account, FollowList.Followers, MaxFollowers) }
        try {
            val recent = client.notifications(account.site, account.credential).notifications
            update(account.id) { Circle(it.accountId, it.following, it.followers, recent) }
        } catch (_: SocialWebException) {
        } catch (_: GhostException) {
        }
    }

    private suspend fun loadList(account: StoredAccount, list: FollowList, max: Int) {
        var next: String? = null
        var loaded = 0
        do {
            val page = try {
                client.follows(account.site, account.credential, "me", list, next)
            } catch (_: SocialWebException) {
                return
            } catch (_: GhostException) {
                return
            }
            loaded += page.accounts.size
            update(account.id) {
                when (list) {
                    FollowList.Following -> Circle(it.accountId, it.following + page.accounts, it.followers, it.recent)
                    FollowList.Followers -> Circle(it.accountId, it.following, it.followers + page.accounts, it.recent)
                }
            }
            next = page.next
        } while (next != null && loaded < max && circle.value?.accountId == account.id)
    }

    /** Changes the circle only while it still belongs to [accountId]. */
    private fun update(accountId: String, change: (Circle) -> Circle) =
        circle.update { if (it?.accountId == accountId) change(it) else it }

    private fun acquaintances(circle: Circle?, thread: List<Author>): Collection<Acquaintance> {
        val people = LinkedHashMap<String, Acquaintance>()
        fun add(author: Author, change: (Acquaintance) -> Acquaintance) {
            val key = handleKey(author.handle)
            people[key] = change(people[key] ?: Acquaintance(author))
        }
        circle?.following?.forEach { author -> add(author) { it.copy(following = true) } }
        circle?.followers?.forEach { author -> add(author) { it.copy(follower = true, following = it.following || author.followedByMe) } }
        circle?.recent?.forEach { notification ->
            val talked = notification.type == Notification.Type.Reply || notification.type == Notification.Type.Mention
            add(notification.actor) {
                it.copy(
                    following = it.following || notification.actor.followedByMe,
                    talkedRecently = it.talkedRecently || talked,
                    noticedRecently = it.noticedRecently || !talked,
                )
            }
        }
        thread.forEach { author -> add(author) { it.copy(inThread = true) } }
        return people.values
    }

    private companion object {
        const val StaleAfterMillis = 10 * 60 * 1000L
        const val SearchPauseMillis = 300L
        // Pages of 20: enough for the people a writer usually means, without a flood of requests.
        const val MaxFollowing = 500
        const val MaxFollowers = 300

        /** What to ask the search for, or null when it can't help: a full handle goes as `@user@domain`. */
        fun searchTerm(query: String): String? = when {
            FullHandle.matches(query) -> "@$query"
            '@' in query -> query.substringBefore('@').takeIf { it.length >= 2 }
            query.length >= 2 -> query
            else -> null
        }

        val FullHandle = Regex("[\\w.-]+@[\\w-]+\\.[\\w.-]+[a-z]")
    }
}
