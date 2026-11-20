@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package co.magicpages.socialweb.ui.article

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.ui.browser.PortalPage
import co.magicpages.socialweb.ui.browser.openPortal
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.EmojiText
import co.magicpages.socialweb.ui.components.emojiSourceId
import co.magicpages.socialweb.ui.components.HtmlText
import co.magicpages.socialweb.ui.components.LocalImageViewer
import co.magicpages.socialweb.ui.components.ViewerImage
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.posts.articleByline
import co.magicpages.socialweb.ui.posts.displayName
import co.magicpages.socialweb.ui.theme.Gutter
import co.magicpages.socialweb.ui.theme.reading
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Emits an article as list items: the header (feature image, title, dek, byline), then one item
 * per block of [document]. One item per block keeps long articles lazy; each is capped to the
 * app's content width, so the text column stays readable on tablets.
 */
fun LazyListScope.articleItems(
    post: Post,
    document: ArticleDocument,
    onOpenProfile: (Author) -> Unit,
    access: ArticleAccess? = null,
) {
    val dek = document.dek(post.summary)
    item(key = "article:header", contentType = "article:header") {
        ArticleHeader(post, dek, onOpenProfile)
    }
    itemsIndexed(
        document.blocks,
        key = { index, _ -> "article:$index" },
        contentType = { _, block -> block.contentType },
    ) { _, block ->
        CompositionLocalProvider(LocalArticleAccess provides access) {
            BlockItem(block)
        }
    }
}

private val ArticleBlock.contentType: String
    get() = when (this) {
        is ArticleBlock.Paragraph -> "article:paragraph"
        is ArticleBlock.Heading -> "article:heading"
        is ArticleBlock.Quote -> "article:quote"
        is ArticleBlock.BulletList -> "article:list"
        is ArticleBlock.Code -> "article:code"
        ArticleBlock.Divider -> "article:divider"
        is ArticleBlock.Image -> "article:image"
        is ArticleBlock.Gallery -> "article:gallery"
        is ArticleBlock.Bookmark -> "article:bookmark"
        is ArticleBlock.Callout -> "article:callout"
        is ArticleBlock.Button -> "article:button"
        is ArticleBlock.Toggle -> "article:toggle"
        is ArticleBlock.Audio -> "article:audio"
        is ArticleBlock.Video -> "article:video"
        is ArticleBlock.Embed -> "article:embed"
        is ArticleBlock.File -> "article:file"
        is ArticleBlock.Table -> "article:table"
        is ArticleBlock.MembersOnly -> "article:members-only"
    }

@Composable
private fun ArticleHeader(post: Post, dek: String?, onOpenProfile: (Author) -> Unit) {
    Column(
        Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        post.featureImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val viewer = LocalImageViewer.current
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.image_cover),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(onClickLabel = stringResource(R.string.image_view), role = Role.Image) {
                        viewer.show(listOf(ViewerImage(url)), 0)
                    },
            )
        }
        Text(
            post.title,
            style = MaterialTheme.typography.headlineLargeEmphasized,
            modifier = Modifier.semantics { heading() },
        )
        if (dek != null) {
            Text(
                dek,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Byline(post, onOpenProfile)
    }
}

