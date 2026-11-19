package co.magicpages.socialweb.data.linkpreview

import co.magicpages.socialweb.data.ghost.parseWebUri
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** What a page looks like as a link. [url] is where the page really is, after redirects. */
data class LinkPreview(
    val url: String,
    val title: String,
    val description: String?,
    /** An absolute http(s) address. */
    val imageUrl: String?,
    val siteName: String,
)

private const val MaxDescriptionChars = 300
private val whitespace = Regex("\\s+")

/**
 * The card for the page [html] at [pageUrl]: Open Graph first, then Twitter card tags, then the
 * plain `<title>` and description. Null when the page has no title, as a card of just an image
 * says nothing.
 */
internal fun parseLinkPreview(html: String, pageUrl: String): LinkPreview? {
    val document = Jsoup.parse(html, pageUrl)
    val title = document.metaText("og:title", "twitter:title")
        ?: document.selectFirst("title")?.text()?.clean()
        ?: return null
    return LinkPreview(
        url = pageUrl,
        title = title,
        description = document.metaText("og:description", "twitter:description", "description")?.take(MaxDescriptionChars),
        imageUrl = document.metaImage("og:image", "og:image:secure_url", "og:image:url", "twitter:image", "twitter:image:src"),
        siteName = document.metaText("og:site_name") ?: parseWebUri(pageUrl)?.host?.removePrefix("www.").orEmpty(),
    )
}

/** The first of [names] the page sets (as `property` or `name`) to something other than blanks. */
private fun Document.metaText(vararg names: String): String? =
    names.firstNotNullOfOrNull { name ->
        select("meta[property=\"$name\"], meta[name=\"$name\"]").firstNotNullOfOrNull { it.attr("content").clean() }
    }

/** Like [metaText], resolved against the page's address; anything but http(s) (a `data:` image) is no image. */
private fun Document.metaImage(vararg names: String): String? =
    names.firstNotNullOfOrNull { name ->
        select("meta[property=\"$name\"], meta[name=\"$name\"]").firstNotNullOfOrNull { meta ->
            // absUrl reads `content` against the page's base URI, so `/a.png`, `a.png` and `//cdn/a.png` all work.
            meta.absUrl("content").trim().takeIf { parseWebUri(it) != null }
        }
    }

private fun String.clean(): String? = replace(whitespace, " ").trim().takeIf { it.isNotEmpty() }
