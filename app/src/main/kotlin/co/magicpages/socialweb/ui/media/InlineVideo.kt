package co.magicpages.socialweb.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.components.AppIcons
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

/**
 * A video in a post: its poster in a [videoFrame] and, when it is the
 * most visible video on screen and Autoplay videos is on, the video itself, muted and in a loop. With
 * autoplay off, or while another video is the one playing, a play button sits on the poster. A tap
 * opens the video full screen with sound.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun InlineVideo(source: VideoSource, modifier: Modifier = Modifier) {
    val playback = LocalVideoPlayback.current
    val token = remember { Any() }
    val autoplay = playback?.autoplay?.collectAsStateWithLifecycle()?.value ?: false
    val currentAutoplay by rememberUpdatedState(autoplay)
    val visible = remember { floatArrayOf(0f) }

    DisposableEffect(token, playback) { onDispose { playback?.remove(token) } }
    // Turning Autoplay videos on or off takes effect without waiting for the next scroll.
    LaunchedEffect(playback, source, autoplay) { playback?.report(token, source, if (autoplay) visible[0] else 0f) }

    var learned by remember(source) { mutableStateOf(VideoPosters.knownRatio(source.playbackUrl)) }
    val ratio = source.aspectRatio ?: learned ?: DefaultRatio

    val fileFrame by produceState(if (source.readsFrame) VideoPosters.cached(source.playbackUrl) else null, source) {
        if (source.readsFrame && value == null) value = VideoPosters.load(source.playbackUrl)
    }
    LaunchedEffect(fileFrame) { fileFrame?.let { learned = it.aspectRatio } }

    val player = if (playback != null && playback.inlineOwner === token && !playback.inlineFailed) playback.inlinePlayer else null
    val label = source.description?.let { stringResource(R.string.video_open_named, it) } ?: stringResource(R.string.video_open)
    val openLabel = stringResource(R.string.video_open)

    Box(
        modifier
            .videoFrame(ratio)
            .clip(MaterialTheme.shapes.large)
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                visible[0] = coordinates.visibleFraction()
                playback?.report(token, source, if (currentAutoplay) visible[0] else 0f)
            }
            .semantics { contentDescription = label }
            .clickable(onClickLabel = openLabel, role = Role.Button) { playback?.openViewer(source, token) },
    ) {
        val frame = fileFrame
        if (source.posterUrl != null) {
            AsyncImage(
                model = source.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { success ->
                    val image = success.result.image
                    if (image.width > 0 && image.height > 0) {
                        val shape = image.width.toFloat() / image.height
                        learned = shape
                        VideoPosters.rememberRatio(source.playbackUrl, shape)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (frame != null) {
            Image(frame.frame.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        // Transparent until the first frame is drawn, so the poster stays up while the video starts.
        if (player != null) {
            ContentFrame(
                player,
                Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                contentScale = ContentScale.Fit,
                shutter = {},
            )
        } else {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.Center).size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(AppIcons.PlayArrowFilled, contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

/** Files can show a frame read from them; playlists can't be read in part. */
private val VideoSource.readsFrame get() = posterUrl == null && !isHls

/** How much of the layout shows in the window, in steps of a twentieth so scrolling doesn't make every pixel an event. */
private fun LayoutCoordinates.visibleFraction(): Float {
    if (!isAttached) return 0f
    val area = size.width.toLong() * size.height
    if (area == 0L) return 0f
    val bounds = boundsInWindow()
    val shown = bounds.width.coerceAtLeast(0f) * bounds.height.coerceAtLeast(0f) / area
    return (shown.coerceIn(0f, 1f) * 20).roundToInt() / 20f
}

/**
 * The full width at [ratio], cut down to [MaxHeight] for a tall video, which then shows with bars at
 * its sides. Width-first aspectRatio can't do this: once a portrait video at the full width is past
 * the limit, it keeps the over-tall size, and the frame spills over the text above and the buttons below.
 * Without a bounded width (a horizontal scroller) the frame is [MaxHeight] tall at [ratio].
 */
fun Modifier.videoFrame(ratio: Float): Modifier = layout { measurable, constraints ->
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else (MaxHeight.roundToPx() * ratio).roundToInt()
    val height = (width / ratio).roundToInt()
        .coerceAtMost(MaxHeight.roundToPx())
        .coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}

private val MaxHeight = 560.dp
private const val DefaultRatio = 16f / 9f
