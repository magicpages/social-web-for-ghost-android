package co.magicpages.socialweb.ui.quote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.annotation.ColorInt
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import co.magicpages.socialweb.R
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The colours of a quote card: the app's own, so it follows light and dark mode and the wallpaper palette. */
data class QuoteCardPalette(
    @ColorInt val background: Int,
    @ColorInt val onBackground: Int,
    @ColorInt val secondary: Int,
    @ColorInt val accent: Int,
    @ColorInt val placeholder: Int,
    @ColorInt val outline: Int,
) {
    companion object {
        fun of(scheme: ColorScheme) = QuoteCardPalette(
            background = scheme.surfaceContainerLow.toArgb(),
            onBackground = scheme.onSurface.toArgb(),
            secondary = scheme.onSurfaceVariant.toArgb(),
            accent = scheme.primary.toArgb(),
            placeholder = scheme.surfaceContainerHighest.toArgb(),
            outline = scheme.outlineVariant.toArgb(),
        )
    }
}

/** A drawn quote card, encoded and ready to upload. */
class RenderedCard(
    val bitmap: Bitmap,
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String,
    /** What the picture shows, for people who can't see it. */
    val altText: String,
)

/**
 * Draws quote cards: 1080 px wide, as tall as their content. Text is laid out with the platform's
 * text engine, so scripts, emoji and right-to-left text come out as on screen.
 */
@Singleton
class QuoteCardRenderer @Inject constructor(@ApplicationContext private val context: Context) {
    /** Null when the picture couldn't be made (out of memory, or the encoder refused). */
    suspend fun render(card: QuoteCardSpec, palette: QuoteCardPalette, maxBytes: Long): RenderedCard? =
        withContext(Dispatchers.Default) {
            try {
                val (bitmap, photo, alt) = when (card) {
                    is PostQuote -> drawPost(card, palette)
                    is TextQuote -> drawText(card, palette)
                }
                encode(bitmap, photo, maxBytes, alt)
            } catch (_: OutOfMemoryError) {
                null
            }
        }

    // --- Post cards ---

    private suspend fun drawPost(card: PostQuote, palette: QuoteCardPalette): Drawn {
        val avatar = card.avatarUrl?.let { loadBitmap(it, AVATAR, AVATAR) }
        val photo = card.imageUrl?.let { loadBitmap(it, CONTENT_WIDTH, CONTENT_WIDTH * 2) }

        val sections = mutableListOf<Section>()

        val textWidth = CONTENT_WIDTH - AVATAR - AVATAR_GAP
        val name = layout(card.authorName, paint(palette.onBackground, 42f, bold = true), textWidth, maxLines = 1)
        val handle = layout(card.handle, paint(palette.secondary, 34f), textWidth, maxLines = 1)
        val headerText = name.height + handle.height
        val header = maxOf(AVATAR, headerText)
        sections += Section(header) { canvas, y ->
            drawAvatar(canvas, avatar, card.authorName, palette, PADDING, y)
            val x = PADDING + AVATAR + AVATAR_GAP
            canvas.drawLayout(name, x, y + (header - headerText) / 2)
            canvas.drawLayout(handle, x, y + (header - headerText) / 2 + name.height)
        }

        card.title?.let { title ->
            val layout = layout(title, paint(palette.onBackground, 54f, bold = true), CONTENT_WIDTH, maxLines = 4, spacing = 1.1f)
            sections += Section(layout.height) { canvas, y -> canvas.drawLayout(layout, PADDING, y) }
        }

        val bodyText = when {
            card.warned -> card.warning?.let { context.getString(R.string.quote_card_warning, it) }
                ?: context.getString(R.string.post_sensitive)
            else -> card.body
        }
        if (bodyText.isNotEmpty()) {
            val style = if (card.warned) Typeface.ITALIC else Typeface.NORMAL
            val color = if (card.warned) palette.secondary else palette.onBackground
            val layout = layout(bodyText, paint(color, 44f, style = style), CONTENT_WIDTH, maxLines = if (card.title != null) 6 else 16)
            sections += Section(layout.height) { canvas, y -> canvas.drawLayout(layout, PADDING, y) }
        }

        if (photo != null) {
            val height = (CONTENT_WIDTH.toLong() * photo.height / photo.width).toInt().coerceIn(1, MAX_PHOTO_HEIGHT)
            sections += Section(height) { canvas, y -> drawPhoto(canvas, photo, y, height, palette) }
        }

        val footer = footerLayout(listOfNotNull(card.sourceHost, card.publishedAtMillis?.let(::formatDate)).joinToString(" · "), palette)
        if (footer != null) sections += dividerAndFooter(footer, palette)

        val alt = when {
            card.warned -> bodyText
            else -> listOfNotNull(card.title, card.body.ifEmpty { null }).joinToString(". ")
        }
        return Drawn(
            compose(sections, palette),
            photo != null,
            context.getString(R.string.quote_card_alt_post, card.authorName, card.handle, ellipsize(alt, ALT_MAX)),
        )
    }

