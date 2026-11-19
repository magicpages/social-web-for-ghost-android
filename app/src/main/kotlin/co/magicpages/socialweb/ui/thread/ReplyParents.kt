package co.magicpages.socialweb.ui.thread

import android.util.LruCache
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.RemoteObjects
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.di.ApplicationScope
import co.magicpages.socialweb.ui.posts.FeedItem
import co.magicpages.socialweb.ui.posts.LinkedPosts
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Finds the post a reply answers when the service has no ancestors for it. The service only links
 * a reply to a parent it stored, and it doesn't store everything a post can answer (a PeerTube
 * video, a post it couldn't fetch at the time), nor does its API say what the reply was to. The
 * reply's own server does, so ask it, then look the parent up like a quoted post.
 */
@Singleton
class ReplyParents @Inject constructor(
    private val remote: RemoteObjects,
    private val linked: LinkedPosts,
    private val accounts: AccountStore,
    @ApplicationScope scope: CoroutineScope,
) {
    sealed interface Parent {
        /** A post the app can show and open. */
        data class Found(val item: FeedItem) : Parent

        /** Something the service can't show, such as a video: link to it instead. */
        data class Elsewhere(val url: String, val title: String?) : Parent

        /** Not a reply, or its server won't say. */
        data object None : Parent
    }

    private val cache = LruCache<String, Parent>(100)

    init {
        // A parent is looked up as the active account and can carry its liked/reposted state.
        scope.launch { accounts.activeId.drop(1).collect { cache.evictAll() } }
    }

    fun cached(postId: String): Parent? = cache.get(postId)

    /** Never throws; when it can't be told right now the answer is [Parent.None], and isn't kept. */
    suspend fun of(postId: String): Parent {
        cache.get(postId)?.let { return it }
        val owner = accounts.activeId.first()
        val reply = remote.fetch(postId) ?: return Parent.None
        val parentId = reply.inReplyTo?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val parent = if (parentId == null) Parent.None else lookUp(parentId) ?: return Parent.None
        // Only kept for the account it was looked up as.
        if (accounts.activeId.first() == owner) cache.put(postId, parent)
        return parent
    }

    /** Null when the site couldn't be reached. */
    private suspend fun lookUp(parentId: String): Parent? {
        val result = try {
            linked.load(parentId)
        } catch (_: GhostException) {
            return null
        } catch (_: SocialWebException) {
            // The service answered but can't turn it into a post; the link still works.
            LinkedPosts.Result.Missing
        }
        return when (result) {
            is LinkedPosts.Result.Found -> Parent.Found(result.item)
            LinkedPosts.Result.Missing -> {
                val parent = remote.fetch(parentId)
                Parent.Elsewhere(url = parent?.url ?: parentId, title = parent?.name?.trim()?.takeIf { it.isNotEmpty() })
            }
        }
    }
}
