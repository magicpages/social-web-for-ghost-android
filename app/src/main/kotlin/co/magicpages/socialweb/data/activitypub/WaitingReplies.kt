package co.magicpages.socialweb.data.activitypub

import android.content.Context
import androidx.core.content.edit
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.ghost.GhostException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How long a reply counts as waiting; older ones are left to the plain notifications list. */
const val WaitingReplyMaxAgeMillis = 7L * 24 * 60 * 60 * 1000

/** When the service created this notification, in epoch milliseconds; null if it sent no readable time. */
fun Notification.createdAtMillis(): Long? = createdAt?.let {
    try {
        Instant.parse(it).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * Whether [notification] is a reply that could be waiting for [myHandle]'s answer, going only by what the
 * notification itself says: a reply (the service only sends those for posts of this account) by someone
 * else, no older than [WaitingReplyMaxAgeMillis] at [now], that the account hasn't liked.
 */
internal fun isWaitingCandidate(notification: Notification, myHandle: String, now: Long): Boolean {
    val post = notification.post ?: return false
    if (notification.type != Notification.Type.Reply || post.likedByMe) return false
    if (notification.actor.handle.equals(myHandle, ignoreCase = true)) return false
    val created = notification.createdAtMillis() ?: return false
    return now - created <= WaitingReplyMaxAgeMillis
}

/**
 * The replies each account has dealt with without liking them: answered, or dismissed from the waiting
 * list. Kept on this device only, by account id, and each entry for just a little longer than a reply
 * can wait, since older ones can't come back. Nothing here is secret, so plain preferences do.
 */
@Singleton
class HandledReplies @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("handled_replies", Context.MODE_PRIVATE)

    private val _handled = MutableStateFlow(readAll())

    /** Each account's handled replies by account id: the reply's post id, and when it was handled. */
    val handled: StateFlow<Map<String, Map<String, Long>>> = _handled.asStateFlow()

    /** Remembers that [accountId] dealt with the reply [postId]. */
    @Synchronized
    fun markHandled(accountId: String, postId: String, nowMillis: Long = System.currentTimeMillis()) {
        val kept = _handled.value[accountId].orEmpty().filterValues { nowMillis - it < KeepMillis } + (postId to nowMillis)
        prefs.edit { putStringSet(KeyPrefix + accountId, kept.mapTo(mutableSetOf()) { "${it.value}\t${it.key}" }) }
        _handled.value += accountId to kept
    }

    /** Drops what was stored for an account that was signed out of. */
    @Synchronized
    fun forgetAccount(accountId: String) {
        prefs.edit { remove(KeyPrefix + accountId) }
        _handled.value -= accountId
    }

    private fun readAll(): Map<String, Map<String, Long>> =
        prefs.all.keys.filter { it.startsWith(KeyPrefix) }.associate { key ->
            val entries = prefs.getStringSet(key, null).orEmpty().mapNotNull { entry ->
                val (millis, postId) = entry.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                millis.toLongOrNull()?.let { postId to it }
            }
            key.removePrefix(KeyPrefix) to entries.toMap()
        }

    private companion object {
        const val KeyPrefix = "handled:"

        /** A day beyond [WaitingReplyMaxAgeMillis], so clock differences can't bring a reply back. */
        const val KeepMillis = WaitingReplyMaxAgeMillis + 24L * 60 * 60 * 1000
    }
}

/**
 * Replies to the active account's posts that are still waiting for the publisher: from someone else, no older than
 * a week, and neither liked, answered nor dismissed. Nothing here nags: it is only read by the quiet row in the
 * notifications list.
 *
 * The service doesn't say whether the account answered a reply. A like is on the notification's post
 * (`likedByMe`), and an answer made in this app is remembered in [HandledReplies]. For an answer made elsewhere
 * (the Ghost admin, say), a reply nobody has replied to yet (`replyCount` 0) can't have one, so only the rare
 * reply that has replies costs a request for its thread, at most [MaxThreadChecks] per [refresh] and once per
 * reply count. A reply whose thread isn't checked yet isn't counted, so the row never claims more than is certain.
 */
@Singleton
class WaitingReplies @Inject constructor(
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
    private val handled: HandledReplies,
    private val interactions: PostInteractions,
    private val moderation: Moderation,
) {
    private class Loaded(val accountId: String, val replies: List<Notification>)

    private val loaded = MutableStateFlow<Loaded?>(null)
    private val refreshing = Mutex()

    /** "accountId|postId" to the reply count at which the thread had no answer from the account. */
    private val checked = ConcurrentHashMap<String, Int>()

    /** Newest first. Follows the account, likes, replies and dismissals as they happen, without asking the service. */
    val waiting: Flow<List<Notification>> = combine(
        loaded,
        accounts.activeId,
        handled.handled,
        interactions.overrides,
        moderation.hidden,
    ) { current, activeId, handledByAccount, overrides, hidden ->
        if (current == null || current.accountId != activeId) {
            emptyList<Notification>()
        } else {
            val done = handledByAccount[current.accountId]?.keys.orEmpty()
            val now = System.currentTimeMillis()
            current.replies.filter { reply ->
                val id = reply.post?.id ?: return@filter false
                id !in done &&
                    overrides[id]?.likedByMe != true &&
                    id !in hidden.posts &&
                    !hidden.hides(reply.actor) &&
                    now - (reply.createdAtMillis() ?: 0) <= WaitingReplyMaxAgeMillis
            }
        }
    }

    /** Remembers that the active account doesn't want [postId] listed any more. */
    suspend fun dismiss(postId: String) {
        accounts.activeId.first()?.let { handled.markHandled(it, postId) }
    }

    /**
     * Looks through the newest notifications again. One request usually covers the week; the service's pages hold
     * at most [PageSize]. Keeps the last result when the service can't be reached.
     */
    suspend fun refresh() {
        refreshing.withLock { scan() }
    }

    private suspend fun scan() {
        val account = accounts.account.first() ?: return
        val me = account.profile.handle
        val now = System.currentTimeMillis()
        val candidates = ArrayList<Notification>()
        try {
            var next: String? = null
            var pages = 0
            var reachedBack = false
            do {
                val page = client.notifications(account.site, account.credential, next, PageSize)
                candidates += page.notifications.filter { isWaitingCandidate(it, me, now) }
                next = page.next
                pages++
                val oldest = page.notifications.lastOrNull()?.createdAtMillis()
                // Newest first: once a page reaches back past the week, nothing further can be waiting.
                reachedBack = oldest != null && now - oldest > WaitingReplyMaxAgeMillis
            } while (next != null && !reachedBack && pages < MaxPages)
        } catch (_: GhostException) {
            return
        } catch (_: SocialWebException) {
            return
        }
        // Pages overlap when notifications arrive between requests.
        val replies = candidates.distinctBy { it.post?.id }
        val done = handled.handled.value[account.id]?.keys.orEmpty()
        var checks = 0
        val confirmed = ArrayList<Notification>(replies.size)
        for (reply in replies) {
            val post = reply.post ?: continue
            if (post.id in done) continue
            if (post.replyCount > 0) {
                val key = "${account.id}|${post.id}"
                if (checked[key] != post.replyCount) {
                    if (checks >= MaxThreadChecks) continue
                    checks++
                    val answered = try {
                        client.replies(account.site, account.credential, post.id).children.any {
                            it.post.authoredByMe || it.post.author.handle.equals(me, ignoreCase = true)
                        }
                    } catch (_: GhostException) {
                        continue
                    } catch (_: SocialWebException) {
                        continue
                    }
                    if (answered) {
                        handled.markHandled(account.id, post.id)
                        continue
                    }
                    checked[key] = post.replyCount
                }
            }
            confirmed += reply
        }
        // The answer is for the account asked; it is dropped when another one became active meanwhile.
        if (accounts.activeId.first() == account.id) loaded.value = Loaded(account.id, confirmed)
    }

    private companion object {
        const val PageSize = 100
        const val MaxPages = 3
        const val MaxThreadChecks = 6
    }
}
