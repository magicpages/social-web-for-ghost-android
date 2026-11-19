package co.magicpages.socialweb.ui.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * First frames of video files, and what is learned about a video along the way.
 *
 * A video's frame is read from the start of the file (the platform fetches only the parts it
 * needs), not by downloading the whole video. Playlists can't be read that way: those videos
 * show their own poster image, or none, until they play.
 */
object VideoPosters {
    /** A video's first frame, its shape and whether it has a sound track. */
    class Poster(val frame: Bitmap, val aspectRatio: Float, val hasAudio: Boolean)

    private val posters = object : LruCache<String, Poster>(MaxCachedBytes) {
        override fun sizeOf(key: String, value: Poster) = value.frame.byteCount
    }
    private val ratios = LruCache<String, Float>(MaxCachedRatios)
    private val readers = Semaphore(MaxConcurrentReads)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun cached(url: String): Poster? = posters.get(url)

    /** The shape learned for [url] earlier, so a list scrolled back doesn't reflow. */
    fun knownRatio(url: String): Float? = ratios.get(url)

    fun rememberRatio(url: String, ratio: Float) {
        if (ratio.isFinite() && ratio > 0f) ratios.put(url, ratio)
    }

    /** The poster of the video file at [url], or `null` if it can't be read (unsupported, offline, slow). */
    suspend fun load(url: String): Poster? {
        cached(url)?.let { return it }
        val poster = readers.withPermit {
            // The platform call can't be cancelled; the timeout only stops waiting for it.
            val reading = io.async { read(url) }
            withTimeoutOrNull(ReadTimeoutMillis) { reading.await() } ?: return@withPermit null
        }
        if (poster != null) {
            posters.put(url, poster)
            rememberRatio(url, poster.aspectRatio)
        }
        return poster
    }

    private fun read(url: String): Poster? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, emptyMap())
            fun number(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            var width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            var height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            // Phone videos are often stored sideways with a rotation flag.
            if (number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0) width = height.also { height = width }
            if (width <= 0 || height <= 0) return null
            val scale = minOf(1f, MaxFrameEdge.toFloat() / maxOf(width, height))
            val frame = retriever.getScaledFrameAtTime(
                0,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
            ) ?: return null
            Poster(frame, width.toFloat() / height, hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null)
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    private const val MaxCachedBytes = 24 * 1024 * 1024
    private const val MaxCachedRatios = 400
    private const val MaxConcurrentReads = 3
    private const val MaxFrameEdge = 1280
    private const val ReadTimeoutMillis = 20_000L
}
