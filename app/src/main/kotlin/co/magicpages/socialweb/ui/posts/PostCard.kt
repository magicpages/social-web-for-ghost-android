package co.magicpages.socialweb.ui.posts

import android.os.Build
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Attachment
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.EmojiText
import co.magicpages.socialweb.ui.components.emojiSourceId
import co.magicpages.socialweb.ui.components.HtmlText
import co.magicpages.socialweb.ui.components.ActionSheet
import co.magicpages.socialweb.ui.components.LocalImageViewer
import co.magicpages.socialweb.ui.components.LinkPreviewCard
import co.magicpages.socialweb.ui.components.SheetAction
import co.magicpages.socialweb.ui.components.ViewerImage
import co.magicpages.socialweb.ui.media.InlineVideo
import co.magicpages.socialweb.ui.media.VideoSource
import co.magicpages.socialweb.ui.media.videos
import co.magicpages.socialweb.ui.media.videoFrame
import co.magicpages.socialweb.ui.quote.LocalQuotePost
import co.magicpages.socialweb.ui.translation.TranslatableText
import co.magicpages.socialweb.ui.theme.Gutter
import co.magicpages.socialweb.ui.theme.LocalLikeColor
import co.magicpages.socialweb.ui.theme.reading
import coil3.compose.AsyncImage

/** What a post can do, wired once per screen. */
@Stable
class PostCallbacks(
    val open: (Post) -> Unit,
    val openProfile: (Author) -> Unit,
    val reply: (FeedItem) -> Unit,
    val toggleLike: (Post) -> Unit,
    val toggleRepost: (Post) -> Unit,
)

/** Avatar size in a post row; the thread line runs through its middle. */
private val RowAvatarSize = 40.dp

/** Space between a row's avatar and its text. */
private val AvatarGap = 12.dp

/** Where a post row's text column starts, measured from the screen edge. */
internal val PostTextInset = Gutter + RowAvatarSize + AvatarGap

/** A context line's icon and its gap, so its text can start on the name's line. */
private val ContextIconWidth = 16.dp + 6.dp

/**
 * The narrowest an action gets: one touch target. Buttons otherwise default to 58 dp, which
 * centres a bare icon (no count yet) further right than an icon with a count.
 */
internal val ActionMinWidth = 48.dp

/** Top padding of a post row, which a thread line bridges to the row above. */
private val RowTopPadding = 12.dp

/**
 * A post in a flat list (Notes, a profile, a conversation): avatar on the left, everything else in
 * the column beside it. In a conversation, [connectAbove] and [connectBelow] draw the thread line
 * through the avatar column; [showContext] adds who reposted it or what it replies to.
 */
@Composable
fun PostRow(
    item: FeedItem,
    callbacks: PostCallbacks,
    modifier: Modifier = Modifier,
    connectAbove: Boolean = false,
    connectBelow: Boolean = false,
    showContext: Boolean = true,
) {
    val post = item.post
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier
            .clickable(enabled = !post.isDeleted) { callbacks.open(post) }
            .padding(start = Gutter, end = Gutter, top = RowTopPadding, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showContext) {
            // The context's text starts where the name does, its icon in the avatar column.
            val contextModifier = Modifier.padding(start = RowAvatarSize + AvatarGap - ContextIconWidth)
            when {
                item.reposters.isNotEmpty() -> Box(contextModifier) { RepostContext(item.reposters, callbacks.openProfile) }
                item.replyingTo != null -> Box(contextModifier) {
                    PostContext(AppIcons.Reply, R.string.post_replying_to, item.replyingTo, callbacks)
                }
            }
        }
        Row(
            Modifier.drawBehind {
                // Drawn from this row's own top, so a context line above doesn't shift the avatar's spot.
                val x = (RowAvatarSize / 2).toPx()
                val width = 2.dp.toPx()
                if (connectAbove) drawLine(lineColor, Offset(x, -RowTopPadding.toPx()), Offset(x, -4.dp.toPx()), width)
                if (connectBelow) drawLine(lineColor, Offset(x, (RowAvatarSize + 4.dp).toPx()), Offset(x, size.height + 4.dp.toPx()), width)
            },
        ) {
            Avatar(
                post.author.avatarUrl,
                post.author.displayName,
                RowAvatarSize,
                Modifier
                    .clickable(onClickLabel = stringResource(R.string.open_profile), role = Role.Button) {
                        callbacks.openProfile(post.author)
                    }
                    .semantics { contentDescription = post.author.displayName },
            )
            Spacer(Modifier.width(AvatarGap))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthorLines(post.author, item.publishedAtMillis, callbacks)
                PostContent(item, callbacks)
            }
        }
    }
}

