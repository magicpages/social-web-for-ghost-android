package co.magicpages.socialweb.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Parses fediverse post HTML. Slow enough to drop frames when run per item while flinging,
 * so callers run it off the main thread; [HtmlText] only applies the theme's link colour.
 */
fun parsePostHtml(html: String): AnnotatedString =
    AnnotatedString.fromHtml(tidyFediverseHtml(html)).trimEnd()

/**
 * Splits off the "RE: <link>" line that Mastodon (`<p class="quote-inline">`) and Misskey
 * (`<span class="quote-inline">`) add to quote posts for apps that can't show quotes. Returns the
 * rest of the HTML and the quoted post's URL, or [html] unchanged and null when it isn't a quote.
 */
fun splitQuote(html: String): Pair<String, String?> {
    // Drop the link-shortening spans first, so a span-wrapped quote line ends at its own </span>.
    val tidied = html.replace(invisibleSpan, "").replace(ellipsisSpan, "$1…")
    val match = quoteInline.find(tidied) ?: return html to null
    val url = hrefAttr.find(match.groupValues[2])?.groupValues?.get(1)?.replace("&amp;", "&")
        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        ?: return html to null
    return tidied.removeRange(match.range) to url
}

/**
 * Renders text from [parsePostHtml]; links open through `LocalUriHandler`. [emojiSourceId] is the
 * ActivityPub id of the post or account the text belongs to, for its custom emoji.
 */
@Composable
fun HtmlText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    maxLines: Int = Int.MAX_VALUE,
    emojiSourceId: String? = null,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val emojis = rememberCustomEmojis(emojiSourceId, text.text)
    val styled = remember(text, linkColor, emojis) {
        text.withLinkStyles(TextLinkStyles(SpanStyle(color = linkColor))).withCustomEmojis(emojis)
    }
    val inline = remember(emojis) { emojiInlineContent(emojis) }
    Text(styled, modifier = modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, inlineContent = inline)
}

private val quoteInline = Regex(
    """<(p|span)\b[^>]*\bclass="[^"]*\bquote-inline\b[^"]*"[^>]*>(.*?)</\1>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val invisibleSpan = Regex("""<span class="invisible">[^<]*</span>""")
private val ellipsisSpan = Regex("""<span class="ellipsis">([^<]*)</span>""")
private val anchorTag = Regex("""<a\s[^>]*>""", RegexOption.IGNORE_CASE)
private val hrefAttr = Regex("""\bhref="([^"]*)"""", RegexOption.IGNORE_CASE)
private val classAttr = Regex("""\bclass="([^"]*)"""", RegexOption.IGNORE_CASE)
private val dataProfileAttr = Regex("""\bdata-profile="(@[^"@\s]+@[^"@\s]+)"""", RegexOption.IGNORE_CASE)

// Profile URLs whose handle can be read off the URL: Mastodon-style /@user and ActivityPub-style /users/user.
private val profileUrl = Regex("""^https?://([^/:?#]+)/(?:@|users/)([A-Za-z0-9_.-]+)/?$""")

/**
 * Mastodon hides URL schemes and tails in spans that browsers drop via CSS; drop them here too.
 * Mentions become [ProfileLinks] so they open the profile in the app.
 */
internal fun tidyFediverseHtml(html: String): String =
    html.replace(invisibleSpan, "").replace(ellipsisSpan, "$1…").replace(anchorTag) { linkMentions(it.value) }

private fun linkMentions(tag: String): String {
    val href = hrefAttr.find(tag) ?: return tag
    val url = href.groupValues[1].replace("&amp;", "&")
    val handle = dataProfileAttr.find(tag)?.groupValues?.get(1) ?: run {
        val classes = classAttr.find(tag)?.groupValues?.get(1)?.split(' ').orEmpty()
        if ("mention" !in classes || "hashtag" in classes) return tag
        val match = profileUrl.matchEntire(url) ?: return tag
        "@${match.groupValues[2]}@${match.groupValues[1]}"
    }
    return tag.replaceRange(href.range, "href=\"${ProfileLinks.create(handle, url)}\"")
}

/** In-app links to a fediverse profile, carrying the original URL for when the profile can't be loaded. */
object ProfileLinks {
    private const val PREFIX = "socialweb://profile/"

    // The Charset overloads need API 33; the name overloads work back to minSdk.
    fun create(handle: String, url: String?): String =
        PREFIX + URLEncoder.encode(handle, "UTF-8") + (url?.let { "?url=" + URLEncoder.encode(it, "UTF-8") } ?: "")

    /** The handle and original URL, or null when [uri] isn't a profile link. */
    fun parse(uri: String): Pair<String, String?>? {
        if (!uri.startsWith(PREFIX)) return null
        val rest = uri.removePrefix(PREFIX)
        val handle = URLDecoder.decode(rest.substringBefore('?'), "UTF-8")
        val url = rest.substringAfter("?url=", "").takeIf { it.isNotEmpty() }?.let { URLDecoder.decode(it, "UTF-8") }
        return handle to url
    }
}

private fun AnnotatedString.trimEnd(): AnnotatedString {
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    return if (end == text.length) this else subSequence(0, end)
}

private fun AnnotatedString.withLinkStyles(styles: TextLinkStyles): AnnotatedString {
    val links = getLinkAnnotations(0, length)
    if (links.isEmpty()) return this
    return buildAnnotatedString {
        append(AnnotatedString(text, spanStyles, paragraphStyles))
        for (link in links) {
            val url = link.item as? LinkAnnotation.Url ?: continue
            addLink(LinkAnnotation.Url(url.url, styles, url.linkInteractionListener), link.start, link.end)
        }
    }
}
