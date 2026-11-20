package co.magicpages.socialweb.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.feed.SavedFeedPosition
import co.magicpages.socialweb.data.feed.newPostsAbove
import co.magicpages.socialweb.data.feed.seenTopId
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.posts.FeedItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How many pages the restoring of a position may load to find the post the user was at. */
private const val MaxRestoreLoads = 15

/**
 * Keeps the list where the user left it, and counts what arrived above them.
 *
 * - The first post on screen, with its scroll offset, is saved per account and feed whenever the list
 *   comes to rest, and restored once per start of the app, loading further pages if that post is
 *   deeper than the first one.
 * - The newest post the user has seen is remembered too. Posts above it are new; while the user is
 *   below them the result says how many are above the screen. A refresh that brings posts when the
 *   user is at the very top shows them instead (the list would otherwise stay on the old first post).
 *
 * @return how many new posts are above the first visible one; 0 at the top.
 */
@Composable
internal fun rememberNewPosts(
    viewModel: FeedViewModel,
    accountId: String?,
    posts: LazyPagingItems<FeedItem>,
    listState: LazyListState,
): Int {
    var seenTop by rememberSaveable { mutableStateOf<String?>(null) }
    // Not saved with the screen: after the app's process died the position has to be restored again.
    var ready by remember { mutableStateOf(viewModel.positionRestored) }
    val ids by remember(posts) {
        derivedStateOf { posts.itemSnapshotList.map { it?.post?.id.orEmpty() } }
    }

    LaunchedEffect(accountId) {
        if (accountId == null || viewModel.positionRestored) return@LaunchedEffect
        snapshotFlow { posts.loadState.refresh to posts.itemCount }.first { (refresh, count) ->
            (count > 0 && refresh !is LoadState.Loading) || refresh is LoadState.Error ||
                (count == 0 && refresh is LoadState.NotLoading)
        }
        val saved = viewModel.savedPosition(accountId)
        seenTop = saved?.topId ?: ids.firstOrNull()
        if (saved != null && !saved.wasAtTop) restorePosition(posts, listState, saved)
        viewModel.positionRestored = true
        ready = true
    }

    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        var previous: Triple<String?, Int, Int>? = null
        snapshotFlow {
            Triple(ids.firstOrNull(), listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { now ->
            val before = previous
            previous = now
            val list = ids
            if (list.isEmpty()) return@collect
            val wasAtTop = before != null && before.first != null && before.second == 0 && before.third == 0
            if (wasAtTop && before?.first != now.first) {
                // The user was at the very top and what is first changed: show it, don't stay on the old first post.
                listState.scrollToItem(0)
                seenTop = list.first()
            } else {
                seenTop = seenTopId(list, seenTop, now.second)
            }
        }
    }

    LaunchedEffect(ready, accountId) {
        if (!ready || accountId == null) return@LaunchedEffect
        snapshotFlow {
            val anchor = ids.getOrNull(listState.firstVisibleItemIndex)?.takeIf { it.isNotEmpty() }
            val top = seenTop
            if (anchor == null || top == null || listState.isScrollInProgress) {
                null
            } else {
                SavedFeedPosition(anchor, listState.firstVisibleItemScrollOffset, top)
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { viewModel.savePosition(accountId, it) }
    }

    // Derived, so scrolling recomposes the caller only when the count changes, not on every frame.
    val count by remember(listState) {
        derivedStateOf { if (ready) newPostsAbove(ids, seenTop, listState.firstVisibleItemIndex) else 0 }
    }
    return count
}

private suspend fun restorePosition(posts: LazyPagingItems<FeedItem>, listState: LazyListState, target: SavedFeedPosition) {
    repeat(MaxRestoreLoads) {
        val index = posts.itemSnapshotList.indexOfFirst { it?.post?.id == target.anchorId }
        if (index >= 0) {
            listState.scrollToItem(index, target.offset)
            return
        }
        if (posts.itemCount == 0 || posts.loadState.append.endOfPaginationReached) return
        // Asking for the last item is what makes Paging fetch the next page.
        posts[posts.itemCount - 1]
        withTimeoutOrNull(1_000) { snapshotFlow { posts.loadState.append }.first { it is LoadState.Loading } }
        val append = snapshotFlow { posts.loadState.append }.first { it !is LoadState.Loading }
        if (append is LoadState.Error) return
    }
}

/** "N new posts", centred at the top of the list; tapping it is the caller's to answer, by scrolling up. */
@Composable
internal fun NewPostsPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Keep the last count while the pill fades out, so it doesn't change to "0 new posts" on its way.
    val shown = remember { intArrayOf(0) }
    if (count > 0) shown[0] = count
    AnimatedVisibility(
        visible = count > 0,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
    ) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 4.dp,
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The arrow up: the expand arrow turned around.
                Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp).rotate(180f))
                Text(
                    pluralStringResource(R.plurals.feed_new_posts, shown[0], shown[0]),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
