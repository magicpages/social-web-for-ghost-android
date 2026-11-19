package co.magicpages.socialweb.ui.media

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.media3.common.Player
import co.magicpages.socialweb.R

/** Picture-in-picture for the full-screen video: the window's shape and its play/pause button. */
object PictureInPicture {
    private const val ActionTogglePlayback = "co.magicpages.socialweb.action.TOGGLE_VIDEO_PLAYBACK"

    // The system accepts shapes from 1:2.39 to 2.39:1 and throws outside them.
    private const val MinRatio = 0.42f
    private const val MaxRatio = 2.38f

    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Shrinks the app's window into [player]'s video. Returns whether it did. */
    fun enter(activity: Activity, player: Player): Boolean {
        if (!isSupported(activity)) return false
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else null
        return activity.enterPictureInPictureMode(params(activity, ratio, player.isPlaying, autoEnter = false))
    }

    private fun params(activity: Activity, aspectRatio: Float?, playing: Boolean, autoEnter: Boolean): PictureInPictureParams {
        val context: Context = activity
        val toggle = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ActionTogglePlayback).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = context.getString(if (playing) R.string.video_pause else R.string.video_play)
        val action = RemoteAction(
            Icon.createWithResource(context, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play),
            label,
            label,
            toggle,
        )
        return PictureInPictureParams.Builder().apply {
            if (aspectRatio != null) {
                val ratio = aspectRatio.coerceIn(MinRatio, MaxRatio)
                setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
                videoBounds(activity, ratio)?.let { setSourceRectHint(it) }
            }
            setActions(listOf(action))
            // From Android 12 the window shrinks by itself when the user leaves while it plays.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(autoEnter)
        }.build()
    }

    /**
     * Where the video shows in the window: the full-screen player fits it, centred, into the whole
     * window. The system animates from these bounds, so the shrink starts from the picture itself.
     */
    private fun videoBounds(activity: Activity, ratio: Float): Rect? {
        val window = activity.window?.decorView ?: return null
        val width = window.width
        val height = window.height
        if (width <= 0 || height <= 0) return null
        return if (width.toFloat() / height > ratio) {
            val videoWidth = (height * ratio).toInt()
            val left = (width - videoWidth) / 2
            Rect(left, 0, left + videoWidth, height)
        } else {
            val videoHeight = (width / ratio).toInt()
            val top = (height - videoHeight) / 2
            Rect(0, top, width, top + videoHeight)
        }
    }

    /** Receiver for the picture-in-picture window's play/pause button. */
    internal fun receiver(playback: VideoPlayback) = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ActionTogglePlayback) playback.toggleViewerPlayback()
        }
    }

    internal val filter get() = IntentFilter(ActionTogglePlayback)

    internal fun update(activity: Activity, aspectRatio: Float?, playing: Boolean) =
        activity.setPictureInPictureParams(params(activity, aspectRatio, playing, autoEnter = playing))

    /** Back to a window that never shrinks by itself, once nothing plays full screen. */
    internal fun reset(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
    }
}

/** Whether the app's window is the small picture-in-picture one, where only the video should show. */
@Composable
fun rememberIsInPictureInPictureMode(): Boolean {
    val activity = LocalActivity.current as? ComponentActivity
    var inPip by remember(activity) { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

/**
 * While the full-screen video is shown: keeps the picture-in-picture window's shape and play/pause
 * button current, lets it shrink on its own when the user leaves the app mid-playback, and handles its button.
 */
@Composable
fun PictureInPictureController(playback: VideoPlayback, state: PlayerUiState) {
    val activity = LocalActivity.current ?: return
    if (!PictureInPicture.isSupported(activity)) return
    DisposableEffect(activity, playback) {
        val receiver = PictureInPicture.receiver(playback)
        ContextCompat.registerReceiver(activity, receiver, PictureInPicture.filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            activity.unregisterReceiver(receiver)
            PictureInPicture.reset(activity)
        }
    }
    LaunchedEffect(activity, state.aspectRatio, state.isPlaying) {
        PictureInPicture.update(activity, state.aspectRatio, state.isPlaying)
    }
}