@Composable
private fun Byline(post: Post, onOpenProfile: (Author) -> Unit) {
    val author = post.author
    val context = LocalContext.current
    val published = remember(post.publishedAt) { formatPublished(context, post.publishedAt) }
    val readingTime = if (post.readingTimeMinutes > 0) {
        pluralStringResource(R.plurals.post_reading_time, post.readingTimeMinutes, post.readingTimeMinutes)
    } else {
        null
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = stringResource(R.string.open_profile), role = Role.Button) { onOpenProfile(author) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(author.avatarUrl, author.displayName, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                EmojiText(
                    author.displayName,
                    author.emojiSourceId,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    author.handle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        articleByline(post)?.let {
            Text(it, style = MaterialTheme.typography.titleSmall)
        }
        val meta = listOfNotNull(published, readingTime).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatPublished(context: Context, publishedAt: String?): String? {
    val millis = try {
        Instant.parse(publishedAt ?: return null).toEpochMilli()
    } catch (_: DateTimeParseException) {
        return null
    }
    return DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
    )
}

/** Caps [block] to the content column; wide images reach 8 dp past the text and full ones the screen edges. */
@Composable
private fun BlockItem(block: ArticleBlock) {
    val width = (block as? ArticleBlock.Image)?.width
    val modifier = when (width) {
        ImageWidth.Full -> Modifier.fillMaxWidth()
        ImageWidth.Wide -> Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(horizontal = 8.dp)
        else -> Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(horizontal = Gutter)
    }
    Box(modifier) {
        Block(block, inCard = false)
    }
}

/**
 * One block. Each carries its own spacing below (and above, for headings), so it looks the same
 * as a list item and nested in a [ArticleBlock.Toggle]. [inCard] keeps full-width images inside the card.
 */
@Composable
private fun Block(block: ArticleBlock, inCard: Boolean) {
    when (block) {
        is ArticleBlock.Paragraph -> ParagraphBlock(block)
        is ArticleBlock.Heading -> HeadingBlock(block)
        is ArticleBlock.Quote -> QuoteBlock(block)
        is ArticleBlock.BulletList -> ListBlock(block)
        is ArticleBlock.Code -> CodeBlock(block)
        ArticleBlock.Divider -> HorizontalDivider(Modifier.padding(vertical = 24.dp))
        is ArticleBlock.Image -> ImageBlock(block, full = block.width == ImageWidth.Full && !inCard)
        is ArticleBlock.Gallery -> GalleryBlock(block)
        is ArticleBlock.Bookmark -> BookmarkBlock(block)
        is ArticleBlock.Callout -> CalloutBlock(block)
        is ArticleBlock.Button -> ButtonBlock(block)
        is ArticleBlock.Toggle -> ToggleBlock(block)
        is ArticleBlock.Audio -> AudioPlayer(block.url, block.title, Modifier.padding(bottom = 16.dp))
        is ArticleBlock.Video -> VideoBlock(block)
        is ArticleBlock.Embed -> EmbedBlock(block)
        is ArticleBlock.File -> FileBlock(block)
        is ArticleBlock.Table -> TableBlock(block)
        is ArticleBlock.MembersOnly -> MembersOnlyBlock(block)
    }
}

/** What the reader knows about the article's gate; read by [MembersOnlyBlock]. Null means nothing is known. */
private val LocalArticleAccess = staticCompositionLocalOf<ArticleAccess?> { null }

/**
 * Where a members-only post stops for a reader without access: why, and a way in. [ArticleBlock.MembersOnly] only
 * stays in a post while it is locked, so this is also the loading and "couldn't unlock" state of [ArticleAccess].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembersOnlyBlock(block: ArticleBlock.MembersOnly) {
    val access = LocalArticleAccess.current
    val context = LocalContext.current
    val siteUrl = access?.siteUrl
    val status = access?.status
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp).padding(bottom = 16.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                block.title ?: stringResource(R.string.article_members_title),
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            when (status) {
                ArticleAccess.Status.Loading -> {
                    Text(stringResource(R.string.article_members_loading), style = MaterialTheme.typography.bodyMedium)
                    LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
                }
                ArticleAccess.Status.Unavailable ->
                    Text(stringResource(R.string.article_members_unavailable), style = MaterialTheme.typography.bodyMedium)
                else ->
                    Text(block.message ?: stringResource(R.string.article_members_message), style = MaterialTheme.typography.bodyMedium)
            }
            if (siteUrl != null && status != ArticleAccess.Status.Loading) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openPortal(context, siteUrl, PortalPage.SignIn) }) {
                        Icon(AppIcons.Login, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.article_sign_in))
                    }
                    FilledTonalButton(onClick = { openPortal(context, siteUrl, PortalPage.SignUp) }) {
                        Text(stringResource(R.string.membership_join))
                    }
                }
            }
        }
    }
}

@Composable
private fun ParagraphBlock(block: ArticleBlock.Paragraph) {
    HtmlText(block.text, Modifier.fillMaxWidth().padding(bottom = 16.dp), MaterialTheme.typography.reading)
}

@Composable
private fun HeadingBlock(block: ArticleBlock.Heading) {
    val typography = MaterialTheme.typography
    val style = when (block.level) {
        1, 2 -> typography.headlineSmallEmphasized
        3 -> typography.titleLargeEmphasized
        else -> typography.titleMediumEmphasized
    }
    HtmlText(
        block.text,
        Modifier
            .fillMaxWidth()
            .padding(top = if (block.level <= 2) 16.dp else 8.dp, bottom = 8.dp)
            .semantics { heading() },
        style,
    )
}

@Composable
private fun QuoteBlock(block: ArticleBlock.Quote) {
    val typography = MaterialTheme.typography
    if (block.alternative) {
        val style = remember(typography) { typography.headlineSmall.copy(fontStyle = FontStyle.Italic, textAlign = TextAlign.Center) }
        HtmlText(block.text, Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), style)
    } else {
        val style = remember(typography) { typography.titleLarge.copy(fontStyle = FontStyle.Italic) }
        Row(Modifier.padding(bottom = 16.dp).height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            HtmlText(block.text, Modifier.weight(1f).padding(start = 16.dp), style)
        }
    }
}

@Composable
private fun ListBlock(block: ArticleBlock.BulletList) {
    val style = MaterialTheme.typography.reading
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.entries.forEach { entry ->
            Row(Modifier.padding(start = (20 * entry.depth.coerceAtLeast(0)).dp)) {
                Text(entry.marker, style = style, modifier = Modifier.widthIn(min = 28.dp))
                HtmlText(entry.text, Modifier.weight(1f), style)
            }
        }
    }
}

@Composable
private fun CodeBlock(block: ArticleBlock.Code) {
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            block.code.trimEnd(),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}

@Composable
private fun Caption(text: AnnotatedString, modifier: Modifier = Modifier) {
    val base = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val style = remember(base, color) { base.copy(color = color, textAlign = TextAlign.Center) }
    HtmlText(text, modifier.fillMaxWidth().padding(top = 8.dp), style)
}

@Composable
private fun ImageBlock(block: ArticleBlock.Image, full: Boolean) {
    val image = block.image
    val viewer = LocalImageViewer.current
    val uriHandler = LocalUriHandler.current
    val alt = image.alt?.takeIf { it.isNotBlank() }
    // Without dimensions in the HTML the box starts 16:9 and takes the picture's own shape once it loads.
    var loadedRatio by remember(image.url) { mutableStateOf<Float?>(null) }
    val ratio = image.aspectRatio ?: loadedRatio ?: (16f / 9f)
    val shape: Shape = if (full) RectangleShape else MaterialTheme.shapes.large
    val link = block.link?.takeIf { it.isNotBlank() }
    val click = if (link != null) {
        Modifier.clickable(onClickLabel = stringResource(R.string.article_open_link), role = Role.Button) { uriHandler.openUri(link) }
    } else {
        Modifier.clickable(onClickLabel = stringResource(R.string.image_view), role = Role.Image) {
            viewer.show(listOf(ViewerImage(image.url, alt)), 0)
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(
            model = image.url,
            contentDescription = alt,
            contentScale = ContentScale.Crop,
            onSuccess = { state ->
                val size = state.painter.intrinsicSize
                if (size.isSpecified && size.width > 0f && size.height > 0f) loadedRatio = size.width / size.height
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .then(click),
        )
        block.caption?.takeIf { it.isNotEmpty() }?.let {
            Caption(it, Modifier.widthIn(max = MaxContentWidth).padding(horizontal = if (full) Gutter else 8.dp))
        }
    }
}

@Composable
private fun GalleryBlock(block: ArticleBlock.Gallery) {
    val viewer = LocalImageViewer.current
    // One viewer pages through the whole gallery, so each row needs the index of its first image.
    val all = remember(block.rows) {
        block.rows.flatten().map { ViewerImage(it.url, it.alt?.takeIf(String::isNotBlank)) }
    }
    val starts = remember(block.rows) { block.rows.runningFold(0) { sum, row -> sum + row.size } }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.clip(MaterialTheme.shapes.large), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.rows.forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEachIndexed { column, image ->
                        // Widths in proportion to the aspect ratios give every image in the row the same height.
                        val ratio = image.aspectRatio ?: 1f
                        AsyncImage(
                            model = image.url,
                            contentDescription = image.alt?.takeIf { it.isNotBlank() },
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(ratio)
                                .aspectRatio(ratio)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable(onClickLabel = stringResource(R.string.image_view), role = Role.Image) {
                                    viewer.show(all, starts[rowIndex] + column)
                                },
                        )
                    }
                }
            }
        }
        block.caption?.takeIf { it.isNotEmpty() }?.let { Caption(it, Modifier.padding(horizontal = 8.dp)) }
    }
}

/**
 * A link card. On a compact width the thumbnail runs across the top of the card, as a link preview does in a feed;
 * wider screens keep it small, beside the text.
 */
@Composable
private fun BookmarkBlock(block: ArticleBlock.Bookmark) {
    // The width the block is given, not the window's: a thread in the detail pane beside the tabs is narrow in a wide window.
    BoxWithConstraints { BookmarkCard(block, compact = maxWidth < CompactWidthDp.dp) }
}

@Composable
private fun BookmarkCard(block: ArticleBlock.Bookmark, compact: Boolean) {
    val uriHandler = LocalUriHandler.current
    val thumbnail = block.thumbnailUrl?.takeIf { it.isNotBlank() }
    OutlinedCard(onClick = { uriHandler.openUri(block.url) }, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        if (compact) {
            Column {
                if (thumbnail != null) {
                    AsyncImage(
                        model = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(BookmarkThumbnailRatio)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                }
                BookmarkText(block, Modifier.padding(16.dp))
            }
        } else {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookmarkText(block, Modifier.weight(1f))
                if (thumbnail != null) {
                    AsyncImage(
                        model = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                }
            }
        }
    }
}

/** Below this width (the Material "compact" window class) a bookmark's thumbnail goes on top. */
private const val CompactWidthDp = 600

/** The 1.91:1 of the preview images link cards are made for. */
private const val BookmarkThumbnailRatio = 1.91f

@Composable
private fun BookmarkText(block: ArticleBlock.Bookmark, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(block.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        block.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val source = listOfNotNull(block.publisher, block.author).filter { it.isNotBlank() }.joinToString(" · ")
        Row(
            Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!block.iconUrl.isNullOrBlank()) {
                AsyncImage(
                    model = block.iconUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(16.dp).clip(MaterialTheme.shapes.extraSmall),
                )
            } else {
                Icon(
                    AppIcons.Public,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                source.ifEmpty { block.url.substringAfter("://").substringBefore('/') },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CalloutBlock(block: ArticleBlock.Callout) {
    val colors = MaterialTheme.colorScheme
    // Ghost's callout colours have no M3 counterpart; each lands on the container role that reads closest.
    val (container, content) = when (block.color) {
        "accent", "purple" -> colors.primaryContainer to colors.onPrimaryContainer
        "blue" -> colors.secondaryContainer to colors.onSecondaryContainer
        "green", "yellow" -> colors.tertiaryContainer to colors.onTertiaryContainer
        "red", "pink" -> colors.errorContainer to colors.onErrorContainer
        else -> colors.surfaceContainer to colors.onSurface
    }
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            block.emoji?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.headlineSmall)
            }
            HtmlText(block.text, Modifier.weight(1f), MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ButtonBlock(block: ArticleBlock.Button) {
    val uriHandler = LocalUriHandler.current
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
        Button(onClick = { uriHandler.openUri(block.url) }) { Text(block.text) }
    }
}

@Composable
private fun ToggleBlock(block: ArticleBlock.Toggle) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val motion = MaterialTheme.motionScheme
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, motion.defaultEffectsSpec(), label = "toggle")
    OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        ListItem(
            headlineContent = { HtmlText(block.heading, style = MaterialTheme.typography.titleMedium) },
            trailingContent = { Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(
                onClickLabel = stringResource(if (expanded) R.string.article_toggle_collapse else R.string.article_toggle_expand),
                role = Role.Button,
            ) { expanded = !expanded },
        )
        AnimatedVisibility(
            expanded,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.defaultEffectsSpec()),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
                block.content.forEach { Block(it, inCard = true) }
            }
        }
    }
}

@Composable
private fun VideoBlock(block: ArticleBlock.Video) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MediaPreview(
            block.posterUrl,
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large),
            onClick = { uriHandler.openUri(block.url) },
        )
        block.caption?.takeIf { it.isNotEmpty() }?.let { Caption(it, Modifier.padding(horizontal = 8.dp)) }
    }
}

