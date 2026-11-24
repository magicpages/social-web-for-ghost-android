package co.magicpages.socialweb.ui.posts

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.paging.compose.LazyPagingItems
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.EmojiText
import co.magicpages.socialweb.ui.components.emojiSourceId
import co.magicpages.socialweb.ui.theme.Gutter
import coil3.compose.AsyncImage

/** The Reader's articles laid out, and how tall its prominent cards are. */
@Immutable
class ReaderPage(val magazine: ReaderMagazine, val prominentHeight: Dp)

/**
 * The Reader's layout for a list [width] by [height]. A prominent card is 4:3, but never taller than
 * 60% of the list, so a landscape phone still shows more than one card. From a font scale of 1.5,
 * no pairs: half the width can't fit a title that large.
 */
@Composable
internal fun rememberReaderPage(posts: LazyPagingItems<FeedItem>, width: Dp, height: Dp): ReaderPage {
    val pairs = LocalDensity.current.fontScale < LargeFontScale
    val magazine by remember(posts, pairs) { derivedStateOf { ReaderLayout.arrange(posts.itemSnapshotList.items, pairs) } }
    return remember(magazine, width, height) {
        val cardWidth = min(width, MaxContentWidth) - Gutter * 2
        ReaderPage(magazine, min(cardWidth * 3 / 4, height * 0.6f))
    }
}

/** Font scale from which the Reader has no pairs of tall cards. */
private const val LargeFontScale = 1.5f

