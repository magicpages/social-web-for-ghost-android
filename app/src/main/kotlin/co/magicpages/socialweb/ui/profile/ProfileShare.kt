package co.magicpages.socialweb.ui.profile

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.annotation.ColorInt
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import co.magicpages.socialweb.data.ghost.displayAddress
import co.magicpages.socialweb.data.ghost.fediPubUrl
import co.magicpages.socialweb.data.ghost.ghostSiteUrl
import co.magicpages.socialweb.ui.quote.QuoteCardPalette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The page a shared profile opens. A Ghost account has no profile page of its own (its URL is the
 * site's home page), so it gets its fedi.pub page, which works for any fediverse account. Other
 * accounts use the profile page their server reports, or failing that their ActivityPub id.
 * [fallbackUrl] is the link the profile was opened from. Null when nothing is known.
 */
fun profileShareUrl(handle: String, apId: String, url: String?, fallbackUrl: String?): String? {
    if (ghostSiteUrl(apId) != null) {
        fediPubUrl(handle)?.let { return it }
    }
    return listOf(url, apId.takeIf { it.startsWith("https://") }, fallbackUrl)
        .firstOrNull { !it.isNullOrBlank() }
        ?.trim()
}

/** What a profile card shows. */
data class ProfileCardSpec(
    val name: String,
    val handle: String,
    val avatarUrl: String?,
    /** The link the QR code opens. */
    val url: String,
)

/**
 * Draws profile cards, 1080 px wide like quote cards and in the same colours: the avatar, name and
 * handle, then a QR code of the profile's link. The QR code is always dark on white, whatever the
 * theme, since scanners expect that.
 */
@Singleton
class ProfileCardRenderer @Inject constructor(@ApplicationContext private val context: Context) {
    /**
     * Draws [card] and saves it as a PNG that other apps may read through the app's file provider; the
     * Uri of that file, or null when the picture couldn't be made (no QR code fits the link, out of memory).
     */
    suspend fun renderShareable(card: ProfileCardSpec, palette: QuoteCardPalette): Uri? = withContext(Dispatchers.Default) {
        try {
            val qr = QrCode.encode(card.url) ?: return@withContext null
            val bitmap = draw(card, qr, palette)
            val file = withContext(Dispatchers.IO) { save(bitmap, card.handle) } ?: return@withContext null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: IllegalArgumentException) {
            // The file provider doesn't cover the file.
            null
        }
    }

    private suspend fun draw(card: ProfileCardSpec, qr: QrMatrix, palette: QuoteCardPalette): Bitmap {
        val avatar = card.avatarUrl?.takeIf { it.isNotBlank() }?.let { loadBitmap(it, AVATAR, AVATAR) }

        val textWidth = CONTENT_WIDTH - AVATAR - AVATAR_GAP
        val name = layout(card.name.ifBlank { card.handle }, paint(palette.onBackground, 52f, bold = true), textWidth, maxLines = 2)
        val handle = layout(card.handle, paint(palette.secondary, 34f), textWidth, maxLines = 1)
        val headerText = name.height + handle.height
        val header = maxOf(AVATAR, headerText)

        val footer = layout(displayAddress(card.url), paint(palette.secondary, 32f), CONTENT_WIDTH, maxLines = 1, align = Layout.Alignment.ALIGN_CENTER)
        val footerHeight = DIVIDER + DIVIDER_GAP * 2 + footer.height

        val height = PADDING + header + SECTION_GAP + QR_PANEL + SECTION_GAP + footerHeight + PADDING
        val bitmap = createBitmap(WIDTH, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(palette.background)

        var y = PADDING
        drawAvatar(canvas, avatar, card.name.ifBlank { card.handle }, palette, PADDING, y + (header - AVATAR) / 2)
        val textX = PADDING + AVATAR + AVATAR_GAP
        canvas.drawLayout(name, textX, y + (header - headerText) / 2)
        canvas.drawLayout(handle, textX, y + (header - headerText) / 2 + name.height)
        y += header + SECTION_GAP

        drawQr(canvas, qr, (WIDTH - QR_PANEL) / 2, y)
        y += QR_PANEL + SECTION_GAP

        val line = Paint().apply { color = palette.outline }
        canvas.drawRect(PADDING.toFloat(), y.toFloat(), (PADDING + CONTENT_WIDTH).toFloat(), (y + DIVIDER).toFloat(), line)
        canvas.drawLayout(footer, PADDING, y + DIVIDER + DIVIDER_GAP)
        return bitmap
    }

    /** A white panel with the code in it, as large as it goes with whole pixels per module. */
    private fun drawQr(canvas: Canvas, qr: QrMatrix, x: Int, y: Int) {
        val panel = RectF(x.toFloat(), y.toFloat(), (x + QR_PANEL).toFloat(), (y + QR_PANEL).toFloat())
        canvas.drawRoundRect(panel, CORNER, CORNER, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = QR_LIGHT })
        // The quiet zone is part of the pixels; the panel's padding is on top of it. Whole pixels per module
        // keep every module the same size, so the code is drawn as it is, centred, and the panel takes the slack.
        val inner = QR_PANEL - QR_PANEL_PADDING * 2
        val scale = (inner / qr.side(1)).coerceAtLeast(1)
        val side = qr.side(scale)
        val code = Bitmap.createBitmap(qr.pixels(scale, QR_DARK, QR_LIGHT), side, side, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(code, (x + (QR_PANEL - side) / 2).toFloat(), (y + (QR_PANEL - side) / 2).toFloat(), null)
    }

    private fun drawAvatar(canvas: Canvas, avatar: Bitmap?, name: String, palette: QuoteCardPalette, x: Int, y: Int) {
        val circle = Path().apply { addCircle(x + AVATAR / 2f, y + AVATAR / 2f, AVATAR / 2f, Path.Direction.CW) }
        canvas.withClip(circle) {
            if (avatar != null) {
                drawCropped(this, avatar, RectF(x.toFloat(), y.toFloat(), (x + AVATAR).toFloat(), (y + AVATAR).toFloat()))
            } else {
                drawColor(palette.placeholder)
                val initial = paint(palette.secondary, 56f, bold = true)
                val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
                drawText(letter, x + (AVATAR - initial.measureText(letter)) / 2f, y + AVATAR / 2f + 20f, initial)
            }
        }
    }

    /** Fills [target] with [bitmap], cutting what overflows evenly from both sides. */
    private fun drawCropped(canvas: Canvas, bitmap: Bitmap, target: RectF) {
        val targetRatio = target.width() / target.height()
        val bitmapRatio = bitmap.width.toFloat() / bitmap.height
        val source = if (bitmapRatio > targetRatio) {
            val width = (bitmap.height * targetRatio).toInt()
            Rect((bitmap.width - width) / 2, 0, (bitmap.width + width) / 2, bitmap.height)
        } else {
            val height = (bitmap.width / targetRatio).toInt()
            Rect(0, (bitmap.height - height) / 2, bitmap.width, (bitmap.height + height) / 2)
        }
        canvas.drawBitmap(bitmap, source, target, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun Canvas.drawLayout(layout: StaticLayout, x: Int, y: Int) {
        withTranslation(x.toFloat(), y.toFloat()) { layout.draw(this) }
    }

    private fun paint(@ColorInt color: Int, size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxLines: Int,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.2f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    /** Loads an image as a software bitmap; null when it can't be fetched or decoded. */
    private suspend fun loadBitmap(url: String, width: Int, height: Int): Bitmap? {
        val request = ImageRequest.Builder(context).data(url).size(width, height).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request)
        return (result as? SuccessResult)?.image?.toBitmap()
    }

    /** Saves [bitmap] under the cache folder the file provider shares; older cards are cleared away first. */
    private fun save(bitmap: Bitmap, handle: String): File? = try {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        val file = File(dir, "profile-${handle.filter { it.isLetterOrDigit() }.take(40).ifEmpty { "card" }}.png")
        file.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) return null }
        file
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val WIDTH = 1080
        const val PADDING = 64
        const val CONTENT_WIDTH = WIDTH - 2 * PADDING
        const val AVATAR = 144
        const val AVATAR_GAP = 32
        const val SECTION_GAP = 56
        const val QR_PANEL = 720
        const val QR_PANEL_PADDING = 24
        const val DIVIDER = 3
        const val DIVIDER_GAP = 24
        const val CORNER = 36f
        const val QR_DARK = 0xFF000000.toInt()
        const val QR_LIGHT = 0xFFFFFFFF.toInt()
        const val SHARE_DIR = "shared-profile"
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}

/** Opens the system share sheet with the profile's [url], as text. */
fun shareProfileLink(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    context.startActivity(Intent.createChooser(send, null))
}

/** Opens the system share sheet with the card picture at [image] and the profile's [url] as its caption. */
fun shareProfileImage(context: Context, image: Uri, url: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, image)
        .putExtra(Intent.EXTRA_TEXT, url)
        // The chooser's preview reads the picture through the clip.
        .apply { clipData = ClipData.newRawUri(null, image) }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}
