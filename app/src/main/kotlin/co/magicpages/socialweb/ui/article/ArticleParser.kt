package co.magicpages.socialweb.ui.article

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI

/**
 * Turns the HTML of a federated `Article` into an [ArticleDocument]. Pure Kotlin on jsoup (no
 * `AnnotatedString.fromHtml`, which needs the Android framework), so it runs on any thread and
 * in plain JVM tests.
 *
 * Ghost's Koenig cards are recognised by their `kg-*` classes; anything else is read as plain
 * HTML, and wrappers and unknown cards are walked for the text, links and images inside.
 */
object ArticleParser {
    /** [baseUrl] is the post's web URL; relative URLs resolve against it, or stay as written if it is null. */
    fun parse(html: String, baseUrl: String?): ArticleDocument {
        val body = Jsoup.parseBodyFragment(html).body()
        return ArticleDocument(Walker(baseUrl).blocks(body))
    }
}

/** Elements that never carry article content: scripts, forms (signup cards), player chrome, icons. */
private val SKIP_TAGS = setOf(
    "script", "style", "noscript", "template", "head", "title", "meta", "link", "form", "input", "button",
    "select", "textarea", "svg", "canvas", "object", "embed",
)

private val SKIP_CLASSES = setOf("kg-signup-card", "kg-video-overlay", "kg-video-player-container")

/** Elements that stay in the running text when they sit between blocks. */
private val INLINE_TAGS = setOf(
    "a", "abbr", "acronym", "b", "bdi", "bdo", "big", "br", "cite", "code", "data", "del", "dfn", "em", "font",
    "i", "ins", "kbd", "mark", "q", "s", "samp", "small", "span", "strike", "strong", "sub", "sup", "time", "tt",
    "u", "var", "wbr",
)

/** Descendants that make an inline-looking wrapper a container of blocks instead. */
private const val BLOCK_SELECTOR =
    "p, div, figure, img, ul, ol, table, pre, blockquote, h1, h2, h3, h4, h5, h6, hr, iframe, video, audio, details, section, article, aside"

/** What a `<p>` can still hold that is not text: HTML closes the paragraph for every other block. */
private const val MEDIA_SELECTOR = "img, iframe, video, audio"

/** Tags that start and end a line when they appear inside running text (quotes, list items, cells). */
private val TEXT_BLOCK_TAGS = setOf(
    "p", "div", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "figure", "figcaption", "tr",
    "td", "th", "table", "thead", "tbody", "tfoot", "pre", "details", "summary", "section", "article", "aside",
    "header", "footer", "hr",
)

private val BUTTON_CLASSES = setOf("kg-btn", "kg-cta-button", "kg-header-card-button", "kg-product-card-button")

private val UNSAFE_SCHEME = Regex("^(javascript|data|vbscript):", RegexOption.IGNORE_CASE)
private val STYLE_URL = Regex("""url\(\s*(['"]?)(.*?)\1\s*\)""")
private val CODE_LANGUAGE = Regex("(?:language|lang)-(.+)")
private val VIDEO_ID = Regex("[A-Za-z0-9_-]{6,20}")

private fun isSkipped(el: Element): Boolean =
    el.normalName() in SKIP_TAGS || el.hasAttr("hidden") || el.classNames().any { it in SKIP_CLASSES }

private fun isInline(el: Element): Boolean {
    val tag = el.normalName()
    if (tag !in INLINE_TAGS) return false
    if (tag == "a" && el.classNames().any { it in BUTTON_CLASSES }) return false
    return el.selectFirst(BLOCK_SELECTOR) == null
}

