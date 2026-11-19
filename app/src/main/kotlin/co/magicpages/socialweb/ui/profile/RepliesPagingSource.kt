package co.magicpages.socialweb.ui.profile

import androidx.paging.PagingSource
import androidx.paging.PagingState
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.OutboxReply
import co.magicpages.socialweb.data.activitypub.RemoteObjects
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.posts.FeedItem
import co.magicpages.socialweb.ui.posts.LinkedPosts
import co.magicpages.socialweb.ui.posts.toFeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The replies an account on another server wrote, newest first. The service lists no replies, so
 * their ids come from the account's own outbox, and each reply then comes through the service, which
 * knows whether the site liked or reposted it. Each carries whom it answers.
 */
class RepliesPagingSource(
    private val remote: RemoteObjects,
    private val activityPub: ActivityPubClient,
    private val linked: LinkedPosts,
    private val account: suspend () -> StoredAccount,
    /** The account's ActivityPub id. */
    private val actorId: suspend () -> String,
) : PagingSource<String, FeedItem>() {
    private val seen = HashSet<String>()

    override suspend fun load(params: LoadParams<String>): LoadResult<String, FeedItem> = try {
        val actor = actorId()
        var cursor = params.key
        var pagesRead = 0
        var items: List<FeedItem>
        // Outbox pages count posts and reposts too, so one can hold no reply at all. Read on a little
        // so the list doesn't end up looking empty, but stop before it turns into a long wait.
        do {
            val page = remote.outboxReplies(actor, cursor)
            items = coroutineScope { page.replies.map { async { item(it) } }.awaitAll() }
                .filterNotNull()
                .filter { seen.add(it.post.id) }
            cursor = page.next
            pagesRead++
        } while (items.isEmpty() && cursor != null && pagesRead < MaxOutboxPagesPerLoad)
        LoadResult.Page(data = items, prevKey = null, nextKey = cursor)
    } catch (e: GhostException) {
        LoadResult.Error(e)
    } catch (e: SocialWebException) {
        LoadResult.Error(e)
    }

    /** Null when the service can't show the reply: deleted since, or not public. */
    private suspend fun item(reply: OutboxReply): FeedItem? {
        val current = account()
        val post = try {
            activityPub.post(current.site, current.credential, reply.id)
        } catch (_: SocialWebException) {
            return null
        }
        // The answered post may be gone or out of reach; the reply still shows, just without the name.
        val parent = try {
            linked.load(reply.inReplyTo) as? LinkedPosts.Result.Found
        } catch (_: SocialWebException) {
            null
        }
        return withContext(Dispatchers.Default) { post.toFeedItem() }.copy(replyingTo = parent?.item?.post?.author)
    }

    // Cursor lists can't resume mid-way; refreshes start from the newest reply.
    override fun getRefreshKey(state: PagingState<String, FeedItem>): String? = null

    private companion object {
        const val MaxOutboxPagesPerLoad = 5
    }
}
