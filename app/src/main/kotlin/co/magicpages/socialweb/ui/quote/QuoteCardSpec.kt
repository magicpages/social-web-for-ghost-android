package co.magicpages.socialweb.ui.quote

import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.ui.components.splitQuote
import java.time.Instant
import java.time.format.DateTimeParseException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** What a quote card shows. The renderer draws it; nothing here depends on Android. */
sealed interface QuoteCardSpec

/** A post, as the author, the words and the first picture. Posts hidden behind a warning show only that. */
data class PostQuote(
    val authorName: String,
    val handle: String,
    val avatarUrl: String?,
    /** An article's title; null for notes. */
    val title: String?,
    /** Plain text with paragraph breaks: a note's words, or an article's summary. */
    val body: String,
    val imageUrl: String?,
    /** The post is content-warned or sensitive: its words and picture are left out. */
    val warned: Boolean,
    /** The author's own content warning, when there is one. */
    val warning: String?,
    val publishedAtMillis: Long?,
    /** The server the post lives on, as the card credits it. */
    val sourceHost: String?,
) : QuoteCardSpec

/** Words selected on a web page, with where they came from. */
data class TextQuote(
    val text: String,
    val title: String?,
    val host: String?,
) : QuoteCardSpec

/** The card for [post]. Parses HTML, so call it off the main thread. */
fun postQuote(post: Post): PostQuote {
    val warning = post.contentWarning?.takeIf { it.isNotBlank() }?.trim()
    val warned = post.sensitive || warning != null
    val summary = post.summary?.takeIf { it.isNotBlank() } ?: post.excerpt
    return PostQuote(
        authorName = post.author.name.ifBlank { post.author.handle },
        handle = post.author.handle,
        avatarUrl = post.author.avatarUrl?.takeIf { it.isNotBlank() },
        title = if (post.isArticle && !warned) post.title.trim().ifEmpty { null } else null,
        body = when {
            warned -> ""
            post.isArticle -> summary.trim()
            else -> htmlToPlainText(splitQuote(post.content).first)
        },
        imageUrl = if (warned) null else postImageUrl(post),
        warned = warned,
        warning = warning,
        publishedAtMillis = post.publishedAt?.let {
            try {
                Instant.parse(it).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        },
        sourceHost = hostOf(post.url) ?: post.author.handle.substringAfterLast('@', "").ifEmpty { null },
    )
}

private fun postImageUrl(post: Post): String? =
    (if (post.isArticle) post.featureImageUrl?.takeIf { it.isNotBlank() } else null)
        ?: post.images.firstOrNull()?.url

/** The card for words shared from a page. */
fun textQuote(content: SharedContent, quote: String): TextQuote =
    TextQuote(text = quote, title = content.title, host = content.host)

private val Blocks = setOf("p", "div", "blockquote", "pre", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6")

/**
 * A post's HTML as the words a reader sees: paragraphs and line breaks kept, the invisible parts of
 * shortened links dropped, entities decoded.
 */
internal fun htmlToPlainText(html: String): String {
    val out = StringBuilder()

    fun breakAfter(newlines: Int) {
        if (out.isEmpty()) return
        while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        var present = 0
        while (present < out.length && out[out.length - 1 - present] == '\n') present++
        repeat((newlines - present).coerceAtLeast(0)) { out.append('\n') }
    }

    fun walk(node: Node) {
        when (node) {
            is TextNode -> out.append(node.text())
            is Element -> {
                val tag = node.normalName()
                when {
                    tag == "script" || tag == "style" || node.hasClass("invisible") -> return
                    tag == "br" -> out.append('\n')
                    tag == "li" -> {
                        breakAfter(1)
                        out.append("• ")
                        node.childNodes().forEach(::walk)
                        breakAfter(1)
                    }
                    else -> {
                        val block = tag in Blocks
                        if (block) breakAfter(2)
                        node.childNodes().forEach(::walk)
                        // Mastodon cuts long links short and shows the cut as a CSS ellipsis.
                        if (node.hasClass("ellipsis")) out.append('…')
                        if (block) breakAfter(2)
                    }
                }
            }
        }
    }

    Jsoup.parseBodyFragment(html).body().childNodes().forEach(::walk)
    return out.toString()
        .replace('\u00A0', ' ')
        .lines()
        .joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** [text] cut to at most [max] characters at a word end, with an ellipsis; unchanged when short enough. */
internal fun ellipsize(text: String, max: Int): String {
    if (text.length <= max) return text
    val cut = text.substring(0, max)
    val atWord = cut.lastIndexOfAny(charArrayOf(' ', '\n'))
    return (if (atWord > max / 2) cut.substring(0, atWord) else cut).trimEnd() + "…"
}