private fun headingLevel(tag: String): Int? =
    if (tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6') tag[1].digitToInt() else null

private fun widthOf(classes: Set<String>): ImageWidth = when {
    "kg-width-full" in classes -> ImageWidth.Full
    "kg-width-wide" in classes -> ImageWidth.Wide
    else -> ImageWidth.Regular
}

/** The whitespace HTML collapses; a no-break space is not one of them. */
private fun Char.isCollapsible(): Boolean = this == ' ' || this == '\n' || this == '\t' || this == '\r' || this == '\u000C'

private fun AnnotatedString.isBlankText(): Boolean = text.all { it.isWhitespace() || it == '\u200B' }

private fun Element.textOf(selector: String): String? = selectFirst(selector)?.text()?.trim()?.takeIf { it.isNotEmpty() }

private fun MutableList<ArticleBlock>.addIfNotNull(block: ArticleBlock?) {
    if (block != null) add(block)
}

private class Walker(baseUrl: String?) {
    private val base: URI? = baseUrl
        ?.let { runCatching { URI(it.trim()) }.getOrNull() }
        ?.takeIf { it.isAbsolute && it.host != null }
        // java.net.URI joins "https://host" and "path" into "https://hostpath"; a root path avoids that.
        ?.let { if (it.rawPath.isNullOrEmpty()) it.resolve("/") else it }

    fun blocks(parent: Element): List<ArticleBlock> = ArrayList<ArticleBlock>().also { collect(parent, null, it) }

    /** Makes [raw] absolute, or null for empty values and script-like schemes. */
    private fun resolve(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty() || UNSAFE_SCHEME.containsMatchIn(url)) return null
        val origin = base ?: return if (url.startsWith("//")) "https:$url" else url
        return try {
            origin.resolve(url).toString()
        } catch (_: IllegalArgumentException) {
            url
        }
    }

    private fun richText(el: Element): AnnotatedString = richTextOf(el.childNodes())

    private fun richTextOf(nodes: List<Node>): AnnotatedString =
        InlineWriter(this::resolve).apply { write(nodes) }.build()

    /**
     * Walks [parent]'s children: blocks map one by one, runs of loose inline nodes between them
     * become one paragraph. [skip] is a child already handled by the caller (a `<summary>`).
     */
    private fun collect(parent: Element, skip: Element?, out: MutableList<ArticleBlock>) {
        val loose = ArrayList<Node>()
        fun flush() {
            if (loose.isEmpty()) return
            val text = richTextOf(loose)
            if (!text.isBlankText()) out += ArticleBlock.Paragraph(text)
            loose.clear()
        }
        for (node in parent.childNodes()) {
            if (node === skip) continue
            if (node is TextNode) {
                loose += node
                continue
            }
            if (node !is Element || isSkipped(node)) continue
            if (isInline(node)) {
                loose.add(node)
            } else {
                flush()
                block(node, out)
            }
        }
        flush()
    }

    private fun block(el: Element, out: MutableList<ArticleBlock>) {
        val tag = el.normalName()
        val classes = el.classNames()
        when {
            "gh-paid-content-notice" in classes -> out += membersOnly(el)
            "kg-gallery-card" in classes -> out.addIfNotNull(gallery(el))
            "kg-bookmark-card" in classes -> out.addIfNotNull(bookmark(el))
            "kg-callout-card" in classes -> out.addIfNotNull(callout(el, classes))
            "kg-button-card" in classes -> out.addIfNotNull(button(el.selectFirst("a[href]")))
            "kg-toggle-card" in classes -> out.addIfNotNull(toggleCard(el))
            "kg-audio-card" in classes -> out.addIfNotNull(audio(el))
            "kg-video-card" in classes -> out.addIfNotNull(video(el))
            "kg-embed-card" in classes -> embedCard(el, out)
            "kg-file-card" in classes -> out.addIfNotNull(file(el))
            "kg-header-card" in classes -> headerCard(el, out)
            "kg-product-card" in classes -> productCard(el, out)
            "kg-image-card" in classes -> figure(el, out)
            tag == "a" && classes.any { it in BUTTON_CLASSES } -> out.addIfNotNull(button(el))
            else -> when (tag) {
                "p" ->
                    if (el.selectFirst(MEDIA_SELECTOR) != null) {
                        collect(el, null, out)
                    } else {
                        val text = richText(el)
                        if (!text.isBlankText()) out += ArticleBlock.Paragraph(text)
                    }
                "h1", "h2", "h3", "h4", "h5", "h6" -> heading(el, tag[1].digitToInt(), out)
                "blockquote" -> {
                    val text = richText(el)
                    if (!text.isBlankText()) out += ArticleBlock.Quote(text, "kg-blockquote-alt" in classes)
                }
                "ul", "ol" -> {
                    val entries = ArrayList<ListEntry>()
                    list(el, 0, entries)
                    if (entries.isNotEmpty()) out += ArticleBlock.BulletList(entries)
                }
                "pre" -> out.addIfNotNull(code(el))
                "hr" -> out += ArticleBlock.Divider
                "figure" -> figure(el, out)
                "img" -> image(el)?.let { out += ArticleBlock.Image(it, null, widthOf(classes), linkOf(el)) }
                "a" -> linkedImage(el, out)
                "table" -> out.addIfNotNull(table(el))
                "details" -> details(el, out)
                "audio" -> out.addIfNotNull(audio(el))
                "video" -> out.addIfNotNull(video(el))
                "iframe" -> out.addIfNotNull(embed(el, null))
                else -> collect(el, null, out)
            }
        }
    }

    /** Ghost's gate notice: a heading, a paragraph and a link to the Portal's sign-up. */
    private fun membersOnly(el: Element): ArticleBlock.MembersOnly = ArticleBlock.MembersOnly(
        title = el.selectFirst("h1, h2, h3, h4, h5, h6")?.text()?.trim()?.takeIf { it.isNotEmpty() },
        message = el.selectFirst("p")?.text()?.trim()?.takeIf { it.isNotEmpty() },
    )

    private fun heading(el: Element, level: Int, out: MutableList<ArticleBlock>) {
        val text = richText(el)
        if (!text.isBlankText()) out += ArticleBlock.Heading(level, text)
    }

    /** Flattens nested lists in reading order; [depth] is 0 for the outermost list. */
    private fun list(listEl: Element, depth: Int, entries: MutableList<ListEntry>) {
        val ordered = listEl.normalName() == "ol"
        var number = listEl.attr("start").trim().toIntOrNull() ?: 1
        for (child in listEl.children()) {
            when (child.normalName()) {
                "li" -> {
                    child.attr("value").trim().toIntOrNull()?.let { number = it }
                    val marker = if (ordered) "${number++}." else "•"
                    val text = richTextOf(child.childNodes().filter { !(it is Element && it.normalName() in LIST_TAGS) })
                    if (!text.isBlankText()) entries += ListEntry(text, depth, marker)
                    child.children().filter { it.normalName() in LIST_TAGS }.forEach { list(it, depth + 1, entries) }
                }
                "ul", "ol" -> list(child, depth + 1, entries)
            }
        }
    }

    private fun code(pre: Element): ArticleBlock.Code? {
        val raw = StringBuilder().also { appendVerbatim(pre, it) }.toString().trimStart('\n').trimEnd()
        if (raw.isBlank()) return null
        val classes = pre.selectFirst("code")?.classNames().orEmpty() + pre.classNames()
        val language = classes.firstNotNullOfOrNull { CODE_LANGUAGE.matchEntire(it)?.groupValues?.get(1) }
        return ArticleBlock.Code(raw, language)
    }

    /** Text with its whitespace intact, `<br>` as a line break. */
    private fun appendVerbatim(node: Node, out: StringBuilder) {
        when {
            node is TextNode -> out.append(node.wholeText)
            node is Element && node.normalName() == "br" -> out.append('\n')
            else -> node.childNodes().forEach { appendVerbatim(it, out) }
        }
    }

    private fun image(img: Element): ArticleImage? {
        val url = resolve(img.attr("src").ifBlank { img.attr("data-src") }) ?: return null
        return ArticleImage(url, img.attr("alt").trim().ifEmpty { null }, dimension(img, "width"), dimension(img, "height"))
    }

    private fun dimension(img: Element, attr: String): Int? =
        img.attr(attr).trim().removeSuffix("px").toIntOrNull()?.takeIf { it > 0 }

    /** The `<a>` wrapping [img], if any. */
    private fun linkOf(img: Element): String? =
        img.parent()?.takeIf { it.normalName() == "a" }?.let { resolve(it.attr("href")) }

    /** An `<a>` around an image and no text is a linked image; with other content it is just a container. */
    private fun linkedImage(a: Element, out: MutableList<ArticleBlock>) {
        val img = a.selectFirst("img")
        if (img != null && a.text().isBlank()) {
            image(img)?.let { out += ArticleBlock.Image(it, null, widthOf(img.classNames()), resolve(a.attr("href"))) }
        } else {
            collect(a, null, out)
        }
    }

    private fun caption(card: Element): AnnotatedString? =
        card.selectFirst("figcaption")?.let { richText(it) }?.takeUnless { it.isBlankText() }

    /** `figure.kg-image-card`, or any figure with images; the caption goes under the last image. */
    private fun figure(el: Element, out: MutableList<ArticleBlock>) {
        val imgs = el.select("img")
        if (imgs.isEmpty()) {
            collect(el, null, out)
            return
        }
        val width = widthOf(el.classNames())
        val caption = caption(el)
        val items = imgs.mapNotNull { img -> image(img)?.let { it to linkOf(img) } }
        items.forEachIndexed { index, (image, link) ->
            out += ArticleBlock.Image(image, if (index == items.lastIndex) caption else null, width, link)
        }
    }

    private fun gallery(el: Element): ArticleBlock.Gallery? {
        var rows = el.select(".kg-gallery-row").map { row -> row.select("img").mapNotNull { image(it) } }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) rows = listOf(el.select("img").mapNotNull { image(it) }).filter { it.isNotEmpty() }
        return if (rows.isEmpty()) null else ArticleBlock.Gallery(rows, caption(el))
    }

    /**
     * Koenig names the two byline spans the wrong way round for theme compatibility:
     * `kg-bookmark-author` holds the publisher and `kg-bookmark-publisher` the author.
     */
    private fun bookmark(el: Element): ArticleBlock.Bookmark? {
        val link = el.selectFirst("a.kg-bookmark-container") ?: el.selectFirst("a[href]") ?: return null
        val url = resolve(link.attr("href")) ?: return null
        return ArticleBlock.Bookmark(
            url = url,
            title = el.textOf(".kg-bookmark-title") ?: url,
            description = el.textOf(".kg-bookmark-description"),
            publisher = el.textOf(".kg-bookmark-author"),
            author = el.textOf(".kg-bookmark-publisher"),
            iconUrl = resolve(el.selectFirst("img.kg-bookmark-icon")?.attr("src")),
            thumbnailUrl = resolve(el.selectFirst(".kg-bookmark-thumbnail img")?.attr("src")),
        )
    }

    private fun callout(el: Element, classes: Set<String>): ArticleBlock.Callout? {
        val text = el.selectFirst(".kg-callout-text")?.let { richText(it) } ?: return null
        if (text.isBlankText()) return null
        val color = classes.firstOrNull { it.startsWith("kg-callout-card-") }?.removePrefix("kg-callout-card-")
        return ArticleBlock.Callout(el.textOf(".kg-callout-emoji"), text, color)
    }

    private fun button(a: Element?): ArticleBlock.Button? {
        if (a == null) return null
        val text = a.text()
        val url = resolve(a.attr("href"))
        return if (text.isBlank() || url == null) null else ArticleBlock.Button(text, url)
    }

    private fun toggleCard(el: Element): ArticleBlock.Toggle? {
        val heading = el.selectFirst(".kg-toggle-heading-text")?.let { richText(it) } ?: AnnotatedString("")
        val content = el.selectFirst(".kg-toggle-content")?.let { blocks(it) }.orEmpty()
        return if (heading.isBlankText() && content.isEmpty()) null else ArticleBlock.Toggle(heading, content)
    }

    /** Without a `<summary>` there is nothing to tap, so the contents are shown as they are. */
    private fun details(el: Element, out: MutableList<ArticleBlock>) {
        val summary = el.children().firstOrNull { it.normalName() == "summary" }
        if (summary == null) {
            collect(el, null, out)
            return
        }
        val content = ArrayList<ArticleBlock>().also { collect(el, summary, it) }
        out += ArticleBlock.Toggle(richText(summary), content)
    }

    private fun mediaUrl(el: Element): String? =
        resolve(el.attr("src").ifBlank { el.selectFirst("source[src]")?.attr("src").orEmpty() })

    /** Audio card or bare `<audio>`. */
    private fun audio(el: Element): ArticleBlock.Audio? {
        val audio = if (el.normalName() == "audio") el else el.selectFirst("audio") ?: return null
        val url = mediaUrl(audio) ?: return null
        val title = el.textOf(".kg-audio-title") ?: audio.attr("title").trim().ifEmpty { null }
        return ArticleBlock.Audio(url, title)
    }

    /** Video card or bare `<video>`; Ghost keeps the thumbnail as a CSS background on the element. */
    private fun video(el: Element): ArticleBlock.Video? {
        val video = if (el.normalName() == "video") el else el.selectFirst("video") ?: return null
        val url = mediaUrl(video) ?: return null
        val poster = resolve(video.attr("poster"))
            ?: resolve(STYLE_URL.find(video.attr("style"))?.groupValues?.get(2))
        return ArticleBlock.Video(url, poster, caption(el))
    }

    /** Embed cards hold an `<iframe>`, or the markup of a social post (a `<blockquote>`), which is just read as text. */
    private fun embedCard(el: Element, out: MutableList<ArticleBlock>) {
        val iframe = el.selectFirst("iframe")
        if (iframe == null) collect(el, null, out) else out.addIfNotNull(embed(iframe, caption(el)))
    }

    private fun embed(iframe: Element, caption: AnnotatedString?): ArticleBlock.Embed? {
        val src = resolve(iframe.attr("src").ifBlank { iframe.attr("data-src") }) ?: return null
        val uri = runCatching { URI(src) }.getOrNull()
        val host = uri?.host?.lowercase()?.removePrefix("www.").orEmpty()
        if (uri != null) {
            youtubeId(host, uri)?.let {
                return ArticleBlock.Embed("https://www.youtube.com/watch?v=$it", "YouTube", "https://i.ytimg.com/vi/$it/hqdefault.jpg", caption)
            }
            vimeoId(host, uri)?.let { return ArticleBlock.Embed("https://vimeo.com/$it", "Vimeo", null, caption) }
        }
        return ArticleBlock.Embed(src, host.ifEmpty { null }, null, caption)
    }

    private fun youtubeId(host: String, uri: URI): String? {
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val id = when (host) {
            "youtu.be" -> segments.firstOrNull()
            "youtube.com", "m.youtube.com", "youtube-nocookie.com" -> when (segments.firstOrNull()) {
                "embed", "v", "shorts", "live" -> segments.getOrNull(1)
                "watch" -> uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.substringAfter('=')
                else -> null
            }
            else -> null
        }
        return id?.takeIf { VIDEO_ID.matches(it) && it != "videoseries" }
    }

    private fun vimeoId(host: String, uri: URI): String? {
        if (host != "vimeo.com" && host != "player.vimeo.com") return null
        return uri.path.orEmpty().split('/').firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    private fun file(el: Element): ArticleBlock.File? {
        val link = el.selectFirst("a[href]") ?: return null
        val url = resolve(link.attr("href")) ?: return null
        val name = el.textOf(".kg-file-card-title")
            ?: el.textOf(".kg-file-card-filename")
            ?: url.substringBefore('?').substringAfterLast('/')
        return ArticleBlock.File(url, name, el.textOf(".kg-file-card-caption"), el.textOf(".kg-file-card-filesize"))
    }

    /** Both header card versions: v1 names its parts `-header`/`-subheader`, v2 `-heading`/`-subheading`. */
    private fun headerCard(el: Element, out: MutableList<ArticleBlock>) {
        el.selectFirst(".kg-header-card-heading, .kg-header-card-header")?.let { heading ->
            val text = richText(heading)
            if (!text.isBlankText()) out += ArticleBlock.Heading(headingLevel(heading.normalName()) ?: 2, text)
        }
        el.selectFirst(".kg-header-card-subheading, .kg-header-card-subheader")?.let { sub ->
            val text = richText(sub)
            if (!text.isBlankText()) out += ArticleBlock.Paragraph(text)
        }
        out.addIfNotNull(button(el.selectFirst("a.kg-header-card-button")))
    }

    /** The star rating is dropped; the reader has no stars to draw. */
    private fun productCard(el: Element, out: MutableList<ArticleBlock>) {
        el.selectFirst("img.kg-product-card-image")?.let { img ->
            image(img)?.let { out += ArticleBlock.Image(it, null, ImageWidth.Regular, null) }
        }
        el.selectFirst(".kg-product-card-title")?.let { title ->
            val text = richText(title)
            if (!text.isBlankText()) out += ArticleBlock.Heading(headingLevel(title.normalName()) ?: 4, text)
        }
        el.selectFirst(".kg-product-card-description")?.let { collect(it, null, out) }
        out.addIfNotNull(button(el.selectFirst("a.kg-product-card-button")))
    }

    /** `<thead>` rows are header rows; without one, a first row made only of `<th>` is. */
    private fun table(el: Element): ArticleBlock.Table? {
        val head = ArrayList<Element>()
        val body = ArrayList<Element>()
        for (child in el.children()) {
            when (child.normalName()) {
                "thead" -> head += child.children().filter { it.normalName() == "tr" }
                "tbody", "tfoot" -> body += child.children().filter { it.normalName() == "tr" }
                "tr" -> body.add(child)
            }
        }
        fun cellsOf(rows: List<Element>) =
            rows.map { row -> row.children().filter { it.normalName() == "td" || it.normalName() == "th" } }.filter { it.isNotEmpty() }
        val headCells = cellsOf(head)
        val bodyCells = cellsOf(body)
        val rows = (headCells + bodyCells).map { row -> row.map { richText(it) } }
        if (rows.all { row -> row.all { it.isBlankText() } }) return null
        val headerRows = when {
            headCells.isNotEmpty() -> headCells.size
            bodyCells.firstOrNull()?.all { it.normalName() == "th" } == true -> 1
            else -> 0
        }
        return ArticleBlock.Table(rows, headerRows)
    }

    private companion object {
        val LIST_TAGS = setOf("ul", "ol")
    }
}