/** A 16:9 poster with a play button over it, for media the app hands to the browser or a player app. */
@Composable
private fun MediaPreview(posterUrl: String?, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (!posterUrl.isNullOrBlank()) {
            AsyncImage(model = posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Surface(Modifier.size(64.dp), shape = CircleShape, color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    AppIcons.PlayArrowFilled,
                    contentDescription = stringResource(R.string.article_video_play),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

@Composable
private fun EmbedBlock(block: ArticleBlock.Embed) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedCard(onClick = { uriHandler.openUri(block.url) }, modifier = Modifier.fillMaxWidth()) {
            // Only video providers come with a thumbnail, and for those the card plays.
            if (!block.thumbnailUrl.isNullOrBlank()) {
                MediaPreview(block.thumbnailUrl, Modifier.fillMaxWidth(), onClick = { uriHandler.openUri(block.url) })
            }
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(AppIcons.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(
                        block.provider?.takeIf { it.isNotBlank() } ?: stringResource(R.string.article_embed_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        block.url.substringAfter("://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        block.caption?.takeIf { it.isNotEmpty() }?.let { Caption(it, Modifier.padding(horizontal = 8.dp)) }
    }
}

@Composable
private fun FileBlock(block: ArticleBlock.File) {
    val uriHandler = LocalUriHandler.current
    val caption = block.caption?.takeIf { it.isNotBlank() }
    val size = block.size?.takeIf { it.isNotBlank() }
    OutlinedCard(onClick = { uriHandler.openUri(block.url) }, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        ListItem(
            headlineContent = { Text(block.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = if (caption != null) {
                { Text(caption, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            } else {
                null
            },
            leadingContent = { Icon(AppIcons.Draft, contentDescription = null) },
            trailingContent = if (size != null) {
                { Text(size, style = MaterialTheme.typography.labelMedium) }
            } else {
                null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun TableBlock(block: ArticleBlock.Table) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val headerBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val base = MaterialTheme.typography.bodyMedium
    val body = base
    val header = remember(base) { base.copy(fontWeight = FontWeight.Bold) }
    // Cells keep a fixed width so the columns line up whatever the text, and the table scrolls sideways.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .horizontalScroll(rememberScrollState())
            .border(1.dp, line),
    ) {
        block.rows.forEachIndexed { index, row ->
            val isHeader = index < block.headerRows
            Row(Modifier.height(IntrinsicSize.Min).then(if (isHeader) Modifier.background(headerBackground) else Modifier)) {
                row.forEach { cell ->
                    HtmlText(
                        cell,
                        Modifier
                            .width(160.dp)
                            .fillMaxHeight()
                            .drawBehind {
                                val stroke = 1.dp.toPx()
                                drawLine(line, Offset(size.width, 0f), Offset(size.width, size.height), stroke)
                                drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), stroke)
                            }
                            .padding(12.dp),
                        if (isHeader) header else body,
                    )
                }
            }
        }
    }
}
