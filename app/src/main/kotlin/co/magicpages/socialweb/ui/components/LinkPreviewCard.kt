package co.magicpages.socialweb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.linkpreview.LinkPreview
import co.magicpages.socialweb.data.linkpreview.firstLinkIn
import co.magicpages.socialweb.data.linkpreview.LinkPreviews
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/** Provided once signed in; null in previews, where links get no card. */
val LocalLinkPreviews = staticCompositionLocalOf<LinkPreviews?> { null }

/**
 * A card for the page at [url]: its image, site, title and a short description; it opens the page.
 * It loads when first composed and draws nothing until the page has answered, and for good when it
 * can't be shown, so the content around it only moves when the card appears. [onDismiss] adds a
 * close button, for a card the reader may take away.
 */
@Composable
fun LinkPreviewCard(url: String, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val previews = LocalLinkPreviews.current ?: return
    // Keyed by url: the composer's card follows the link as it is edited.
    var result by remember(url) { mutableStateOf(previews.cached(url)) }
    LaunchedEffect(url) {
        if (result == null) result = previews.load(url)
    }
    val preview = (result as? LinkPreviews.Result.Found)?.preview ?: return
    LinkPreviewContent(preview, url, modifier, onDismiss)
}

@Composable
private fun LinkPreviewContent(preview: LinkPreview, url: String, modifier: Modifier, onDismiss: (() -> Unit)?) {
    val uriHandler = LocalUriHandler.current
    // A picture that can't be loaded leaves a blank block: take it away rather than show that.
    var imageFailed by remember(preview.imageUrl) { mutableStateOf(false) }
    val showImage = preview.imageUrl != null && !imageFailed
    Box(modifier.fillMaxWidth()) {
        OutlinedCard(onClick = { uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth()) {
            if (showImage) {
                AsyncImage(
                    model = preview.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { imageFailed = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ImageRatio)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
            Column(
                // Without an image the close button sits over the text's corner.
                Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = if (onDismiss != null && !showImage) 56.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // A home page's title is often just the site's name; don't say it twice.
                if (preview.siteName.isNotEmpty() && !preview.siteName.equals(preview.title, ignoreCase = true)) {
                    Text(
                        preview.siteName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(preview.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                preview.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (onDismiss != null) {
            FilledTonalIconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.link_preview_remove))
            }
        }
    }
}

/** The shape Open Graph images are made for. */
private const val ImageRatio = 1.91f

/**
 * The card for the first link typed in [text], once typing has paused. [hidden] takes it away
 * (an attached image is the note's own preview). The close button hides the card for that link
 * only; it is nothing but this screen's view: the server makes its own preview.
 */
@Composable
fun ComposerLinkPreview(text: String, hidden: Boolean, modifier: Modifier = Modifier) {
    val link = remember(text) { firstLinkIn(text) }
    var settled by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(link) {
        if (link == null) {
            settled = null
        } else {
            delay(TypingPauseMillis)
            settled = link
        }
    }
    var dismissed by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = settled
    if (!hidden && shown != null && shown != dismissed) {
        LinkPreviewCard(shown, modifier, onDismiss = { dismissed = shown })
    }
}

/** A link is only fetched once its writer has stopped typing it. */
private const val TypingPauseMillis = 600L