/**
 * Builds one [AnnotatedString] from inline nodes, collapsing whitespace the way a browser does:
 * runs become one space and the ends are trimmed. Block elements inside (a quote's paragraphs,
 * a list item's `<p>`) are separated by a blank line.
 */
private class InlineWriter(private val resolve: (String?) -> String?) {
    private val out = AnnotatedString.Builder()
    private var lineStart = true
    private var lastWasSpace = false
    private var trailingNewlines = 0

    fun write(nodes: List<Node>) = nodes.forEach { node(it) }

    fun build(): AnnotatedString {
        val text = out.toAnnotatedString()
        val end = text.text.indexOfLast { !it.isCollapsible() } + 1
        return if (end == text.length) text else text.subSequence(0, end)
    }

    private fun node(node: Node) {
        when (node) {
            is TextNode -> text(node.wholeText)
            is Element -> element(node)
        }
    }

    private fun text(raw: String) {
        val chunk = StringBuilder(raw.length)
        for (c in raw) {
            if (c.isCollapsible()) {
                if (!lineStart && !lastWasSpace) {
                    chunk.append(' ')
                    lastWasSpace = true
                }
            } else {
                chunk.append(c)
                lineStart = false
                lastWasSpace = false
                trailingNewlines = 0
            }
        }
        if (chunk.isNotEmpty()) out.append(chunk.toString())
    }

