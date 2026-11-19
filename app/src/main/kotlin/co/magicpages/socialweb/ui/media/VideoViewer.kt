package co.magicpages.socialweb.ui.media

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.theme.Gutter
import kotlinx.coroutines.delay

/**
 * The full-screen video player, drawn over the whole app while [VideoPlayback.viewing] is set: the
 * video fitted to the screen with sound, and tap-to-toggle controls (close, picture-in-picture,
 * play/pause/replay, seek bar). In picture-in-picture only the video shows. Back closes it.
 */
@Composable
fun VideoViewerOverlay(playback: VideoPlayback) {
    val player = playback.viewerPlayer ?: return
    if (playback.viewing == null) return
    VideoViewer(playback, player)
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VideoViewer(playback: VideoPlayback, player: Player) {
    val inPip = rememberIsInPictureInPictureMode()
    val state = rememberPlayerUiState(player)
    BackHandler(enabled = !inPip) { playback.closeViewer() }
    ImmersiveSystemBars(hidden = !inPip)
    KeepScreenOn(state.isPlaying)
    PictureInPictureController(playback, state)

    var controlsShown by rememberSaveable { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsShown, state.isPlaying, interactions) {
        if (controlsShown && state.isPlaying) {
            delay(ControlsHideMillis)
            controlsShown = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controlsShown = !controlsShown },
    ) {
        ContentFrame(
            player,
            Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            contentScale = ContentScale.Fit,
        )
        if (inPip) return@Box
        val failed = state.error != null
        if (state.isBuffering && !failed) {
            LoadingIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White)
        }
        if (failed) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = Gutter, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.video_error), style = MaterialTheme.typography.bodyLarge, color = Color.White)
                TextButton(onClick = { player.prepare() }) { Text(stringResource(R.string.video_retry)) }
            }
        }
        AnimatedVisibility(
            controlsShown,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Controls(playback, player, state, showCenterButton = !failed && !(state.isBuffering), onInteract = { interactions++ })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Controls(
    playback: VideoPlayback,
    player: Player,
    state: PlayerUiState,
    showCenterButton: Boolean,
    onInteract: () -> Unit,
) {
    val activity = LocalActivity.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(Modifier.fillMaxWidth().align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = playback::closeViewer) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.video_close))
            }
            Box(Modifier.weight(1f))
            if (activity != null && PictureInPicture.isSupported(activity)) {
                IconButton(onClick = { PictureInPicture.enter(activity, player) }) {
                    Icon(AppIcons.PictureInPicture, contentDescription = stringResource(R.string.video_picture_in_picture))
                }
            }
        }
        if (showCenterButton) {
            val (icon, label) = when {
                state.isEnded -> AppIcons.Replay to R.string.video_replay
                state.isPlaying -> AppIcons.PauseFilled to R.string.video_pause
                else -> AppIcons.PlayArrowFilled to R.string.video_play
            }
            FilledIconButton(
                onClick = {
                    playback.toggleViewerPlayback()
                    onInteract()
                },
                modifier = Modifier.align(Alignment.Center).size(72.dp),
            ) {
                Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(40.dp))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SeekBar(state, onInteract)
        }
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, onInteract: () -> Unit) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs
    val fraction = scrubbing ?: if (duration > 0) state.positionMs.toFloat() / duration else 0f
    val elapsed = formatTime(if (scrubbing != null) (fraction * duration).toLong() else state.positionMs)
    val total = if (duration > 0) formatTime(duration) else null
    val label = stringResource(R.string.video_seek)
    val description = if (total != null) stringResource(R.string.video_position, elapsed, total) else elapsed
    Slider(
        value = fraction.coerceIn(0f, 1f),
        onValueChange = {
            scrubbing = it
            onInteract()
        },
        onValueChangeFinished = {
            scrubbing?.let { state.seekTo((it * duration).toLong()) }
            scrubbing = null
        },
        enabled = duration > 0,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                stateDescription = description
            },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(elapsed, style = MaterialTheme.typography.labelMedium)
        if (total != null) Text(total, style = MaterialTheme.typography.labelMedium)
    }
}

/** Hides the status and navigation bars (a swipe from the edge brings them back briefly) while [hidden]. */
@Composable
private fun ImmersiveSystemBars(hidden: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(activity, view, hidden) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun KeepScreenOn(keep: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, keep) {
        view.keepScreenOn = keep
        onDispose { view.keepScreenOn = false }
    }
}

private fun formatTime(millis: Long): String {
    val seconds = millis / 1000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds % 60) else "%d:%02d".format(minutes, seconds % 60)
}

private const val ControlsHideMillis = 3500L
