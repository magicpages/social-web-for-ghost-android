package co.magicpages.socialweb.ui.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.media.LocalImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The part of a [width] by [height] picture that stays when it is cut to a centred square: [side] wide, from ([left], [top]). */
data class SquareCrop(val left: Int, val top: Int, val side: Int)

/** The largest centred square of a [width] by [height] picture; null when it has no area. */
fun centredSquare(width: Int, height: Int): SquareCrop? {
    if (width <= 0 || height <= 0) return null
    val side = minOf(width, height)
    return SquareCrop((width - side) / 2, (height - side) / 2, side)
}

/**
 * The site only takes square profile pictures (Ghost's web client turns others away). Phone photos
 * rarely are, so the picture is cut to a centred square and kept under [MaxSide] pixels, which is
 * more than a profile picture is ever shown at.
 */
@Singleton
class AvatarImage @Inject constructor() {
    /** [image] as a square the service takes; null when it can't be decoded or there isn't memory for it. */
    suspend fun square(image: LocalImage): LocalImage? = withContext(Dispatchers.Default) {
        try {
            squareOrNull(image)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun squareOrNull(image: LocalImage): LocalImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        if (width == height && width <= MaxSide && image.bytes.size <= ActivityPubClient.MAX_IMAGE_BYTES) return image

        // Decode at most about twice the size needed.
        var sample = 1
        while (minOf(width, height) / (sample * 2) >= MaxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            image.bytes,
            0,
            image.bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val crop = centredSquare(decoded.width, decoded.height) ?: return null
        val matrix = orientation(image.bytes)
        val square = Bitmap.createBitmap(decoded, crop.left, crop.top, crop.side, crop.side, matrix, true)
        val sized = if (square.width > MaxSide) square.scale(MaxSide, MaxSide) else square

        // Pictures that may have transparency stay PNG; photos go out as JPEG.
        val keepsAlpha = image.mimeType == "image/png" || image.mimeType == "image/webp" || image.mimeType == "image/gif"
        if (keepsAlpha) {
            encode(sized, Bitmap.CompressFormat.PNG, 100)
                ?.takeIf { it.size <= ActivityPubClient.MAX_IMAGE_BYTES }
                ?.let { return LocalImage(it, "image/png", "avatar.png") }
        }
        var jpeg: ByteArray? = null
        for (quality in JpegQualities) {
            jpeg = encode(sized, Bitmap.CompressFormat.JPEG, quality) ?: return null
            if (jpeg.size <= ActivityPubClient.MAX_IMAGE_BYTES) break
        }
        return jpeg?.takeIf { it.size <= ActivityPubClient.MAX_IMAGE_BYTES }?.let { LocalImage(it, "image/jpeg", "avatar.jpg") }
    }

    /** The turn and mirroring a picture's EXIF orientation asks for, so a phone photo isn't sideways once re-encoded. */
    private fun orientation(bytes: ByteArray): Matrix? {
        val value = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (value) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return null
        }
        return matrix
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (bitmap.compress(format, quality, out)) out.toByteArray() else null
    }

    companion object {
        const val MaxSide = 1024
        private val JpegQualities = intArrayOf(92, 80, 65)
    }
}
