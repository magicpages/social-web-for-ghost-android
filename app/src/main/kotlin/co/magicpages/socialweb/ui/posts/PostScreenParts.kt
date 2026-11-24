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
 * with [reader] (the Reader), articles are laid out as a magazine instead. A "caught up" line goes
 * above the post [caughtUpAbove], unless that post is the first.
 */
fun LazyListScope.feedItems(
    posts: LazyPagingItems<FeedItem>,
    callbacks: PostCallbacks,
    reader: ReaderPage? = null,
    caughtUpAbove: String? = null,
) {
    val magazine = reader?.magazine
    items(
        count = posts.itemCount,
        key = posts.itemKey { it.post.id },
        contentType = posts.itemContentType { item ->
            when {
                magazine == null || !ReaderLayout.isMagazineArticle(item) -> "row"
                item.post.id in magazine.absorbed -> "absorbed"
                else -> magazine.blocks[item.post.id]?.contentType ?: ReaderStyle.Compact
            }
        },
    ) { index ->
        val item = posts[index] ?: return@items
        val block = if (reader != null && ReaderLayout.isMagazineArticle(item)) {
            // Drawn with the post its pair starts at; an empty item keeps this post's place in the list.
            if (item.post.id in reader.magazine.absorbed) return@items
            // Not laid out yet (the list changed a moment before its layout did): a compact card meanwhile.
            reader.magazine.blocks[item.post.id] ?: ReaderBlock.Single(item, ReaderStyle.Compact)
        } else {
            null
        }
        // Part of the post's own item, so list indices stay those of the posts.
        val caughtUp = index > 0 && item.post.id == caughtUpAbove
        Column(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth()) {
            if (reader != null && block != null) {
                if (caughtUp) CaughtUpLine()
                ReaderBlockView(block, callbacks, reader)
            } else {
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

/** What kind of list item a block is, so the list reuses the same kind for it. */
private val ReaderBlock.contentType: Any
    get() = when (this) {
        is ReaderBlock.Single -> style
        is ReaderBlock.TallPair -> "pair"
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
