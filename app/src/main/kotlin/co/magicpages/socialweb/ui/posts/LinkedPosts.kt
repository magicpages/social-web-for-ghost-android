package co.magicpages.socialweb.ui.posts

import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads the posts other posts point at (the post a quote quotes, the post a reply answers) once
 * per session of the active account: the same quote often shows in the feed, the thread and a
 * profile, and each lookup can mean the service fetching it remotely. Posts carry the account's own
 * state (liked, reposted), so switching accounts forgets them all.
 */
@Singleton
class LinkedPosts @Inject constructor(
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
    @ApplicationScope scope: CoroutineScope,
) {
    sealed interface Result {
        data class Found(val item: FeedItem) : Result

        /** Deleted, private, not a post, or its server is gone. */
        data object Missing : Result
    }

    private val cache = LruCache<String, Result>(200)

    init {
        scope.launch { accounts.activeId.drop(1).collect { cache.evictAll() } }
    }

    fun cached(url: String): Result? = cache.get(url)

    /** Throws [GhostException] or [SocialWebException] when it can't be told right now; those aren't cached. */
    suspend fun load(url: String): Result {
        cache.get(url)?.let { return it }
        val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
        val result = try {
            val post = client.post(account.site, account.credential, url)
            Result.Found(withContext(Dispatchers.Default) { post.toFeedItem() })
        } catch (e: SocialWebException) {
            if (e.status != 404) throw e
            Result.Missing
        }
        // Only kept for the account it was loaded as; another may have become active while it loaded.
        if (accounts.activeId.first() == account.id) cache.put(url, result)
        return result
    }
}

/** Provided once signed in; null in previews, where quotes show as plain links. */
val LocalLinkedPosts = staticCompositionLocalOf<LinkedPosts?> { null }
