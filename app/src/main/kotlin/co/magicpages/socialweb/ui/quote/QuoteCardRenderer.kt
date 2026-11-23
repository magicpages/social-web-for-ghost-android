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
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ReplacementSpan
import androidx.annotation.ColorInt
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.linkpreview.LinkPreview
import co.magicpages.socialweb.data.linkpreview.LinkPreviews
import co.magicpages.socialweb.ui.components.CustomEmojiSource
import co.magicpages.socialweb.ui.components.hasShortcode
import co.magicpages.socialweb.ui.posts.LinkedPosts
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * text engine, so scripts, emoji and right-to-left text come out as on screen. A post's card shows
 * what its timeline row shows (a link card, the quoted post in a box, custom emoji, a video's still);
 * all of it, pictures included, is loaded before anything is drawn.
 */
@Singleton
class QuoteCardRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linkPreviews: LinkPreviews,
    private val linkedPosts: LinkedPosts,
    private val customEmojis: CustomEmojiSource,
) {
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

    /** Everything a post's card shows that has to be fetched. */
    private class PostAssets(
        val avatar: Bitmap?,
        val photo: Bitmap?,
        val videoStill: Bitmap?,
        val nameEmoji: List<EmojiRun>,
        val bodyEmoji: List<EmojiRun>,
        /** The emoji pictures, by address. */
        val emoji: Map<String, Bitmap>,
        val link: LinkAssets?,
        val quoted: QuotedAssets?,
    ) {
        /** Whether a picture beyond avatars and emoji is drawn, which makes a PNG heavy. */
        val hasPhoto: Boolean
            get() = photo != null || videoStill != null || link?.image != null || quoted?.assets?.hasPhoto == true
    }

    private class LinkAssets(val preview: LinkPreview, val image: Bitmap?)

    private class QuotedAssets(val card: PostQuote, val assets: PostAssets)

    private suspend fun drawPost(card: PostQuote, palette: QuoteCardPalette): Drawn {
        val assets = loadPost(card, nested = false)
        val sections = postSections(card, assets, palette, CONTENT_WIDTH, nested = false).toMutableList()

        val footer = footerLayout(listOfNotNull(card.sourceHost, card.publishedAtMillis?.let(::formatDate)).joinToString(" · "), palette)
        if (footer != null) sections += dividerAndFooter(footer, palette)

        var alt = context.getString(R.string.quote_card_alt_post, card.authorName, card.handle, ellipsize(summaryOf(card), ALT_MAX))
        assets.quoted?.card?.let { quoted ->
            val summary = summaryOf(quoted)
            alt += "\n\n" + if (summary.isEmpty()) {
                context.getString(R.string.quote_card_alt_quoted_plain, quoted.authorName, quoted.handle)
            } else {
                context.getString(R.string.quote_card_alt_quoted, quoted.authorName, quoted.handle, ellipsize(summary, ALT_MAX))
            }
        }
        return Drawn(compose(sections, palette), assets.hasPhoto, alt)
    }

    /** The words a card gives for [card]: its warning when it is hidden, else its title and body. */
    private fun summaryOf(card: PostQuote): String =
        if (card.warned) warnedText(card) else listOfNotNull(card.title, card.body.ifEmpty { null }).joinToString(". ")

    private fun warnedText(card: PostQuote): String =
        card.warning?.let { context.getString(R.string.quote_card_warning, it) } ?: context.getString(R.string.post_sensitive)

    private suspend fun loadPost(card: PostQuote, nested: Boolean): PostAssets = coroutineScope {
        val avatar = async { card.avatarUrl?.let { loadBitmap(it, AVATAR, AVATAR) } }
        val photo = async { card.imageUrl?.let { loadBitmap(it, CONTENT_WIDTH, CONTENT_WIDTH * 2) } }
        val videoStill = async { card.video?.posterUrl?.let { loadBitmap(it, CONTENT_WIDTH, CONTENT_WIDTH) } }
        // A quoted post stays one level deep, as in the timeline: what it quotes or links to isn't drawn.
        val quoted = async { card.quotedUrl?.takeIf { !nested }?.let { loadQuoted(it) } }
        val link = async {
            when {
                nested -> null
                // A quoted post that can't be shown is a link in the timeline; its page may still preview.
                card.quotedUrl != null -> if (quoted.await() == null) loadLink(card.quotedUrl) else null
                else -> card.linkUrl?.let { loadLink(it) }
            }
        }
        val nameEmoji = loadEmojiRuns(card.authorEmojiSource, card.authorName)
        val bodyEmoji = loadEmojiRuns(card.emojiSource, card.body)
        val emoji = (nameEmoji + bodyEmoji).map { it.url }.distinct()
            .map { url -> url to async { loadBitmap(url, EMOJI_BITMAP, EMOJI_BITMAP) } }
            .mapNotNull { (url, bitmap) -> bitmap.await()?.let { url to it } }
            .toMap()
        PostAssets(avatar.await(), photo.await(), videoStill.await(), nameEmoji, bodyEmoji, emoji, link.await(), quoted.await())
    }

    /** Where [text] uses the custom emoji of [sourceId]; nothing is looked up for text without a shortcode. */
    private suspend fun loadEmojiRuns(sourceId: String?, text: String): List<EmojiRun> {
        if (sourceId == null || !hasShortcode(text)) return emptyList()
        return emojiRuns(text, customEmojis.load(sourceId))
    }

    /** The post [url] points at, ready to draw; null when it is gone, private or can't be fetched. */
    private suspend fun loadQuoted(url: String): QuotedAssets? {
        val post = try {
            (linkedPosts.load(url) as? LinkedPosts.Result.Found)?.item?.post
        } catch (_: GhostException) {
            null
        } catch (_: SocialWebException) {
            null
        }
        if (post == null || post.isDeleted) return null
        val card = postQuote(post)
        return QuotedAssets(card, loadPost(card, nested = true))
    }

    private suspend fun loadLink(url: String): LinkAssets? {
        val preview = (linkPreviews.load(url) as? LinkPreviews.Result.Found)?.preview ?: return null
        return LinkAssets(preview, preview.imageUrl?.let { loadBitmap(it, CONTENT_WIDTH, CONTENT_WIDTH) })
    }

    /**
     * A post as its timeline row: author, title and words, pictures, a video's still, then a link card or
     * the quoted post. A [nested] post (the one in the quote's box) is drawn smaller and without the rest.
     */
    private fun postSections(card: PostQuote, assets: PostAssets, palette: QuoteCardPalette, width: Int, nested: Boolean): List<Section> {
        val scale = if (nested) NESTED_SCALE else 1f
        val avatarSize = (AVATAR * scale).roundToInt()
        val sections = mutableListOf<Section>()

        val textWidth = width - avatarSize - (AVATAR_GAP * scale).roundToInt()
        val name = layout(
            withEmoji(card.authorName, assets.nameEmoji, assets.emoji),
            paint(palette.onBackground, 42f * scale, bold = true), textWidth, maxLines = 1,
        )
        // The box has no footer, so its date sits with the handle.
        val handleText = if (nested) {
            listOfNotNull(card.handle, card.publishedAtMillis?.let(::formatDate)).joinToString(" · ")
        } else {
            card.handle
        }
        val handle = layout(handleText, paint(palette.secondary, 34f * scale), textWidth, maxLines = 1)
        val headerText = name.height + handle.height
        val header = maxOf(avatarSize, headerText)
        sections += Section(header) { canvas, x, y ->
            drawAvatar(canvas, assets.avatar, card.authorName, palette, x, y, avatarSize)
            val textX = x + width - textWidth
            canvas.drawLayout(name, textX, y + (header - headerText) / 2)
            canvas.drawLayout(handle, textX, y + (header - headerText) / 2 + name.height)
        }

        card.title?.let { title ->
            val layout = layout(title, paint(palette.onBackground, 54f * scale, bold = true), width, maxLines = 4, spacing = 1.1f)
            sections += Section(layout.height) { canvas, x, y -> canvas.drawLayout(layout, x, y) }
        }

        val bodyText = if (card.warned) warnedText(card) else card.body
        if (bodyText.isNotEmpty()) {
            val style = if (card.warned) Typeface.ITALIC else Typeface.NORMAL
            val color = if (card.warned) palette.secondary else palette.onBackground
            val maxLines = when {
                nested -> NESTED_BODY_LINES
                card.title != null -> 6
                else -> 16
            }
            val layout = layout(withEmoji(bodyText, assets.bodyEmoji, assets.emoji), paint(color, 44f * scale, style = style), width, maxLines)
            sections += Section(layout.height) { canvas, x, y -> canvas.drawLayout(layout, x, y) }
        }

        val maxMedia = (MAX_PHOTO_HEIGHT * scale).roundToInt()
        assets.photo?.let { photo ->
            val height = (width.toLong() * photo.height / photo.width).toInt().coerceIn(1, maxMedia)
            sections += Section(height) { canvas, x, y -> drawPhoto(canvas, photo, x, y, width, height, palette) }
        }

        card.video?.let { video ->
            val still = assets.videoStill
            val ratio = video.aspectRatio ?: still?.let { it.width.toFloat() / it.height } ?: DEFAULT_VIDEO_RATIO
            val height = (width / ratio).toInt().coerceIn(1, maxMedia)
            sections += Section(height) { canvas, x, y -> drawVideoStill(canvas, still, x, y, width, height, scale, palette) }
        }

        assets.link?.let { sections += linkCardSection(it, palette, width) }
        assets.quoted?.let { sections += quotedBoxSection(it, palette, width) }
        return sections
    }

    /** The link card as the timeline draws it: the page's picture, then its site, title and description. */
    private fun linkCardSection(link: LinkAssets, palette: QuoteCardPalette, width: Int): Section {
        val preview = link.preview
        val textWidth = width - BOX_PADDING * 2
        val site = preview.siteName.takeIf { it.isNotEmpty() && !it.equals(preview.title, ignoreCase = true) }
            ?.let { layout(it, paint(palette.secondary, 30f), textWidth, maxLines = 1) }
        val title = layout(preview.title, paint(palette.onBackground, 38f, bold = true), textWidth, maxLines = 2)
        val description = preview.description?.let { layout(it, paint(palette.secondary, 34f), textWidth, maxLines = 2) }
        val imageHeight = if (link.image != null) (width / LINK_IMAGE_RATIO).toInt() else 0
        val texts = listOfNotNull(site, title, description)
        val height = imageHeight + BOX_PADDING * 2 + texts.sumOf { it.height }
        return Section(height) { canvas, x, y ->
            canvas.withClip(roundedRect(x, y, width, height)) {
                link.image?.let { image ->
                    drawColor(palette.placeholder)
                    drawCropped(this, image, RectF(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + imageHeight).toFloat()))
                }
                var top = y + imageHeight + BOX_PADDING
                for (text in texts) {
                    drawLayout(text, x + BOX_PADDING, top)
                    top += text.height
                }
            }
            drawFrame(canvas, x, y, width, height, palette)
        }
    }

    /** The quoted post in its own outlined box, drawn smaller than the post that quotes it. */
    private fun quotedBoxSection(quoted: QuotedAssets, palette: QuoteCardPalette, width: Int): Section {
        val inner = postSections(quoted.card, quoted.assets, palette, width - BOX_PADDING * 2, nested = true)
        val height = stackHeight(inner, NESTED_SECTION_GAP) + BOX_PADDING * 2
        return Section(height) { canvas, x, y ->
            drawStack(canvas, inner, x + BOX_PADDING, y + BOX_PADDING, NESTED_SECTION_GAP)
            drawFrame(canvas, x, y, width, height, palette)
        }
    }

    // --- Text cards ---

    private fun drawText(card: TextQuote, palette: QuoteCardPalette): Drawn {
        val words = ellipsize(card.text, QUOTE_MAX)
        val sections = mutableListOf<Section>()

        val mark = paint(palette.accent, 200f, bold = true, typeface = Typeface.SERIF)
        sections += Section(MARK_HEIGHT) { canvas, x, y -> canvas.drawText("“", x.toFloat(), y + MARK_BASELINE, mark) }

        val size = when (words.length) {
            in 0 until 70 -> 72f
            in 70 until 140 -> 62f
            in 140 until 240 -> 52f
            in 240 until 360 -> 46f
            else -> 40f
        }
        val quote = layout(words, paint(palette.onBackground, size, typeface = Typeface.SERIF), CONTENT_WIDTH, maxLines = 26, spacing = 1.25f)
        sections += Section(quote.height) { canvas, x, y -> canvas.drawLayout(quote, x, y) }

        val title = card.title?.let { layout(it, paint(palette.onBackground, 40f, bold = true), CONTENT_WIDTH, maxLines = 3) }
        val host = card.host?.let { layout(it, paint(palette.secondary, 32f), CONTENT_WIDTH, maxLines = 1) }
        if (title != null || host != null) {
            val height = (title?.height ?: 0) + (host?.height ?: 0)
            sections += Section(DIVIDER + DIVIDER_GAP * 2 + height) { canvas, x, y ->
                val line = Paint().apply { color = palette.outline }
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + CONTENT_WIDTH).toFloat(), (y + DIVIDER).toFloat(), line)
                val top = y + DIVIDER + DIVIDER_GAP
                title?.let { canvas.drawLayout(it, x, top) }
                host?.let { canvas.drawLayout(it, x, top + (title?.height ?: 0)) }
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

    /** A block of the card: its [height], and how to draw it with its top left corner at the given x and y. */
    private class Section(val height: Int, val draw: (Canvas, Int, Int) -> Unit)

    private data class Drawn(val bitmap: Bitmap, val photo: Boolean, val altText: String)

    private fun stackHeight(sections: List<Section>, gap: Int): Int =
        sections.sumOf { it.height } + gap * (sections.size - 1).coerceAtLeast(0)

    private fun drawStack(canvas: Canvas, sections: List<Section>, x: Int, y: Int, gap: Int) {
        var top = y
        for (section in sections) {
            section.draw(canvas, x, top)
            top += section.height + gap
        }
    }

    /** Stacks the sections down a card-sized bitmap, with room between them. */
    private fun compose(sections: List<Section>, palette: QuoteCardPalette, minHeight: Int = 0): Bitmap {
        val height = (stackHeight(sections, SECTION_GAP) + PADDING * 2).coerceIn(minHeight, MAX_HEIGHT)
        val bitmap = createBitmap(WIDTH, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(palette.background)
        drawStack(canvas, sections, PADDING, PADDING, SECTION_GAP)
        return bitmap
    }

    private fun footerLayout(text: String, palette: QuoteCardPalette): StaticLayout? =
        if (text.isEmpty()) null else layout(text, paint(palette.secondary, 32f), CONTENT_WIDTH, maxLines = 1)

    private fun dividerAndFooter(footer: StaticLayout, palette: QuoteCardPalette) =
        Section(DIVIDER + DIVIDER_GAP * 2 + footer.height) { canvas, x, y ->
            val line = Paint().apply { color = palette.outline }
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + CONTENT_WIDTH).toFloat(), (y + DIVIDER).toFloat(), line)
            canvas.drawLayout(footer, x, y + DIVIDER + DIVIDER_GAP)
        }

    private fun drawAvatar(canvas: Canvas, avatar: Bitmap?, name: String, palette: QuoteCardPalette, x: Int, y: Int, size: Int) {
        val circle = Path().apply { addCircle(x + size / 2f, y + size / 2f, size / 2f, Path.Direction.CW) }
        canvas.withClip(circle) {
            if (avatar != null) {
                drawCropped(this, avatar, RectF(x.toFloat(), y.toFloat(), (x + size).toFloat(), (y + size).toFloat()))
            } else {
                drawColor(palette.placeholder)
                val initial = paint(palette.secondary, 48f * size / AVATAR, bold = true)
                val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
                drawText(letter, x + (size - initial.measureText(letter)) / 2f, y + size / 2f + 17f * size / AVATAR, initial)
            }
        }
    }

    private fun drawPhoto(canvas: Canvas, photo: Bitmap, x: Int, y: Int, width: Int, height: Int, palette: QuoteCardPalette) {
        val target = RectF(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        canvas.withClip(Path().apply { addRoundRect(target, CORNER, CORNER, Path.Direction.CW) }) {
            drawColor(palette.placeholder)
            drawCropped(this, photo, target)
        }
    }

    /** A video as its poster with a play button, like the timeline's player before it starts; a plain frame without a poster. */
    private fun drawVideoStill(canvas: Canvas, still: Bitmap?, x: Int, y: Int, width: Int, height: Int, scale: Float, palette: QuoteCardPalette) {
        val target = RectF(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        canvas.withClip(Path().apply { addRoundRect(target, CORNER, CORNER, Path.Direction.CW) }) {
            drawColor(palette.placeholder)
            still?.let { drawCropped(this, it, target) }
        }
        val radius = PLAY_RADIUS * scale
        val centerX = target.centerX()
        val centerY = target.centerY()
        canvas.drawCircle(centerX, centerY, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLAY_SCRIM })
        val triangle = Path().apply {
            moveTo(centerX - radius * 0.25f, centerY - radius * 0.42f)
            lineTo(centerX - radius * 0.25f, centerY + radius * 0.42f)
            lineTo(centerX + radius * 0.45f, centerY)
            close()
        }
        canvas.drawPath(triangle, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLAY_ICON })
    }

    private fun roundedRect(x: Int, y: Int, width: Int, height: Int): Path =
        Path().apply { addRoundRect(RectF(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat()), BOX_CORNER, BOX_CORNER, Path.Direction.CW) }

    /** The outline of a card in the card, drawn inside its bounds. */
    private fun drawFrame(canvas: Canvas, x: Int, y: Int, width: Int, height: Int, palette: QuoteCardPalette) {
        val half = BOX_STROKE / 2f
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = BOX_STROKE
            color = palette.outline
        }
        canvas.drawRoundRect(RectF(x + half, y + half, x + width - half, y + height - half), BOX_CORNER, BOX_CORNER, frame)
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

    /** [text] with each of [runs] that has a picture in [images] drawn as that picture in place of its shortcode. */
    private fun withEmoji(text: String, runs: List<EmojiRun>, images: Map<String, Bitmap>): CharSequence {
        val drawable = runs.filter { it.url in images }
        if (drawable.isEmpty()) return text
        return SpannableString(text).apply {
            for (run in drawable) setSpan(EmojiSpan(images.getValue(run.url)), run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** A custom emoji drawn a little larger than the text around it, centred on the line like the timeline's. */
    private class EmojiSpan(private val bitmap: Bitmap) : ReplacementSpan() {
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            (paint.textSize * EMOJI_SIZE).roundToInt()

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val size = paint.textSize * EMOJI_SIZE
            val metrics = paint.fontMetrics
            val centerY = y + (metrics.ascent + metrics.descent) / 2f
            canvas.drawBitmap(bitmap, null, RectF(x, centerY - size / 2f, x + size, centerY + size / 2f), bitmapPaint)
        }
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

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int, spacing: Float = 1.2f): StaticLayout =
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
        const val BOX_PADDING = 32
        const val BOX_CORNER = 28f
        const val BOX_STROKE = 3f
        const val NESTED_SCALE = 0.8f
        const val NESTED_SECTION_GAP = 24
        const val NESTED_BODY_LINES = 8
        const val LINK_IMAGE_RATIO = 1.91f
        const val DEFAULT_VIDEO_RATIO = 16f / 9f
        const val PLAY_RADIUS = 56f
        val PLAY_SCRIM = 0x99000000.toInt()
        val PLAY_ICON = 0xFFFFFFFF.toInt()
        /** The size emoji pictures are loaded at, and how much larger than the text they are drawn (as in the timeline). */
        const val EMOJI_BITMAP = 96
        const val EMOJI_SIZE = 1.2f
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
