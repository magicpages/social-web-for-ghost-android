package co.magicpages.socialweb.ui.article

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.components.AppIcons
import kotlinx.coroutines.delay
import java.io.IOException

/** Where an [ArticleAudio] source is: nothing loaded, loading, loaded (playing or paused), or broken. */
enum class AudioPhase { Idle, Preparing, Ready, Failed }

/**
 * The one [MediaPlayer] of an article screen. It lives above the list, so a podcast keeps playing
 * while its player card scrolls out of composition. Playing another URL stops the current one.
 * Nothing is fetched until the first play.
 */
@Stable
class ArticleAudio {
    /** The URL last handed to [toggle]; the other state describes this source only. */
    var url by mutableStateOf<String?>(null)
        private set
    var phase by mutableStateOf(AudioPhase.Idle)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set

    private var mediaPlayer: MediaPlayer? = null

    // Set by a play tap, cleared by anything that pauses; decides whether a player that finishes preparing starts.
    private var wantsPlay = false

    /** Plays or pauses [source]; starts it, stopping any other source, if it isn't the current one. */
    fun toggle(source: String) {
        if (source != url) return prepare(source)
        when (phase) {
            AudioPhase.Preparing -> Unit
            AudioPhase.Ready -> if (playing) pause() else play()
            AudioPhase.Idle, AudioPhase.Failed -> prepare(source)
        }
    }

    fun pause() {
        wantsPlay = false
        val player = mediaPlayer ?: return
        if (!playing) return
        try {
            player.pause()
        } catch (_: IllegalStateException) {
            return fail()
        }
        playing = false
        syncPosition()
    }

    fun seekTo(millis: Int) {
        val player = mediaPlayer?.takeIf { phase == AudioPhase.Ready } ?: return
        val target = millis.coerceIn(0, durationMs)
        try {
            player.seekTo(target)
            positionMs = target
        } catch (_: IllegalStateException) {
            fail()
        }
    }

    /** MediaPlayer has no progress callback; whoever shows the position asks for it while [playing]. */
    fun syncPosition() {
        val player = mediaPlayer?.takeIf { phase == AudioPhase.Ready } ?: return
        try {
            positionMs = player.currentPosition.coerceIn(0, durationMs)
        } catch (_: IllegalStateException) {
            fail()
        }
    }

    fun release() {
        wantsPlay = false
        mediaPlayer?.release()
        mediaPlayer = null
        playing = false
    }

    private fun play() {
        val player = mediaPlayer ?: return
        wantsPlay = true
        try {
            player.start()
        } catch (_: IllegalStateException) {
            return fail()
        }
        playing = true
    }

    private fun prepare(source: String) {
        release()
        url = source
        wantsPlay = true
        phase = AudioPhase.Preparing
        positionMs = 0
        durationMs = 0
        val player = MediaPlayer()
        mediaPlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        player.setOnPreparedListener {
            if (mediaPlayer !== it) return@setOnPreparedListener
            durationMs = it.duration.coerceAtLeast(0)
            phase = AudioPhase.Ready
            if (wantsPlay) play()
        }
        player.setOnCompletionListener {
            if (mediaPlayer !== it) return@setOnCompletionListener
            playing = false
            wantsPlay = false
            positionMs = 0
            it.seekTo(0)
        }
        player.setOnErrorListener { failed, _, _ ->
            if (mediaPlayer === failed) fail()
            true
        }
        try {
            player.setDataSource(source)
            player.prepareAsync()
        } catch (_: IOException) {
            fail()
        } catch (_: IllegalArgumentException) {
            fail()
        } catch (_: IllegalStateException) {
            fail()
        } catch (_: SecurityException) {
            fail()
        }
    }

    private fun fail() {
        release()
        phase = AudioPhase.Failed
    }
}

/** Provided by the screen that shows articles; [AudioPlayer] makes its own when absent. */
val LocalArticleAudio = staticCompositionLocalOf<ArticleAudio?> { null }

/** An [ArticleAudio] that is released with the caller and paused when the app goes to the background. */
@Composable
fun rememberArticleAudio(): ArticleAudio {
    val audio = remember { ArticleAudio() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(audio, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) audio.pause() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            audio.release()
        }
    }
    return audio
}

/**
 * The card for an article's audio: play or pause, a seek bar and elapsed over total time. It
 * shows [LocalArticleAudio]'s state when that is playing [url], and looks idle otherwise.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AudioPlayer(url: String, title: String?, modifier: Modifier = Modifier) {
    val audio = LocalArticleAudio.current ?: rememberArticleAudio()
    val current = audio.url == url
    val phase = if (current) audio.phase else AudioPhase.Idle
    val playing = current && audio.playing
    LaunchedEffect(audio, playing) {
        while (playing) {
            audio.syncPosition()
            delay(PositionPollMillis)
        }
    }

    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledIconButton(onClick = { audio.toggle(url) }) {
                    if (phase == AudioPhase.Preparing) {
                        LoadingIndicator(Modifier.size(24.dp))
                    } else {
                        Icon(
                            if (playing) AppIcons.PauseFilled else AppIcons.PlayArrowFilled,
                            contentDescription = stringResource(if (playing) R.string.article_audio_pause else R.string.article_audio_play),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.article_audio_label),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Progress(audio, current, phase)
                }
            }
            if (phase == AudioPhase.Failed) {
                val uriHandler = LocalUriHandler.current
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.article_audio_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { uriHandler.openUri(url) }) { Text(stringResource(R.string.open_in_browser)) }
                }
            }
        }
    }
}

@Composable
private fun Progress(audio: ArticleAudio, current: Boolean, phase: AudioPhase) {
    var scrubbing by remember(audio) { mutableStateOf<Float?>(null) }
    val duration = if (current) audio.durationMs else 0
    val position = if (current) audio.positionMs else 0
    val fraction = scrubbing ?: if (duration > 0) position.toFloat() / duration else 0f
    val elapsed = formatTime(if (scrubbing != null) (fraction * duration).toInt() else position)
    val total = if (duration > 0) formatTime(duration) else null
    val label = stringResource(R.string.article_audio_seek)
    val state = if (total != null) stringResource(R.string.article_audio_position, elapsed, total) else elapsed
    Slider(
        value = fraction.coerceIn(0f, 1f),
        onValueChange = { scrubbing = it },
        onValueChangeFinished = {
            scrubbing?.let { audio.seekTo((it * duration).toInt()) }
            scrubbing = null
        },
        enabled = phase == AudioPhase.Ready && duration > 0,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                stateDescription = state
            },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(elapsed, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (total != null) {
            Text(total, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val PositionPollMillis = 250L

private fun formatTime(millis: Int): String {
    val seconds = millis / 1000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds % 60) else "%d:%02d".format(minutes, seconds % 60)
}