    // --- Text cards ---

    private fun drawText(card: TextQuote, palette: QuoteCardPalette): Drawn {
        val words = ellipsize(card.text, QUOTE_MAX)
        val sections = mutableListOf<Section>()

        val mark = paint(palette.accent, 200f, bold = true, typeface = Typeface.SERIF)
        sections += Section(MARK_HEIGHT) { canvas, y -> canvas.drawText("“", PADDING.toFloat(), y + MARK_BASELINE, mark) }

        val size = when (words.length) {
            in 0 until 70 -> 72f
            in 70 until 140 -> 62f
            in 140 until 240 -> 52f
            in 240 until 360 -> 46f
            else -> 40f
        }
        val quote = layout(words, paint(palette.onBackground, size, typeface = Typeface.SERIF), CONTENT_WIDTH, maxLines = 26, spacing = 1.25f)
        sections += Section(quote.height) { canvas, y -> canvas.drawLayout(quote, PADDING, y) }

        val title = card.title?.let { layout(it, paint(palette.onBackground, 40f, bold = true), CONTENT_WIDTH, maxLines = 3) }
        val host = card.host?.let { layout(it, paint(palette.secondary, 32f), CONTENT_WIDTH, maxLines = 1) }
        if (title != null || host != null) {
            val height = (title?.height ?: 0) + (host?.height ?: 0)
            sections += Section(DIVIDER + DIVIDER_GAP * 2 + height) { canvas, y ->
                val line = Paint().apply { color = palette.outline }
                canvas.drawRect(PADDING.toFloat(), y.toFloat(), (PADDING + CONTENT_WIDTH).toFloat(), (y + DIVIDER).toFloat(), line)
                val top = y + DIVIDER + DIVIDER_GAP
                title?.let { canvas.drawLayout(it, PADDING, top) }
                host?.let { canvas.drawLayout(it, PADDING, top + (title?.height ?: 0)) }
            }
        }

        val source = listOfNotNull(card.title, card.host).joinToString(", ")
        val alt = if (source.isEmpty()) {
            context.getString(R.string.quote_card_alt_quote_plain, ellipsize(card.text, ALT_MAX))
        } else {
            context.getString(R.string.quote_card_alt_quote, ellipsize(card.text, ALT_MAX), source)
        }
        return Drawn(compose(sections, palette, minHeight = MIN_TEXT_CARD_HEIGHT), false, alt)
    }

    // --- Drawing ---

    private class Section(val height: Int, val draw: (Canvas, Int) -> Unit)

    private data class Drawn(val bitmap: Bitmap, val photo: Boolean, val altText: String)