/** What one item of the Reader draws: [block]'s card, or its two. */
@Composable
internal fun ReaderBlockView(block: ReaderBlock, callbacks: PostCallbacks, page: ReaderPage) {
    when (block) {
        is ReaderBlock.Single -> when (block.style) {
            ReaderStyle.Prominent -> ImageCard(
                block.item,
                callbacks,
                // At least this tall: grows with a title that a large font scale makes longer.
                Modifier.padding(horizontal = Gutter, vertical = 8.dp).fillMaxWidth().heightIn(min = page.prominentHeight),
                MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                padding = 16.dp,
                avatarSize = 20.dp,
            )
            ReaderStyle.Compact -> CompactCard(block.item, callbacks, Modifier.padding(horizontal = Gutter - CompactInset))
        }
        is ReaderBlock.TallPair -> Row(
            Modifier.padding(horizontal = Gutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TallCard(block.left, callbacks, Modifier.weight(1f))
            TallCard(block.right, callbacks, Modifier.weight(1f))
        }
    }
}

/** A portrait image card, one of two side by side: a smaller title, and no date (too narrow for both). */
@Composable
private fun TallCard(item: FeedItem, callbacks: PostCallbacks, modifier: Modifier) = ImageCard(
    item,
    callbacks,
    modifier.aspectRatio(3f / 4f),
    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    padding = 12.dp,
    avatarSize = 16.dp,
    showsDate = false,
)

/**
 * The cover filling the card, and over its foot who reposted the article, the publication and the
 * title, on a scrim of their own. [modifier] gives the card its size.
 */
@Composable
private fun ImageCard(
    item: FeedItem,
    callbacks: PostCallbacks,
    modifier: Modifier,
    titleStyle: TextStyle,
    padding: Dp,
    avatarSize: Dp,
    showsDate: Boolean = true,
) {
    ReaderCardFrame(item, callbacks, MaterialTheme.shapes.large, modifier) {
        Cover(item.post, Modifier.matchParentSize())
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .drawBehind { drawRect(textScrim(ScrimFade.toPx() / size.height)) }
                .padding(start = padding, top = ScrimFade, end = padding, bottom = padding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (item.reposters.isNotEmpty()) RepostContext(item.reposters, callbacks.openProfile, color = Color.White.copy(alpha = 0.85f))
            Publication(item, avatarSize, onImage = true, showsDate = showsDate)
            Text(
                item.post.title,
                style = titleStyle.copy(shadow = TitleShadow),
                color = Color.White,
                maxLines = 4,
                // Full width, so a one-line title aligns by its own language like a longer one does.
                modifier = Modifier.fillMaxWidth(),
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The publication and the title, the cover small and square beside them; without a cover, the
 * publication's icon in a circle, like a columnist's photo.
 */
@Composable
private fun CompactCard(item: FeedItem, callbacks: PostCallbacks, modifier: Modifier) {
    val post = item.post
    val hasCover = !post.featureImageUrl.isNullOrBlank()
    val iconAsImage = !hasCover && !post.author.avatarUrl.isNullOrBlank()
    ReaderCardFrame(item, callbacks, MaterialTheme.shapes.medium, modifier.fillMaxWidth()) {
        // Centered, so a short title doesn't leave a gap under it while a long one runs into the next card.
        Row(
            Modifier.padding(CompactInset),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Publication(item, 18.dp, onImage = false, showsAvatar = !iconAsImage)
                Text(
                    post.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (item.reposters.isNotEmpty()) RepostContext(item.reposters, callbacks.openProfile)
            }
            when {
                hasCover -> Cover(post, Modifier.size(CompactCoverSize).clip(MaterialTheme.shapes.medium))
                iconAsImage -> Avatar(
                    post.author.avatarUrl,
                    post.author.displayName,
                    CompactCoverSize,
                    Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
            }
        }
    }
}

/**
 * A card that opens the article, clipped to [shape]. A long press opens what the timeline's action
 * buttons and more menu offer elsewhere, as the Reader's cards have neither.
 */
@Composable
private fun ReaderCardFrame(
    item: FeedItem,
    callbacks: PostCallbacks,
    shape: Shape,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .clip(shape)
            .combinedClickable(
                onLongClickLabel = stringResource(R.string.action_more),
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
                onClick = { callbacks.open(item.post) },
            ),
        content = content,
    )
    ReaderCardMenu(item, callbacks, menuOpen, onClose = { menuOpen = false })
}

/**
 * The publication's icon and name, and how long ago the article came out. Where both don't fit,
 * the date is left out rather than cut short: the name says more about the article.
 */
@Composable
private fun Publication(item: FeedItem, avatarSize: Dp, onImage: Boolean, showsDate: Boolean = true, showsAvatar: Boolean = true) {
    val author = item.post.author
    val color = if (onImage) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelMedium
    val date = item.publishedAtMillis?.takeIf { showsDate }?.let { relativeTime(it) }
    Layout(
        content = {
            if (showsAvatar) {
                Avatar(
                    author.avatarUrl,
                    author.displayName,
                    avatarSize,
                    Modifier.layoutId(AvatarId).then(if (onImage) Modifier.border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape) else Modifier),
                )
            }
            EmojiText(
                author.displayName,
                emojiSourceId = author.emojiSourceId,
                style = style.copy(fontWeight = FontWeight.SemiBold),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.layoutId(NameId),
            )
            if (date != null) {
                Text(
                    "· $date",
                    style = style,
                    color = color,
                    maxLines = 1,
                    // Read out without the dot.
                    modifier = Modifier.layoutId(DateId).clearAndSetSemantics { contentDescription = date },
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val avatarGap = 6.dp.roundToPx()
        // About a space: the dot then sits evenly between the name and the date.
        val dateGap = 4.dp.roundToPx()
        val avatar = measurables.firstOrNull { it.layoutId == AvatarId }?.measure(loose)
        val textWidth = (constraints.maxWidth - (avatar?.let { it.width + avatarGap } ?: 0)).coerceAtLeast(0)
        val name = measurables.first { it.layoutId == NameId }.measure(loose.copy(maxWidth = textWidth))
        val dateMeasured = measurables.firstOrNull { it.layoutId == DateId }?.measure(Constraints())
        val dateShown = dateMeasured?.takeIf { name.width + dateGap + it.width <= textWidth }
        val width = (avatar?.let { it.width + avatarGap } ?: 0) + name.width + (dateShown?.let { dateGap + it.width } ?: 0)
        val height = maxOf(avatar?.height ?: 0, name.height, dateShown?.height ?: 0)
        layout(width, height) {
            var x = 0
            avatar?.let {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + avatarGap
            }
            name.placeRelative(x, (height - name.height) / 2)
            dateShown?.placeRelative(x + name.width + dateGap, (height - dateShown.height) / 2)
        }
    }
}

private const val AvatarId = "avatar"
private const val NameId = "name"
private const val DateId = "date"

/**
 * The article's cover, filling its frame. Under a content warning, or marked sensitive while the
 * account doesn't show such media, it's blurred beyond recognition (before API 31, which can't
 * blur, left out); the title stays, as the article's own screen asks before showing it.
 */
@Composable
private fun Cover(post: Post, modifier: Modifier) {
    val hidden = !post.contentWarning.isNullOrBlank() || (post.sensitive && !LocalShowSensitiveMedia.current)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (hidden && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@Box
        AsyncImage(
            model = post.featureImageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(if (hidden) Modifier.blur(32.dp) else Modifier),
        )
    }
}

/**
 * Black under the text on a cover, faded in over [fadeFraction] of its height: at least 55% black
 * where the text starts, so white text has 4.5:1 contrast even on a white cover.
 */
private fun textScrim(fadeFraction: Float) = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0f),
    fadeFraction to Color.Black.copy(alpha = 0.55f),
    1f to Color.Black.copy(alpha = 0.8f),
)

/** How far above the text its scrim fades in. */
private val ScrimFade = 48.dp

private val TitleShadow = Shadow(Color.Black.copy(alpha = 0.3f), Offset(0f, 2f), blurRadius = 12f)

/** A compact card's square cover. */
private val CompactCoverSize = 88.dp

/** A compact card's own padding, inside its press highlight. */
private val CompactInset = 8.dp
