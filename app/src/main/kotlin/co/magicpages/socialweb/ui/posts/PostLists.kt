package co.magicpages.socialweb.ui.posts

import androidx.paging.PagingSource
import androidx.paging.PagingState
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.ImageRejectedException
import co.magicpages.socialweb.data.activitypub.ImageRejection
import co.magicpages.socialweb.data.activitypub.PickedImage
import co.magicpages.socialweb.data.activitypub.Moderation
import co.magicpages.socialweb.data.activitypub.PostInteractions
import co.magicpages.socialweb.data.activitypub.PostsPage
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.fediverse.PublicServerException
import co.magicpages.socialweb.data.ghost.GhostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pages through a post list with the server's opaque `next` cursor. Pages [authoritative] for
 * interaction state replace optimistic overrides; others leave them alone. With [groups], the
 * several entries of a post that was reposted by several accounts become one item.
 */
class PostsPagingSource(
    private val interactions: PostInteractions,
    private val authoritative: Boolean = true,
    private val groups: RepostGroups? = null,
    private val loadPage: suspend (next: String?) -> PostsPage,
) : PagingSource<String, FeedItem>() {
    // Lists are newest-first, so a post can slide into the next page while the user scrolls.
    // Paging and LazyColumn keys both need each post once per generation.
    private val seen = HashSet<String>()

    override suspend fun load(params: LoadParams<String>): LoadResult<String, FeedItem> = try {
        val page = loadPage(params.key)
        if (authoritative) interactions.reconcile(page.posts)
        val items = withContext(Dispatchers.Default) {
            if (groups != null) {
                groups.fold(page.posts).map { it.post.toFeedItem().copy(reposters = it.reposters) }
            } else {
                page.posts.filter { seen.add(it.id) }.map { it.toFeedItem() }
            }
        }
        LoadResult.Page(data = items, prevKey = null, nextKey = page.next)
    } catch (e: GhostException) {
        LoadResult.Error(e)
    } catch (e: SocialWebException) {
        LoadResult.Error(e)
    } catch (e: PublicServerException) {
        LoadResult.Error(e)
    }

    // Cursor lists can't resume mid-way; refreshes start from the newest post.
    override fun getRefreshKey(state: PagingState<String, FeedItem>): String? = null
}

data class ReplyUiState(
    val target: FeedItem,
    val text: String = "",
    /** The picked image's `content://` Uri. */
    val image: String? = null,
    val altText: String = "",
    val sending: Boolean = false,
    val failure: ReplyFailure? = null,
) {
    val canSend: Boolean get() = text.isNotBlank()
    val hasDraft: Boolean get() = text.isNotBlank() || image != null
}

enum class ReplyFailure(val message: Int) {
    ImageTooLarge(R.string.note_image_too_large),
    ImageUnsupported(R.string.note_image_unsupported),
    Failed(R.string.reply_failed),
}

/** State behind [ReplyEditor], one per screen. */
class ReplyComposer(private val scope: CoroutineScope, private val interactions: PostInteractions) {
    private val _state = MutableStateFlow<ReplyUiState?>(null)
    val state: StateFlow<ReplyUiState?> = _state.asStateFlow()

    private val _posted = MutableSharedFlow<FeedItem>(extraBufferCapacity = 1)

    /** The post replied to, each time a reply goes through. */
    val posted: Flow<FeedItem> = _posted

    fun start(item: FeedItem) {
        if (item.post.isDeleted) return
        _state.value = ReplyUiState(item)
    }

    fun onTextChange(text: String) = _state.update { it?.copy(text = text, failure = null) }

    fun pickImage(uri: String) = _state.update { it?.copy(image = uri, altText = "", failure = null) }

    fun removeImage() = _state.update { it?.copy(image = null, altText = "", failure = null) }

    fun onAltTextChange(altText: String) = _state.update { it?.copy(altText = altText) }

    fun dismiss() = _state.update { if (it?.sending == true) it else null }

    fun send() {
        val current = _state.value ?: return
        if (current.sending || !current.canSend) return
        _state.value = current.copy(sending = true, failure = null)
        scope.launch {
            val failure = try {
                interactions.reply(current.target.post, current.text, current.image?.let { PickedImage(it, current.altText) })
                null
            } catch (e: ImageRejectedException) {
                when (e.reason) {
                    ImageRejection.TooLarge -> ReplyFailure.ImageTooLarge
                    ImageRejection.UnsupportedType -> ReplyFailure.ImageUnsupported
                    ImageRejection.Unreadable -> ReplyFailure.Failed
                }
            } catch (_: SocialWebException) {
                ReplyFailure.Failed
            } catch (_: GhostException) {
                ReplyFailure.Failed
            }
            if (failure == null) {
                _state.value = null
                _posted.emit(current.target)
            } else {
                _state.update { it?.copy(sending = false, failure = failure) }
            }
        }
    }
}

/**
 * Snackbar messages every post screen shows: failed likes and reposts, posted replies, deletes and blocks.
 * A screen that closes over a delete or block passes [takesReport] to leave it for the one it returns to.
 */
fun postMessages(
    interactions: PostInteractions,
    composer: ReplyComposer,
    moderation: Moderation,
    takesReport: (Moderation.Report) -> Boolean = { true },
): Flow<Int> = merge(
    interactions.failures.map {
        when (it) {
            PostInteractions.Failure.Like -> R.string.like_failed
            PostInteractions.Failure.Repost -> R.string.repost_failed
        }
    },
    composer.posted.map { R.string.reply_posted },
    moderation.outcomes(takesReport).map {
        when (it) {
            Moderation.Outcome.Deleted -> R.string.post_delete_done
            Moderation.Outcome.DeleteFailed -> R.string.delete_failed
            Moderation.Outcome.Blocked -> R.string.account_blocked
            Moderation.Outcome.BlockFailed -> R.string.block_failed
            Moderation.Outcome.ServerBlocked -> R.string.server_blocked
            Moderation.Outcome.ServerBlockFailed -> R.string.server_block_failed
        }
    },
)
