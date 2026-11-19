package co.magicpages.socialweb.ui.media

import co.magicpages.socialweb.data.activitypub.Attachment
import co.magicpages.socialweb.data.activitypub.Post
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * A video a post carries, ready to play: where it plays from, and what to show before it does.
 *
 * [playbackUrl] differs from the attachment's own URL for Bluesky videos (see [Attachment.toVideoSource]).
 */
data class VideoSource(
    val playbackUrl: String,
    /** Whether [playbackUrl] is an HLS playlist, which ExoPlayer can't tell from the URL alone. */
    val isHls: Boolean,
    /** An image to show before the video plays, if the attachment or its origin names one. */
    val posterUrl: String?,
    /** Width over height, if the attachment says; otherwise the poster or the video itself shows it. */
    val aspectRatio: Float?,
    val description: String?,
)

/** The videos of a post, in the order the post lists them. Images and other attachments are not among them. */
val Post.videos: List<VideoSource> get() = attachments.mapNotNull { it.toVideoSource() }

/**
 * The video this attachment is, or `null` if it isn't one (or its URL isn't a plain web address).
 *
 * It is a video when its media type is any `video` type or an HLS playlist type, or, with no usable media
 * type, when its type is `Video` or its URL is a `.m3u8` playlist. A declared `image` or `audio`
 * media type wins over everything else.
 *
 * A video bridged from Bluesky by Bridgy Fed has no media type: it is `{"type": "Video", "url":
 * "https://<pds>/xrpc/com.atproto.sync.getBlob?did=…&cid=…", "image": {"type": "Image", "url":
 * "https://video.bsky.app/watch/<did>/<cid>/thumbnail.jpg"}}`. That URL is the original upload on the
 * poster's own server, which can be tens of megabytes and isn't meant for streaming. Bluesky's video
 * service has the same video as an HLS stream, so that is what plays.
 */
fun Attachment.toVideoSource(): VideoSource? {
    val uri = webUri(url) ?: return null
    val mime = mediaType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    val playlistUrl = uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)
    val hls = mime in HlsMimeTypes || playlistUrl
    val video = when {
        mime.startsWith("video/") || mime in HlsMimeTypes -> true
        mime.startsWith("image/") || mime.startsWith("audio/") -> false
        else -> type.equals("Video", ignoreCase = true) || playlistUrl
    }
    if (!video) return null

    val poster = posterUrl?.takeIf { webUri(it) != null }
    val ratio = if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else null
    val description = name?.takeIf { it.isNotBlank() }
    val bluesky = BlueskyVideo.from(uri)
    return if (bluesky != null) {
        VideoSource(bluesky.stream, isHls = true, posterUrl = poster ?: bluesky.thumbnail, aspectRatio = ratio, description = description)
    } else {
        VideoSource(url, isHls = hls, posterUrl = poster, aspectRatio = ratio, description = description)
    }
}

private val HlsMimeTypes = setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl")

private fun webUri(value: String): URI? {
    val uri = try {
        URI(value.trim())
    } catch (_: URISyntaxException) {
        return null
    }
    return uri.takeIf { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrEmpty() }
}

/** A video on Bluesky's video service, named by the poster's account (DID) and the video's blob (CID). */
private class BlueskyVideo(val stream: String, val thumbnail: String) {
    companion object {
        private const val BlobPath = "/xrpc/com.atproto.sync.getBlob"
        private val Did = Regex("did:[A-Za-z0-9:._-]+")
        private val Cid = Regex("[A-Za-z0-9]+")

        fun from(blob: URI): BlueskyVideo? {
            if (blob.path != BlobPath) return null
            val query = blob.rawQuery?.split('&')?.mapNotNull { pair ->
                val name = pair.substringBefore('=')
                if (name.isEmpty() || '=' !in pair) return@mapNotNull null
                name to (runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrNull() ?: return@mapNotNull null)
            }.orEmpty().toMap()
            val did = query["did"]?.takeIf { Did.matches(it) } ?: return null
            val cid = query["cid"]?.takeIf { Cid.matches(it) } ?: return null
            // Only characters that can't change the URL's shape got through above.
            val base = "https://video.bsky.app/watch/${did.replace(":", "%3A")}/$cid/"
            return BlueskyVideo(base + "playlist.m3u8", base + "thumbnail.jpg")
        }
    }
}
