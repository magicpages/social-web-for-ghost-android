package co.magicpages.socialweb.ui.quote

import co.magicpages.socialweb.data.activitypub.ImageRejection
import java.io.ByteArrayOutputStream

/**
 * What a share (`ACTION_SEND`) holds, split for the note composer: the page it links to, the words
 * the sender selected on it, and the page's title; or one image, with or without such text.
 */
data class SharedContent(
    /** The page's address without a text-fragment directive; null when the share has no link. */
    val url: String?,
    /** The words selected on the page, for a quote card; null when the share holds none. */
    val quote: String?,
    /** The page's title when the sharing app sent one and it isn't the quoted words. */
    val title: String?,
    /** A share with no link: text to start the note with, as it is. */
    val plainText: String?,
    /** A shared image, already copied into the app's cache; null when the share has none or it was turned away. */
    val imageUri: String? = null,
    /** Why the shared image can't go on the note; shown in the composer. */
    val imageRejection: ImageRejection? = null,
) {
    /** The page's domain without `www.`, as quote cards credit it. */
    val host: String? get() = url?.let(::hostOf)

    companion object {
        /**
         * [text] is the share's `EXTRA_TEXT`, [subject] its `EXTRA_SUBJECT` or `EXTRA_TITLE`. Null when
         * there is nothing to write about.
         *
         * Sharing apps put the link at the end of the text, or share the link alone. A link may carry
         * the selection itself as a text fragment (`#:~:text=`), which is what Chrome's "Copy link to
         * highlight" makes. Text next to the link is the selection, unless it only repeats the title.
         */
        fun parse(text: String?, subject: String?): SharedContent? {
            val body = text?.trim().orEmpty()
            if (body.isEmpty()) return null
            val title = subject?.let(::tidyLines)?.takeIf { it.isNotEmpty() }
            val link = findLink(body) ?: return SharedContent(url = null, quote = null, title = title, plainText = body)

            val around = tidySelection(body.removeRange(link.range))
            val isTitle = title != null && around.equals(title, ignoreCase = true)
            val quote = around.takeIf { it.isNotEmpty() && !isTitle } ?: TextFragment.quote(link.url)
            return SharedContent(
                url = TextFragment.withoutDirective(link.url),
                quote = quote,
                title = title?.takeUnless { it.equals(quote, ignoreCase = true) },
                plainText = null,
            )
        }
    }
}

/** The domain of a web address, lower case and without `www.`; null for anything else. */
internal fun hostOf(url: String): String? =
    HostPattern.find(url)?.groupValues?.get(1)?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

private val HostPattern = Regex("""^https?://(?:[^@/?#]*@)?([^/?#:]+)""", RegexOption.IGNORE_CASE)

private class Link(val url: String, val range: IntRange)

private val LinkPattern = Regex("""https?://[^\s<>"“”«»]+""", RegexOption.IGNORE_CASE)

/**
 * The link that is being shared: the one the text starts with, otherwise the last one. Its [Link.range]
 * covers the punctuation that ended the sentence too, so none of it is left behind in the words.
 */
private fun findLink(text: String): Link? {
    val matches = LinkPattern.findAll(text).toList()
    val match = matches.firstOrNull()?.takeIf { it.range.first == 0 } ?: matches.lastOrNull() ?: return null
    return Link(trimLinkEnd(match.value), match.range)
}

/**
 * Drops what the sentence around a link adds to its end. A text fragment may end in a full stop or
 * a comma that belongs to the quote, so links that carry one are left as they are.
 */
private fun trimLinkEnd(raw: String): String {
    if (":~:" in raw) return raw
    var url = raw
    while (url.isNotEmpty()) {
        val last = url.last()
        val drop = when (last) {
            '.', ',', ';', ':', '!', '?', '\'', '’', '…' -> true
            ')' -> url.count { it == ')' } > url.count { it == '(' }
            ']' -> url.count { it == ']' } > url.count { it == '[' }
            else -> false
        }
        if (!drop) break
        url = url.dropLast(1)
    }
    return url
}