/**
 * An article in the Reader: one card with who shared it, the feature image across the card's
 * width, then the title, excerpt and actions.
 */
@Composable
fun ArticleCard(item: FeedItem, callbacks: PostCallbacks, modifier: Modifier = Modifier) {
    val post = item.post
    Surface(
        onClick = { callbacks.open(post) },
        enabled = !post.isDeleted,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    item.reposters.isNotEmpty() -> RepostContext(item.reposters, callbacks.openProfile)
                    item.replyingTo != null -> PostContext(AppIcons.Reply, R.string.post_replying_to, item.replyingTo, callbacks)
                }
                AuthorHeader(post.author, item.publishedAtMillis, callbacks, avatarSize = RowAvatarSize)
            }
            when {
                post.isDeleted -> Box(Modifier.padding(horizontal = 16.dp)) { DeletedNotice() }
                // A warning has text and a button, which need the card's padding; the article's image doesn't.
                post.isWarned -> Box(Modifier.padding(horizontal = 16.dp)) {
                    SensitiveGate(post) { ArticleBody(post, contained = false) { callbacks.open(post) } }
                }
                else -> ArticleBody(post, contained = false) { callbacks.open(post) }
            }
            // The buttons' own padding lines their icons up with the text above.
            Box(Modifier.padding(start = 16.dp, bottom = 4.dp)) {
                ActionBar(item, callbacks, large = false)
            }
        }
    }
}