    /** Stacks the sections down a card-sized bitmap, with room between them. */
    private fun compose(sections: List<Section>, palette: QuoteCardPalette, minHeight: Int = 0): Bitmap {
        val content = sections.sumOf { it.height } + SECTION_GAP * (sections.size - 1).coerceAtLeast(0)
        val height = (content + PADDING * 2).coerceIn(minHeight, MAX_HEIGHT)
        val bitmap = createBitmap(WIDTH, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(palette.background)
        var y = PADDING
        for (section in sections) {
            section.draw(canvas, y)
            y += section.height + SECTION_GAP
        }
        return bitmap
    }

    private fun footerLayout(text: String, palette: QuoteCardPalette): StaticLayout? =
        if (text.isEmpty()) null else layout(text, paint(palette.secondary, 32f), CONTENT_WIDTH, maxLines = 1)

    private fun dividerAndFooter(footer: StaticLayout, palette: QuoteCardPalette) =
        Section(DIVIDER + DIVIDER_GAP * 2 + footer.height) { canvas, y ->
            val line = Paint().apply { color = palette.outline }
            canvas.drawRect(PADDING.toFloat(), y.toFloat(), (PADDING + CONTENT_WIDTH).toFloat(), (y + DIVIDER).toFloat(), line)
            canvas.drawLayout(footer, PADDING, y + DIVIDER + DIVIDER_GAP)
        }

    private fun drawAvatar(canvas: Canvas, avatar: Bitmap?, name: String, palette: QuoteCardPalette, x: Int, y: Int) {
        val circle = Path().apply { addCircle(x + AVATAR / 2f, y + AVATAR / 2f, AVATAR / 2f, Path.Direction.CW) }
        canvas.withClip(circle) {
            if (avatar != null) {
                drawCropped(this, avatar, RectF(x.toFloat(), y.toFloat(), (x + AVATAR).toFloat(), (y + AVATAR).toFloat()))
            } else {
                drawColor(palette.placeholder)
                val initial = paint(palette.secondary, 48f, bold = true)
                val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
                drawText(letter, x + (AVATAR - initial.measureText(letter)) / 2f, y + AVATAR / 2f + 17f, initial)
            }
        }
    }

    private fun drawPhoto(canvas: Canvas, photo: Bitmap, y: Int, height: Int, palette: QuoteCardPalette) {
        val target = RectF(PADDING.toFloat(), y.toFloat(), (PADDING + CONTENT_WIDTH).toFloat(), (y + height).toFloat())
        canvas.withClip(Path().apply { addRoundRect(target, CORNER, CORNER, Path.Direction.CW) }) {
            drawColor(palette.placeholder)
            drawCropped(this, photo, target)
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

    private fun paint(
        @ColorInt color: Int,
        size: Float,
        bold: Boolean = false,
        style: Int = Typeface.NORMAL,
        typeface: Typeface = Typeface.DEFAULT,
    ) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        this.typeface = Typeface.create(typeface, if (bold) Typeface.BOLD else style)
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int, spacing: Float = 1.2f): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, spacing)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun formatDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    /** Loads an image as a software bitmap; null when it can't be fetched or decoded. */
    private suspend fun loadBitmap(url: String, width: Int, height: Int): Bitmap? {
        val request = ImageRequest.Builder(context).data(url).size(width, height).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request)
        return (result as? SuccessResult)?.image?.toBitmap()
    }

    // --- Encoding ---

    /**
     * Text-only cards go out as PNG, which keeps their edges crisp and stays small. A picture
     * makes PNG heavy, so those go out as JPEG, at lower quality until they fit [maxBytes].
     */
    private fun encode(bitmap: Bitmap, photo: Boolean, maxBytes: Long, altText: String): RenderedCard? {
        if (!photo) {
            val png = compress(bitmap, Bitmap.CompressFormat.PNG, 100) ?: return null
            if (png.size <= maxBytes) return RenderedCard(bitmap, png, "image/png", "quote.png", altText)
        }
        var jpeg: ByteArray? = null
        for (quality in JPEG_QUALITIES) {
            jpeg = compress(bitmap, Bitmap.CompressFormat.JPEG, quality) ?: return null
            if (jpeg.size <= maxBytes) break
        }
        return jpeg?.let { RenderedCard(bitmap, it, "image/jpeg", "quote.jpg", altText) }
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (bitmap.compress(format, quality, out)) out.toByteArray() else null
    }

    private companion object {
        const val WIDTH = 1080
        const val PADDING = 64
        const val CONTENT_WIDTH = WIDTH - 2 * PADDING
        const val AVATAR = 104
        const val AVATAR_GAP = 28
        const val SECTION_GAP = 36
        const val DIVIDER = 3
        const val DIVIDER_GAP = 24
        const val CORNER = 36f
        const val MAX_PHOTO_HEIGHT = 1000
        const val MAX_HEIGHT = 4096
        const val MIN_TEXT_CARD_HEIGHT = 540
        const val MARK_HEIGHT = 96
        const val MARK_BASELINE = 150f
        const val QUOTE_MAX = 700
        const val ALT_MAX = 600
        val JPEG_QUALITIES = intArrayOf(92, 80, 65)
    }
}