private const val Openers = "\"“‘'«„"
private const val Closers = "\"”’'»“"
private const val Separators = "—–-:|·•"

/**
 * The words around the link: whitespace tidied (line breaks kept, at most one blank line in a row),
 * separators before the link dropped, and one pair of quotation marks around the whole removed.
 */
private fun tidySelection(text: String): String {
    var tidy = tidyLines(text).trimEnd { it.isWhitespace() || it in Separators }
    if (tidy.length >= 2 && tidy.first() in Openers && tidy.last() in Closers) {
        tidy = tidy.substring(1, tidy.length - 1).trim()
    }
    return tidy
}

private fun tidyLines(text: String): String {
    val lines = mutableListOf<String>()
    for (raw in text.lines()) {
        val line = raw.split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
        if (line.isEmpty() && (lines.isEmpty() || lines.last().isEmpty())) continue
        lines += line
    }
    return lines.joinToString("\n").trim()
}

/**
 * Reads links made with "Copy link to highlight": the highlighted text travels in the address as a
 * text fragment, `https://example.com/page#anchor:~:text=[prefix-,]start[,end][,-suffix]`, with
 * several `text=` directives joined by `&`. Every part is percent-encoded, so commas, dashes and
 * ampersands that belong to the text arrive as `%2C`, `%2D` and `%26`.
 */
internal object TextFragment {
    private const val Marker = ":~:"
    private const val Gap = " … "

    /**
     * The highlighted words. A range highlight (`start,end`) carries only its first and last words
     * and reads "start … end"; several highlights are joined the same way. Null without a highlight.
     */
    fun quote(url: String): String? {
        val directive = directive(url) ?: return null
        val quotes = directive.split('&').mapNotNull { item ->
            parts(item).takeIf { it.isNotEmpty() }?.joinToString(Gap)
        }
        return quotes.takeIf { it.isNotEmpty() }?.joinToString(Gap)
    }

    /** The address without its `:~:` directive; an ordinary `#anchor` before it stays. */
    fun withoutDirective(url: String): String {
        val hash = url.indexOf('#')
        if (hash < 0) return url
        val fragment = url.substring(hash + 1)
        val marker = fragment.indexOf(Marker)
        if (marker < 0) return url
        val anchor = fragment.substring(0, marker)
        return url.substring(0, hash) + if (anchor.isEmpty()) "" else "#$anchor"
    }

    private fun directive(url: String): String? {
        val hash = url.indexOf('#')
        if (hash < 0) return null
        val fragment = url.substring(hash + 1)
        val marker = fragment.indexOf(Marker)
        return if (marker < 0) null else fragment.substring(marker + Marker.length)
    }

    /** The start (and end) words of a `text=` directive, without its prefix and suffix context. */
    private fun parts(item: String): List<String> {
        if (!item.startsWith("text=")) return emptyList()
        val terms = item.removePrefix("text=").split(',').toMutableList()
        // The context is told apart from the words by a dash, before it is decoded.
        if (terms.size > 1 && terms.first().endsWith("-")) terms.removeAt(0)
        if (terms.size > 1 && terms.last().startsWith("-")) terms.removeAt(terms.lastIndex)
        if (terms.size > 2) return emptyList()
        return terms.map { percentDecode(it).trim() }.filter { it.isNotEmpty() }
    }

    /** Unlike form decoding, a `+` stays a plus. */
    private fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val out = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val code = if (text[i] == '%' && i + 2 < text.length) hexByte(text[i + 1], text[i + 2]) else -1
            if (code >= 0) {
                out.write(code)
                i += 3
            } else {
                val point = text.codePointAt(i)
                out.write(String(Character.toChars(point)).toByteArray(Charsets.UTF_8))
                i += Character.charCount(point)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hexByte(high: Char, low: Char): Int {
        val h = Character.digit(high, 16)
        val l = Character.digit(low, 16)
        return if (h < 0 || l < 0) -1 else h * 16 + l
    }
}