/** The post a thread is about: reading-size text, the full date and roomier actions. */
@Composable
fun FocusedPost(item: FeedItem, callbacks: PostCallbacks, connectAbove: Boolean, modifier: Modifier = Modifier) {
    val post = item.post
    val context = LocalContext.current
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier
            .drawBehind {
                if (connectAbove) {
                    // Same x as the rows above, so the line runs straight into this avatar.
                    val x = (Gutter + RowAvatarSize / 2).toPx()
                    drawLine(lineColor, Offset(x, 0f), Offset(x, 12.dp.toPx()), 2.dp.toPx())
                }
            }
            .padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AuthorHeader(post.author, publishedAtMillis = null, callbacks, avatarSize = 48.dp)
        if (post.isDeleted) {
            DeletedNotice()
            return@Column
        }
        SensitiveGate(post) {
            if (post.isArticle) {
                // Shown here only when the reader can't lay the article out, so open it at its origin.
                val uriHandler = LocalUriHandler.current
                ArticleBody(post, contained = true) { if (post.url.isNotBlank()) uriHandler.openUri(post.url) }
            } else {
                if (item.content.isNotEmpty()) TranslatableText(item, style = MaterialTheme.typography.reading)
                PostMedia(post)
                item.quoteUrl?.let { QuotedPost(it, callbacks) }
                item.previewUrl?.let { LinkPreviewCard(it) }
            }
        }
        item.publishedAtMillis?.let {
            Text(
                DateUtils.formatDateTime(
                    context,
                    it,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ActionBar(item, callbacks, large = true)
    }
}

@Composable
private fun PostContent(item: FeedItem, callbacks: PostCallbacks) {
    val post = item.post
    if (post.isDeleted) {
        DeletedNotice()
        Spacer(Modifier.height(6.dp))
        return
    }
    SensitiveGate(post) {
        if (post.isArticle) {
            ArticleBody(post, contained = true) { callbacks.open(post) }
        } else {
            if (item.content.isNotEmpty()) TranslatableText(item)
            PostMedia(post)
            item.quoteUrl?.let { QuotedPost(it, callbacks) }
            item.previewUrl?.let { LinkPreviewCard(it) }
        }
    }
    ActionBar(item, callbacks, large = false)
}

@Composable
private fun DeletedNotice() {
    Text(
        stringResource(R.string.post_deleted),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A line above the author saying how the post got here, e.g. who reposted it; opens that profile. */
@Composable
private fun PostContext(icon: ImageVector, label: Int, author: Author, callbacks: PostCallbacks) {
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button) { callbacks.openProfile(author) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        EmojiText(
            stringResource(label, author.displayName),
            emojiSourceId = author.emojiSourceId,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Avatar beside the author's name, handle and time; the whole row opens the profile. */
@Composable
private fun AuthorHeader(author: Author, publishedAtMillis: Long?, callbacks: PostCallbacks, avatarSize: Dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = stringResource(R.string.open_profile), role = Role.Button) { callbacks.openProfile(author) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(author.avatarUrl, author.displayName, avatarSize)
        Spacer(Modifier.width(AvatarGap))
        AuthorLines(author, publishedAtMillis, callbacks = null, Modifier.weight(1f))
    }
}

/**
 * Every post's identity block: the name over the handle, the time at the end of the name's line.
 * With [callbacks] it opens the author's profile on its own; without, its parent handles taps.
 */
@Composable
private fun AuthorLines(author: Author, publishedAtMillis: Long?, callbacks: PostCallbacks?, modifier: Modifier = Modifier) {
    val click = callbacks?.let { cb ->
        Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.open_profile), role = Role.Button) { cb.openProfile(author) }
    } ?: Modifier
    Column(modifier.then(click)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiText(
                author.displayName,
                emojiSourceId = author.emojiSourceId,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            publishedAtMillis?.let {
                Text(
                    relativeTime(it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            author.handle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A compact name line for embedded posts: name, handle and time on one line. */
@Composable
private fun NameLine(author: Author, publishedAtMillis: Long?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EmojiText(
            author.displayName,
            emojiSourceId = author.emojiSourceId,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // When both don't fit (narrow screens, large fonts) the name keeps twice the handle's room.
            modifier = Modifier.weight(2f, fill = false),
        )
        Text(
            author.handle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        publishedAtMillis?.let {
            Text(
                "· " + relativeTime(it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Content-warned or sensitive: an article hides behind a tap; so does anything under a content warning. */
private val Post.isWarned: Boolean get() = sensitive || !contentWarning.isNullOrBlank()

/**
 * Hides content-warned posts (and sensitive articles) behind a tap, as Ghost Admin does. A post that
 * is only marked sensitive keeps its words in view and veils just its media; either way, a
 * [SensitiveMedia] tells [PostMedia] whether the reader has asked to see it, and that choice lasts
 * the session.
 */
@Composable
private fun SensitiveGate(post: Post, content: @Composable () -> Unit) {
    val warning = post.contentWarning?.takeIf { it.isNotBlank() }
    if (warning == null && !post.sensitive) return content()
    var revealed by rememberSaveable(post.id) { mutableStateOf(false) }
    val media = remember(revealed, warning) { SensitiveMedia(revealed, canHide = warning == null) { revealed = !revealed } }
    CompositionLocalProvider(LocalSensitiveMedia provides media) {
        if (warning == null && !post.isArticle) {
            content()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    warning ?: stringResource(R.string.post_sensitive),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                FilledTonalButton(onClick = { revealed = !revealed }) {
                    Text(stringResource(if (revealed) R.string.post_hide else R.string.post_show))
                }
                if (revealed) content()
            }
        }
    }
}

/** Whether a sensitive post's media is shown; [toggle] flips it, and [canHide] says whether the media offers its own Hide. */
@Immutable
private class SensitiveMedia(val revealed: Boolean, val canHide: Boolean, val toggle: () -> Unit)

/** The veil state of the post being drawn, from [SensitiveGate]; null outside one. */
private val LocalSensitiveMedia = compositionLocalOf<SensitiveMedia?> { null }

/**
 * The post a quote post quotes, as an embedded card that opens it. Loads on first show; until
 * then, and when it can't be loaded, the card is the link the quoting server sent.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuotedPost(url: String, callbacks: PostCallbacks) {
    val quotes = LocalLinkedPosts.current ?: return QuoteLink(url)
    val state by produceState(quotes.cached(url), url) {
        if (value == null) {
            value = try {
                quotes.load(url)
            } catch (_: GhostException) {
                LinkedPosts.Result.Missing
            } catch (_: SocialWebException) {
                LinkedPosts.Result.Missing
            }
        }
    }
    when (val result = state) {
        null -> OutlinedCard(Modifier.fillMaxWidth()) {
            val loading = stringResource(R.string.quote_loading)
            Box(Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = loading }, contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.size(40.dp))
            }
        }
        LinkedPosts.Result.Missing -> QuoteLink(url)
        is LinkedPosts.Result.Found -> QuoteCard(result.item, callbacks)
    }
}

@Composable
private fun QuoteCard(item: FeedItem, callbacks: PostCallbacks) {
    val post = item.post
    OutlinedCard(
        onClick = { callbacks.open(post) },
        enabled = !post.isDeleted,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(post.author.avatarUrl, post.author.displayName, 24.dp)
                Spacer(Modifier.width(8.dp))
                NameLine(post.author, item.publishedAtMillis, Modifier.weight(1f))
            }
            when {
                post.isDeleted -> DeletedNotice()
                else -> SensitiveGate(post) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (post.isArticle) {
                            Text(post.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (post.excerpt.isNotBlank()) {
                                Text(post.excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            if (item.content.isNotEmpty()) {
                                HtmlText(item.content, style = MaterialTheme.typography.bodyMedium, maxLines = 8, emojiSourceId = post.id)
                            }
                            PostMedia(post)
                        }
                        // A quote inside a quote stays a link rather than nesting cards.
                        item.quoteUrl?.let { QuoteLink(it) }
                    }
                }
            }
        }
    }
}

/** A quoted post that isn't (or can't be) shown in place: opens in the browser. */
@Composable
private fun QuoteLink(url: String) = ExternalPostLink(stringResource(R.string.quote_label), url)

/** A post the app can't show, such as a video a reply answers: [label], its [title] if known, and where it opens. */
@Composable
fun ExternalPostLink(label: String, url: String, modifier: Modifier = Modifier, title: String? = null) {
    val uriHandler = LocalUriHandler.current
    OutlinedCard(onClick = { uriHandler.openUri(url) }, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(AppIcons.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                title?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(
                    url.substringAfter("://"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * An article's feature image, title, excerpt and reading time. [contained] wraps it in an outlined
 * card that opens it, as an embed in a post row; otherwise it fills a card that already opens it.
 */
@Composable
private fun ArticleBody(post: Post, contained: Boolean, onClick: () -> Unit) {
    if (contained) {
        OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            ArticleBodyContent(post, MaterialTheme.typography.titleMedium, bottomPadding = 12.dp)
        }
    } else {
        // The card's own spacing follows, so no padding below.
        Column(Modifier.fillMaxWidth()) { ArticleBodyContent(post, MaterialTheme.typography.titleLarge, bottomPadding = 0.dp) }
    }
}

@Composable
private fun ArticleBodyContent(post: Post, titleStyle: TextStyle, bottomPadding: Dp) {
    post.featureImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
        // The image opens full screen; the rest of the card opens the article.
        val viewer = LocalImageViewer.current
        AsyncImage(
            model = url,
            contentDescription = stringResource(R.string.image_cover),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clickable(onClickLabel = stringResource(R.string.image_view), role = Role.Image) {
                    viewer.show(listOf(ViewerImage(url)), 0)
                },
        )
    }
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(post.title, style = titleStyle, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val excerpt = post.summary?.takeIf { it.isNotBlank() } ?: post.excerpt
        if (excerpt.isNotBlank()) {
            Text(
                excerpt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (post.readingTimeMinutes > 0) {
            Text(
                pluralStringResource(R.plurals.post_reading_time, post.readingTimeMinutes, post.readingTimeMinutes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A post's images, then its videos; the media of a sensitive post sits behind a [SensitiveVeil]. */
@Composable
private fun PostMedia(post: Post) {
    val media = LocalSensitiveMedia.current?.takeIf { post.sensitive }
    if (post.images.isNotEmpty()) {
        SensitiveVeil(media, ratio = ImagesPlaceholderRatio) { Images(post.images) }
    }
    val videos = remember(post.attachments) { post.videos }
    if (videos.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        videos.forEach { video ->
            key(video.playbackUrl) {
                val ratio = video.aspectRatio ?: DefaultVideoRatio
                // A hidden video is not composed at all, so it can't report itself to the player and start on its own.
                SensitiveVeil(media, ratio, preview = { VideoPosterPreview(video, ratio) }) { InlineVideo(video) }
            }
        }
    }
}

/**
 * Shows [content] as is when [media] is null or revealed (with a small Hide on top where the reader
 * may take it back). Otherwise it fails closed: from API 31 the [preview] (default: [content]) is
 * blurred beyond recognition with a Show button over it; older versions can't blur, so a plain
 * surface of the media's [ratio] stands in. The blurred copy is out of reach of taps and screen
 * readers, so neither can get at what it hides.
 */
@Composable
private fun SensitiveVeil(
    media: SensitiveMedia?,
    ratio: Float,
    preview: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (media == null) return content()
    val shape = MaterialTheme.shapes.large
    when {
        media.revealed -> Box(Modifier.fillMaxWidth()) {
            content()
            if (media.canHide) {
                FilledTonalIconButton(
                    onClick = media.toggle,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Icon(AppIcons.VisibilityOff, contentDescription = stringResource(R.string.post_hide))
                }
            }
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Box(
            Modifier.fillMaxWidth().clip(shape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.blur(MediaBlurRadius, BlurredEdgeTreatment(shape)).clearAndSetSemantics {}) {
                // A video brings its poster in place of itself, so the player stays out while hidden.
                if (preview != null) preview() else content()
            }
            ShowMediaScrim(media, Modifier.matchParentSize())
            ShowMediaButton(media)
        }
        else -> Box(
            Modifier.videoFrame(ratio).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { ShowMediaButton(media) }
    }
}

/** Takes the taps meant for the blurred media, so none reach it, and reveals it instead. */
@Composable
private fun ShowMediaScrim(media: SensitiveMedia, modifier: Modifier) {
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.2f))
            .clearAndSetSemantics {}
            .pointerInput(media) { detectTapGestures { media.toggle() } },
    )
}

@Composable
private fun ShowMediaButton(media: SensitiveMedia) {
    val label = stringResource(R.string.post_sensitive_media_show)
    FilledTonalButton(
        onClick = media.toggle,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Icon(AppIcons.Visibility, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.post_show))
    }
}

/** A video's poster in the video's own shape, for the blur to work on while the player stays out. */
@Composable
private fun VideoPosterPreview(video: VideoSource, ratio: Float) {
    Box(
        Modifier
            .videoFrame(ratio)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        video.posterUrl?.let { AsyncImage(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
}

/** How strongly hidden media is blurred: enough that no detail is left to make out. */
private val MediaBlurRadius = 32.dp

/** The shape a hidden image grid takes before API 31, which can't blur it. */
private const val ImagesPlaceholderRatio = 4f / 3f

/** A video's shape when its attachment doesn't say (the inline player assumes the same). */
private const val DefaultVideoRatio = 16f / 9f

@Composable
private fun Images(images: List<Attachment>) {
    if (images.isEmpty()) return
    val viewer = LocalImageViewer.current
    // The viewer pages through every image, including any past the four in the grid.
    val all = remember(images) { images.map { ViewerImage(it.url, it.name?.takeIf(String::isNotBlank)) } }
    val open = { index: Int -> viewer.show(all, index) }
    val shown = images.take(4)
    val shape = MaterialTheme.shapes.large
    if (shown.size == 1) {
        PostImage(shown[0], Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(shape)) { open(0) }
        return
    }
    Column(Modifier.clip(shape), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.chunked(2).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { i, image -> PostImage(image, Modifier.weight(1f).aspectRatio(1f)) { open(rowIndex * 2 + i) } }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PostImage(image: Attachment, modifier: Modifier, onClick: () -> Unit) {
    AsyncImage(
        model = image.url,
        contentDescription = image.name?.takeIf { it.isNotBlank() },
        contentScale = ContentScale.Crop,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClickLabel = stringResource(R.string.image_view), role = Role.Image, onClick = onClick),
    )
}

/**
 * Reply, repost and like, then Share and more; [large] for the post a screen is about. A row too
 * narrow for Share first tightens its buttons' padding and gaps (a 360 dp phone needs that), and
 * only if it still doesn't fit (long counts) moves Share into the more menu rather than overflow.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ActionBar(item: FeedItem, callbacks: PostCallbacks, large: Boolean) {
    val height = if (large) ButtonDefaults.MinHeight else ButtonDefaults.ExtraSmallContainerHeight
    val layoutDirection = LocalLayoutDirection.current
    val padding = ButtonDefaults.contentPaddingFor(height, hasStartIcon = true)
    val compactPadding = PaddingValues(
        horizontal = CompactActionPadding,
        vertical = padding.calculateTopPadding(),
    )
    val spacing = if (large) 8.dp else 4.dp
    SubcomposeLayout { constraints ->
        // The row starts left of the text by the buttons' start padding, so the first icon lines up
        // with it; that also gives the row that much more room.
        fun measure(compact: Boolean, shareInMenu: Boolean): ActionRowMeasure {
            val rowPadding = if (compact) compactPadding else padding
            val rowSpacing = if (compact) 0.dp else spacing
            val pull = rowPadding.calculateStartPadding(layoutDirection).roundToPx()
            val loose = Constraints(maxWidth = constraints.maxWidth + pull)
            val counted = subcompose(if (compact) ActionSlot.CountedCompact else ActionSlot.Counted) {
                CountedActions(item, callbacks, height, rowSpacing, rowPadding)
            }.single().measure(loose)
            val slot = when {
                shareInMenu -> ActionSlot.WithoutShare
                compact -> ActionSlot.WithShareCompact
                else -> ActionSlot.WithShare
            }
            val trailing = subcompose(slot) { ActionTrailing(item, height, rowSpacing, shareInMenu) }.single().measure(loose)
            return ActionRowMeasure(counted, trailing, rowSpacing.roundToPx(), pull, constraints.maxWidth + pull)
        }
        val row = measure(compact = false, shareInMenu = false).takeIf { it.fits }
            ?: measure(compact = true, shareInMenu = false).takeIf { it.fits }
            ?: measure(compact = false, shareInMenu = true)
        val width = (row.counted.width + row.gap + row.trailing.width - row.pull).coerceIn(constraints.minWidth, constraints.maxWidth)
        val rowHeight = maxOf(row.counted.height, row.trailing.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, rowHeight) {
            row.counted.placeRelative(-row.pull, (rowHeight - row.counted.height) / 2)
            row.trailing.placeRelative(-row.pull + row.counted.width + row.gap, (rowHeight - row.trailing.height) / 2)
        }
    }
}

/** An [ActionBar] measured one way: its two parts, the gap between them, and how far left it starts. */
private class ActionRowMeasure(val counted: Placeable, val trailing: Placeable, val gap: Int, val pull: Int, room: Int) {
    val fits = counted.width + gap + trailing.width <= room
}

/** Horizontal padding of an [ActionBar]'s buttons when the row is tight. */
private val CompactActionPadding = 8.dp

/** Reply, repost and like, with their counts. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CountedActions(item: FeedItem, callbacks: PostCallbacks, height: Dp, spacing: Dp, padding: PaddingValues) {
    val post = item.post
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { callbacks.reply(item) },
            shapes = ButtonDefaults.shapesFor(height),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant),
            contentPadding = padding,
            modifier = Modifier.heightIn(min = height).widthIn(min = ActionMinWidth),
        ) {
            ActionContent(AppIcons.Reply, stringResource(R.string.action_reply), post.replyCount, height)
        }
        // Reposting shows on every follower's feed, so a sheet confirms it (and offers quoting); the
        // haptic waits for the sheet's Repost, as tapping the toggle only opens the sheet.
        var repostSheet by remember { mutableStateOf(false) }
        val quotePost = LocalQuotePost.current
        val haptics = LocalHapticFeedback.current
        ActionToggle(
            icon = AppIcons.Repost,
            label = stringResource(if (post.repostedByMe) R.string.action_undo_repost else R.string.action_repost),
            count = post.repostCount,
            checked = post.repostedByMe,
            height = height,
            padding = padding,
            haptic = false,
            checkedColor = colors.primary,
            onClick = { repostSheet = true },
        )
        if (repostSheet) {
            ActionSheet(onDismiss = { repostSheet = false }) {
                SheetAction(
                    stringResource(if (post.repostedByMe) R.string.action_undo_repost else R.string.action_repost),
                    AppIcons.Repost,
                ) {
                    haptics.performHapticFeedback(
                        if (post.repostedByMe) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                    )
                    callbacks.toggleRepost(post)
                }
                if (quotePost != null) {
                    SheetAction(stringResource(R.string.action_quote), AppIcons.FormatQuote) { quotePost(post) }
                }
            }
        }
        ActionToggle(
            icon = if (post.likedByMe) AppIcons.FavoriteFilled else AppIcons.Favorite,
            label = stringResource(if (post.likedByMe) R.string.action_unlike else R.string.action_like),
            count = post.likeCount,
            checked = post.likedByMe,
            height = height,
            padding = padding,
            checkedColor = LocalLikeColor.current,
            onClick = { callbacks.toggleLike(post) },
        )
    }
}

/** The row's slots: its counted actions (roomy or compact), and the end of it with or without its own Share. */
private enum class ActionSlot { Counted, CountedCompact, WithShare, WithShareCompact, WithoutShare }

/** Share and more, the end of an [ActionBar]; with [shareInMenu] Share is an entry of the menu instead. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionTrailing(item: FeedItem, height: Dp, spacing: Dp, shareInMenu: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
        if (!shareInMenu && !item.post.isDeleted) {
            val share = rememberSharePost()
            TextButton(
                onClick = { share(item) },
                shapes = ButtonDefaults.shapesFor(height),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                contentPadding = ButtonDefaults.contentPaddingFor(height),
                modifier = Modifier.heightIn(min = height).widthIn(min = ActionMinWidth),
            ) {
                Icon(
                    AppIcons.Share,
                    contentDescription = stringResource(R.string.share),
                    modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
                )
            }
        }
        PostMoreMenu(item, height, shareInMenu)
    }
}

/**
 * A stock expressive [ToggleButton] without a container: on, it takes [checkedColor] (and the
 * like heart fills), so the row stays calm and the state still reads at a glance. It buzzes on a
 * toggle unless [haptic] is off, for a button whose tap only asks for confirmation.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionToggle(
    icon: ImageVector,
    label: String,
    count: Int,
    checked: Boolean,
    height: Dp,
    padding: PaddingValues,
    checkedColor: Color,
    onClick: () -> Unit,
    haptic: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    ToggleButton(
        checked = checked,
        onCheckedChange = {
            if (haptic) {
                haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            }
            onClick()
        },
        shapes = ToggleButtonDefaults.shapesFor(height),
        colors = ToggleButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContainerColor = Color.Transparent,
            checkedContentColor = checkedColor,
        ),
        contentPadding = padding,
        modifier = Modifier.heightIn(min = height).widthIn(min = ActionMinWidth),
    ) {
        ActionContent(icon, label, count, height)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionContent(icon: ImageVector, label: String, count: Int, height: Dp) {
    Icon(icon, contentDescription = label, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(motion.fastSpatialSpec()) { if (up) it else -it } + fadeIn(motion.fastEffectsSpec())) togetherWith
                (slideOutVertically(motion.fastSpatialSpec()) { if (up) -it else it } + fadeOut(motion.fastEffectsSpec())) using
                SizeTransform(clip = false) { _, _ -> motion.fastSpatialSpec() }
        },
        label = "count",
    ) { value ->
        if (value > 0) {
            Text(
                value.toString(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = ButtonDefaults.iconSpacingFor(height)),
            )
        }
    }
}

val Author.displayName: String get() = name.ifBlank { handle }

internal fun relativeTime(millis: Long): String {
    val now = System.currentTimeMillis()
    // Remote servers' clocks drift; a post from "in 3 min." reads as a bug, so treat it as now.
    return DateUtils.getRelativeTimeSpanString(
        minOf(millis, now),
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}
