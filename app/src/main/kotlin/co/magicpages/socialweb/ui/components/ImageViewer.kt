package co.magicpages.socialweb.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import co.magicpages.socialweb.R
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size as CoilSize

@Immutable
data class ViewerImage(val url: String, val description: String? = null)

/** Opens [images] full screen, starting at [start]. */
fun interface ImageViewerLauncher {
    fun show(images: List<ViewerImage>, start: Int)
}

/** Provided by [ImageViewerHost]; a no-op outside it (previews). */
val LocalImageViewer = staticCompositionLocalOf { ImageViewerLauncher { _, _ -> } }

/** Lets anything inside [content] open the full-screen viewer through [LocalImageViewer]. */
@Composable
fun ImageViewerHost(content: @Composable () -> Unit) {
    var shown by rememberSaveable(stateSaver = ShownSaver) { mutableStateOf(null) }
    var start by rememberSaveable { mutableIntStateOf(0) }
    val launcher = remember {
        ImageViewerLauncher { images, index ->
            if (images.isEmpty()) return@ImageViewerLauncher
            start = index.coerceIn(images.indices)
            shown = images
        }
    }
    // Before [content], like the note editor host: a sibling after screens that an account switch
    // tears down can pick up one of their (soon destroyed) composition locals.
    shown?.let { ImageViewer(it, start, onDismiss = { shown = null }) }
    CompositionLocalProvider(LocalImageViewer provides launcher, content = content)
}

// Flattened url/description pairs, so the open viewer survives rotation and process death.
private val ShownSaver = Saver<List<ViewerImage>?, ArrayList<String>>(
    save = { images -> images?.flatMapTo(ArrayList()) { listOf(it.url, it.description.orEmpty()) } },
    restore = { flat -> flat.chunked(2).map { (url, description) -> ViewerImage(url, description.ifBlank { null }) } },
)

/**
 * Material 3 has no image viewer, so this is a stock full-screen [Dialog] around a [HorizontalPager]:
 * pinch or double-tap to zoom, drag to pan while zoomed, swipe between images, tap to hide the
 * controls, back or the close button to leave.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImageViewer(images: List<ViewerImage>, start: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Light bar icons over the black backdrop, whatever the app theme.
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
            onDispose {}
        }
        val pager = rememberPagerState(initialPage = start) { images.size }
        var chrome by rememberSaveable { mutableStateOf(true) }
        var zoomed by remember { mutableStateOf(false) }
        val appear = remember { MutableTransitionState(false).apply { targetState = true } }
        val motion = MaterialTheme.motionScheme

        AnimatedVisibility(
            visibleState = appear,
            enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.92f),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = !zoomed,
                    beyondViewportPageCount = 1,
                    key = { images[it].url + "#" + it },
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    ZoomableImage(
                        image = images[page],
                        isCurrent = page == pager.currentPage,
                        onZoomedChange = { zoomed = it },
                        onTap = { chrome = !chrome },
                    )
                }
                AnimatedVisibility(chrome, enter = fadeIn(motion.fastEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
                    Controls(images, pager.currentPage, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun Controls(images: List<ViewerImage>, page: Int, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onClose,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White),
            ) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.image_close))
            }
            if (images.size > 1) {
                Text(
                    stringResource(R.string.image_counter, page + 1, images.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        images[page].description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Start,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
                    .padding(12.dp),
            )
        }
    }
}

private const val MaxZoom = 5f
private const val DoubleTapZoom = 2.5f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ZoomableImage(image: ViewerImage, isCurrent: Boolean, onZoomedChange: (Boolean) -> Unit, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var state by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

    // Pans stop where the zoomed image's edge meets the screen's.
    fun clamp(value: Offset, scale: Float): Offset {
        val maxX = size.width * (scale - 1) / 2
        val maxY = size.height * (scale - 1) / 2
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    LaunchedEffect(isCurrent, scale > 1f) { if (isCurrent) onZoomedChange(scale > 1f) }
    // Leaving a page resets its zoom, so coming back shows the whole image again.
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // Zoom towards the tapped point.
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = DoubleTapZoom
                            offset = clamp((center - tap) * (DoubleTapZoom - 1), DoubleTapZoom)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // Only claims the gesture for pinches, or drags while zoomed; an unzoomed one-finger
                // swipe is left to the pager.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        // The release event has no pressed pointer, so no centroid to scale around.
                        if (pressed > 0 && (pressed > 1 || scale > 1f)) {
                            val zoom = event.calculateZoom()
                            val newScale = (scale * zoom).coerceIn(1f, MaxZoom)
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val factor = newScale / scale
                            // Keep the point under the fingers in place while scaling.
                            val shifted = (offset + (center - centroid)) * factor - (center - centroid) + event.calculatePan()
                            scale = newScale
                            offset = if (newScale == 1f) Offset.Zero else clamp(shifted, newScale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Decoded at twice the screen size (never above the original) so zooming in stays sharp.
        val context = LocalPlatformContext.current
        val request = remember(image.url, size) {
            ImageRequest.Builder(context)
                .data(image.url)
                .size(CoilSize(size.width.coerceAtLeast(1) * 2, size.height.coerceAtLeast(1) * 2))
                .precision(Precision.INEXACT)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = image.description,
            contentScale = ContentScale.Fit,
            onState = { state = it },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
        when (state) {
            is AsyncImagePainter.State.Loading -> LoadingIndicator()
            is AsyncImagePainter.State.Error -> Text(
                stringResource(R.string.image_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            else -> Unit
        }
    }
}
