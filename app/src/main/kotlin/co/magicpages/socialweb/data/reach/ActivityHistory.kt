package co.magicpages.socialweb.data.reach

import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.Notification
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The site's notifications, newest first, as far back as a reach screen asked for. Kept for a few
 * minutes, so going from the overview to a post and back doesn't page through them all again.
 */
data class ActivityWindow(
    val notifications: List<Notification>,
    /** When the oldest notification fetched came in; null when there are none. */
    val oldestMillis: Long?,
    /** Whether that's every notification the site has. */
    val complete: Boolean,
) {
    /** Whether everything since [millis] is here. */
    fun covers(millis: Long): Boolean = complete || (oldestMillis != null && oldestMillis <= millis)
}

@Singleton
class ActivityHistory @Inject constructor(private val activityPub: ActivityPubClient) {
    private class Scan(val key: Pair<String, String>, val startedAt: Long) {
        val notifications = ArrayList<Notification>()
        var next: String? = null
        var done = false
        var pages = 0
        var oldestMillis: Long? = null
    }

    private val mutex = Mutex()
    private var scan: Scan? = null

    /**
     * Notifications back to [sinceMillis], or as far as [MaxPages] pages go: a busy site can have
     * thousands, and a screen that waits on all of them shows nothing at all.
     */
    suspend fun since(account: StoredAccount, sinceMillis: Long, now: Long = System.currentTimeMillis()): ActivityWindow =
        mutex.withLock {
            val key = account.site.siteUrl to account.profile.handle
            val current = scan?.takeIf { it.key == key && now - it.startedAt < FreshMillis } ?: Scan(key, now).also { scan = it }
            while (!current.done && current.pages < MaxPages && (current.oldestMillis ?: Long.MAX_VALUE) > sinceMillis) {
                val page = activityPub.notifications(account.site, account.credential, current.next, limit = PageSize)
                current.pages++
                current.notifications += page.notifications
                page.notifications.lastOrNull()?.let { timestampMillis(it.createdAt) }?.let { current.oldestMillis = it }
                current.next = page.next
                current.done = page.next == null || page.notifications.isEmpty()
            }
            ActivityWindow(current.notifications.toList(), current.oldestMillis, current.done)
        }

    private companion object {
        const val PageSize = 100
        const val MaxPages = 20
        const val FreshMillis = 3 * 60 * 1000L
    }
}
