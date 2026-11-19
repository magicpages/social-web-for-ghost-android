package co.magicpages.socialweb.ui.posts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import co.magicpages.socialweb.ui.components.AppendState
import co.magicpages.socialweb.ui.theme.Gutter
import kotlinx.coroutines.flow.Flow

/** Widest a post column gets, so lines stay readable on tablets and in landscape. */
val MaxContentWidth = 640.dp

/** Shows each string resource from [messages] as a snackbar. */
@Composable
fun CollectMessages(messages: Flow<Int>, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(messages) {
        messages.collect { snackbar.showSnackbar(resources.getString(it)) }
    }
}

/**
 * A paged post list, followed by its loading/retry footer. Posts are flat rows split by dividers;
 * with [articleCards] (the Reader), articles are cards instead.
 */
fun LazyListScope.feedItems(posts: LazyPagingItems<FeedItem>, callbacks: PostCallbacks, articleCards: Boolean = false) {
    items(
        count = posts.itemCount,
        key = posts.itemKey { it.post.id },
        contentType = posts.itemContentType { if (articleCards && it.post.isArticle) "card" else "row" },
    ) { index ->
        val item = posts[index] ?: return@items
        val width = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth()
        if (articleCards && item.post.isArticle) {
            ArticleCard(item, callbacks, width.padding(horizontal = Gutter, vertical = 6.dp))
        } else {
            Column(width) {
                if (index > 0) HorizontalDivider()
                PostRow(item, callbacks)
            }
        }
    }
    item(key = "append", contentType = "append") { AppendState(posts.loadState.append, onRetry = posts::retry) }
}
