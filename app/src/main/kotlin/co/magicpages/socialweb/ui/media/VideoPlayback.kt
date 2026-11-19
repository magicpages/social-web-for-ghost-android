package co.magicpages.socialweb.ui.media

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import co.magicpages.socialweb.data.settings.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/** The app's [VideoPlayback], provided at the top of the UI; `null` outside it, where videos stay still. */
val LocalVideoPlayback = staticCompositionLocalOf<VideoPlayback?> { null }

/**
 * Everything that plays video, so that only what the user can see plays.
 *
 * - **In lists** videos report how much of them is on screen ([report]). The most visible one, if at
 *   least half of it shows, plays muted and in a loop on a single shared player; the others stay on
 *   their poster. Nothing plays while the app is stopped or the full-screen player is open.
 * - **Full screen** ([openViewer]) is one player with sound, which lives as long as the viewer is
 *   open, so it survives rotation and picture-in-picture.
 *
 * Players are created when needed and released when not, so no hardware decoder is held idle. A
 * singleton, because the viewer must outlive the activity's recreation. Everything runs on the main thread.
 */
@Singleton
class VideoPlayback @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: AppSettings,
) {
    /** Whether list videos start on their own; they report no visibility to [report] while it is off. */
    val autoplay: StateFlow<Boolean> = settings.autoplayVideos

    private class Candidate(val source: VideoSource, var fraction: Float)

    private val candidates = LinkedHashMap<Any, Candidate>()
    private var appVisible = true
    private var listExo: ExoPlayer? = null
    private var viewerExo: ExoPlayer? = null

    /** The list video playing now, as the token it reported with, and its (muted, looping) player. */
    var inlineOwner by mutableStateOf<Any?>(null)
        private set
    var inlinePlayer by mutableStateOf<Player?>(null)
        private set

    /** Set when [inlineOwner] can't play; it then shows its poster and play button like a stopped video. */
    var inlineFailed by mutableStateOf(false)
        private set

    /** The video open full screen, with its player. */
    var viewing by mutableStateOf<VideoSource?>(null)
        private set
    var viewerPlayer by mutableStateOf<Player?>(null)
        private set

    /** A list video says how much of it is on screen (0 to 1). Call again whenever that changes. */
    fun report(token: Any, source: VideoSource, fraction: Float) {
        val candidate = candidates[token]
        if (candidate != null && candidate.source == source) {
            if (candidate.fraction == fraction) return
            candidate.fraction = fraction
        } else {
            candidates[token] = Candidate(source, fraction)
        }
        reevaluate()
    }

    /** The list video behind [token] left the screen's composition. */
    fun remove(token: Any) {
        if (candidates.remove(token) != null) reevaluate()
    }

    /** The app is on screen (or not): list videos play only while it is; the full-screen one pauses when it isn't. */
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        if (!visible) viewerExo?.pause()
        reevaluate()
    }

    /**
     * Opens [source] full screen with sound, from its start, or from where the list video behind
     * [from] has got to if that is what plays it. A video without a sound track loops, as the GIFs
     * that servers turn into such videos do.
     */
    fun openViewer(source: VideoSource, from: Any? = null) {
        val startMs = if (from != null && from == inlineOwner) listExo?.currentPosition ?: 0L else 0L
        viewerExo?.release()
        val player = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        viewerExo = player
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.isEmpty) return
                val silent = !tracks.containsType(C.TRACK_TYPE_AUDIO)
                player.repeatMode = if (silent) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
        })
        viewerPlayer = player
        viewing = source
        player.setMediaItem(source.mediaItem(), startMs)
        player.prepare()
        player.playWhenReady = appVisible
        reevaluate()
    }

    fun closeViewer() {
        viewerExo?.release()
        viewerExo = null
        viewerPlayer = null
        viewing = null
        reevaluate()
    }

    /** Pauses the full-screen video, or plays it (from the start if it ended). */
    fun toggleViewerPlayback() {
        val player = viewerExo ?: return
        when {
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0)
                player.play()
            }
            player.playWhenReady -> player.pause()
            else -> player.play()
        }
    }

    private fun reevaluate() {
        val eligible = if (appVisible && viewing == null) {
            candidates.entries.filter { it.value.fraction >= PlayThreshold }
        } else {
            emptyList()
        }
        var winner = eligible.maxByOrNull { it.value.fraction }?.key
        // Keep the current one unless another is clearly more visible, so two equally visible videos don't trade places.
        val current = inlineOwner
        if (current != null && winner != null && winner != current) {
            val currentFraction = candidates[current]?.fraction ?: 0f
            val winnerFraction = candidates.getValue(winner).fraction
            if (currentFraction >= PlayThreshold && winnerFraction - currentFraction < SwitchMargin) winner = current
        }
        if (winner != current) switchInline(winner)
    }

    private fun switchInline(owner: Any?) {
        listExo?.release()
        listExo = null
        inlinePlayer = null
        inlineOwner = owner
        inlineFailed = false
        val source = owner?.let { candidates[it]?.source } ?: return
        val player = ExoPlayer.Builder(context).build()
        player.volume = 0f
        player.repeatMode = Player.REPEAT_MODE_ONE
        // A muted preview needs neither the sound track nor the highest quality.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setMaxVideoSizeSd()
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (listExo !== player) return
                listExo = null
                inlinePlayer = null
                inlineFailed = true
                player.release()
            }
        })
        listExo = player
        inlinePlayer = player
        player.setMediaItem(source.mediaItem())
        player.prepare()
        player.playWhenReady = true
    }

    private fun VideoSource.mediaItem(): MediaItem =
        MediaItem.Builder().setUri(playbackUrl).apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()

    private companion object {
        /** How much of a list video must show for it to play. */
        const val PlayThreshold = 0.5f

        /** How much more visible another video must be to take over from the one playing. */
        const val SwitchMargin = 0.1f
    }
}
