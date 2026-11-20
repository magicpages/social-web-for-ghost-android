package co.magicpages.socialweb.ui.posts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.components.AppIcons
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
 * with [articleCards] (the Reader), articles are cards instead. A "caught up" line goes above the
 * post [caughtUpAbove], unless that post is the first.
 */
fun LazyListScope.feedItems(
    posts: LazyPagingItems<FeedItem>,
    callbacks: PostCallbacks,
    articleCards: Boolean = false,
    caughtUpAbove: String? = null,
) {
    items(
        count = posts.itemCount,
        key = posts.itemKey { it.post.id },
        contentType = posts.itemContentType { if (articleCards && it.post.isArticle) "card" else "row" },
    ) { index ->
        val item = posts[index] ?: return@items
        val width = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth()
        // Part of the post's own item, so list indices stay those of the posts.
        val caughtUp = index > 0 && item.post.id == caughtUpAbove
        if (articleCards && item.post.isArticle) {
            Column(width) {
                if (caughtUp) CaughtUpLine()
                ArticleCard(item, callbacks, Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp))
            }
        } else {
            Column(width) {
                when {
                    caughtUp -> CaughtUpLine()
                    index > 0 -> HorizontalDivider()
                }
                PostRow(item, callbacks)
            }
        }
    }
    item(key = "append", contentType = "append") { AppendState(posts.loadState.append, onRetry = posts::retry) }
}

/** "You're all caught up" between two lines: the posts below it were already there last time. */
@Composable
private fun CaughtUpLine() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
        Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.feed_caught_up), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
    }
}
