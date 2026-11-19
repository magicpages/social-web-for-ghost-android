package co.magicpages.socialweb.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/** A [Player]'s state as Compose state, for controls: playing or not, buffering, ended, failed, position and length. */
@Stable
class PlayerUiState internal constructor(private val player: Player) {
    /** Whether it is playing now (not paused, buffering, ended or failed). */
    var isPlaying by mutableStateOf(player.isPlaying)
        private set

    /** Whether it is waiting for data to play on. */
    var isBuffering by mutableStateOf(false)
        private set
    var isEnded by mutableStateOf(false)
        private set
    var error by mutableStateOf<PlaybackException?>(null)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set

    /** 0 until known (and for live streams). */
    var durationMs by mutableLongStateOf(0L)
        private set

    /** Width over height of the picture, once it is known. */
    var aspectRatio by mutableStateOf<Float?>(null)
        private set

    internal fun sync() {
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        isEnded = player.playbackState == Player.STATE_ENDED
        error = player.playerError
        durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val size = player.videoSize
        aspectRatio = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else null
        syncPosition()
    }

    internal fun syncPosition() {
        positionMs = player.currentPosition.coerceAtLeast(0L)
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        syncPosition()
    }
}

private const val PositionPollMillis = 250L

/** [PlayerUiState] for [player], kept current for as long as the caller is composed. */
@Composable
fun rememberPlayerUiState(player: Player): PlayerUiState {
    val state = remember(player) { PlayerUiState(player).also { it.sync() } }
    DisposableEffect(player, state) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = state.sync()
        }
        player.addListener(listener)
        state.sync()
        onDispose { player.removeListener(listener) }
    }
    // The player has no progress callback; ask for the position while it moves.
    LaunchedEffect(state, state.isPlaying) {
        while (state.isPlaying) {
            state.syncPosition()
            delay(PositionPollMillis)
        }
    }
    return state
}