    private fun newline() {
        if (out.length == 0) return
        out.append("\n")
        trailingNewlines++
        lineStart = true
        lastWasSpace = false
    }

    private fun blockBreak() {
        while (out.length > 0 && trailingNewlines < 2) newline()
    }

    private fun children(el: Element) = write(el.childNodes())

    private fun styled(style: SpanStyle, el: Element) = out.withStyle(style) { children(el) }

    private fun element(el: Element) {
        if (isSkipped(el)) return
        when (el.normalName()) {
            "br" -> newline()
            "img", "wbr" -> Unit
            "strong", "b" -> styled(SpanStyle(fontWeight = FontWeight.Bold), el)
            "em", "i", "cite", "dfn" -> styled(SpanStyle(fontStyle = FontStyle.Italic), el)
            "u", "ins" -> styled(SpanStyle(textDecoration = TextDecoration.Underline), el)
            "s", "del", "strike" -> styled(SpanStyle(textDecoration = TextDecoration.LineThrough), el)
            "code", "kbd", "samp", "tt" -> styled(SpanStyle(fontFamily = FontFamily.Monospace), el)
            "sup" -> styled(SpanStyle(baselineShift = BaselineShift.Superscript), el)
            "sub" -> styled(SpanStyle(baselineShift = BaselineShift.Subscript), el)
            "a" -> link(el)
            in TEXT_BLOCK_TAGS -> {
                blockBreak()
                children(el)
                blockBreak()
            }
            else -> children(el)
        }
    }

    /** Links carry no styling; the reader colours them. In-page anchors have nowhere to go, so they stay text. */
    private fun link(el: Element) {
        val href = el.attr("href")
        val url = if (href.trim().startsWith("#")) null else resolve(href)
        if (url == null) children(el) else out.withLink(LinkAnnotation.Url(url)) { children(el) }
    }
}
