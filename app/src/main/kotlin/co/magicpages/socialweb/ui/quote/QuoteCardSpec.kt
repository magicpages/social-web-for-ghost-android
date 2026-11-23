package co.magicpages.socialweb.ui.quote

import co.magicpages.socialweb.data.activitypub.CustomEmoji
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.linkpreview.isPreviewableUrl
import co.magicpages.socialweb.ui.components.emojiShortcodes
import co.magicpages.socialweb.ui.components.emojiSourceId
import co.magicpages.socialweb.ui.components.splitQuote
import co.magicpages.socialweb.ui.media.videos
import co.magicpages.socialweb.ui.posts.isSameLink
import java.time.Instant
import java.time.format.DateTimeParseException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** What a quote card shows. The renderer draws it; nothing here depends on Android. */
sealed interface QuoteCardSpec

/** A video's still: the picture it shows before it plays, which is all a card can hold of it. */
data class QuoteVideo(
    /** Null when the video names no poster; the card then draws a plain frame. */
    val posterUrl: String?,
    /** Width over height, when the attachment says. */
    val aspectRatio: Float?,
)

/**
 * A post, as the author, the words and the first picture. Posts hidden behind a warning show only that.
 * What the timeline row shows beyond that (a quoted post, a link card, emoji) is named here by address and
 * fetched by the renderer, which has to wait for it anyway.
 */
data class PostQuote(
    val authorName: String,
    val handle: String,
    val avatarUrl: String?,
    /** An article's title; null for notes. */
    val title: String?,
    /** Plain text with paragraph breaks: a note's words, or an article's summary. */
    val body: String,
    val imageUrl: String?,
    /** A note's first video, drawn as its still below the picture. */
    val video: QuoteVideo?,
    /** The post is content-warned or sensitive: its words and picture are left out. */
    val warned: Boolean,
    /** The author's own content warning, when there is one. */
    val warning: String?,
    val publishedAtMillis: Long?,
    /** The server the post lives on, as the card credits it. */
    val sourceHost: String?,
    /** The post this one quotes; drawn as that post in a box. Its "RE:" line is not part of [body]. */
    val quotedUrl: String?,
    /** The page the words link to first, drawn as a link card; null when media, a quote or an article's own card already say what the post is about. */
    val linkUrl: String?,
    /** Where the author's name emoji can be looked up. */
    val authorEmojiSource: String?,
    /** Where the body's emoji can be looked up; null for articles, whose summary is plain text in the timeline too. */
    val emojiSource: String?,
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
    // A quote post's "RE:" line is the quoted post in the card's box, not words of its own.
    val (noteHtml, quotedUrl) = if (post.isArticle) post.content to null else splitQuote(post.content)
    val openNote = !warned && !post.isArticle
    return PostQuote(
        authorName = post.author.name.ifBlank { post.author.handle },
        handle = post.author.handle,
        avatarUrl = post.author.avatarUrl?.takeIf { it.isNotBlank() },
        title = if (post.isArticle && !warned) post.title.trim().ifEmpty { null } else null,
        body = when {
            warned -> ""
            post.isArticle -> summary.trim()
            else -> htmlToPlainText(noteHtml)
        },
        imageUrl = if (warned) null else postImageUrl(post),
        video = if (openNote) post.videos.firstOrNull()?.let { QuoteVideo(it.posterUrl, it.aspectRatio) } else null,
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
        quotedUrl = quotedUrl.takeIf { openNote },
        // The timeline's own rule: media, a quoted post and an article's card already say what the post is about.
        linkUrl = if (openNote && post.attachments.isEmpty() && quotedUrl == null) firstPreviewLink(noteHtml, post) else null,
        authorEmojiSource = post.author.emojiSourceId,
        emojiSource = post.id.takeIf { openNote && it.isNotBlank() },
    )
}

private fun postImageUrl(post: Post): String? =
    (if (post.isArticle) post.featureImageUrl?.takeIf { it.isNotBlank() } else null)
        ?: post.images.firstOrNull()?.url

/**
 * The first link in [html] that leads to a page: mentions and hashtags are links too, and a link
 * back to the post itself would only preview what is already on the card.
 */
internal fun firstPreviewLink(html: String, post: Post): String? =
    Jsoup.parseBodyFragment(html).select("a[href]").firstNotNullOfOrNull { link ->
        val url = link.attr("href").trim()
        val label = link.text().trimStart()
        url.takeUnless {
            label.startsWith('@') || label.startsWith('#') || !isPreviewableUrl(it) ||
                it.isSameLink(post.url) || it.isSameLink(post.id)
        }
    }

/** A custom emoji's place in a text: [start] until [end] is its `:shortcode:`, to be drawn as the image at [url]. */
internal data class EmojiRun(val start: Int, val end: Int, val url: String)

/** Where [text] uses [emojis], in order. Shortcodes without an emoji stay text. */
internal fun emojiRuns(text: String, emojis: List<CustomEmoji>): List<EmojiRun> {
    val urls = emojis.associate { it.shortcode to it.url }
    return emojiShortcodes(text, emojis).map { EmojiRun(it.range.first, it.range.last + 1, urls.getValue(it.groupValues[1])) }
}

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
