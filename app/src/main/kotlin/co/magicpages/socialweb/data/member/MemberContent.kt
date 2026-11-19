package co.magicpages.socialweb.data.member

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Members-only and paid Ghost posts reach the social web as their public part only, followed by the
 * service's "This post is for subscribers only" notice. A signed-in member's copy of the post page has
 * the whole post; this finds it there.
 */
object MemberContent {
    private const val NoticeClass = "gh-paid-content-notice"

    /** Whether [html] (a federated article's content) is only the public part of a gated post. */
    fun isPaywalled(html: String): Boolean = NoticeClass in html

    sealed interface Extraction {
        /** The post's HTML as the site rendered it for the member. */
        data class Full(val html: String) : Extraction

        /** The post couldn't be recognised in the page. */
        data object NotFound : Extraction

        /** The page has only the public part too: not signed in, or not on the post's tier. */
        data object Gated : Extraction
    }

    /**
     * Takes the post out of [pageHtml], the post page as the site sent it to the member. Ghost writes the post's HTML
     * into the page in one piece with no wrapper of its own (the theme decides what holds it: `.gh-content`,
     * `.post-content`, …), so it is found by its first paragraph: the public part [publicHtml] starts with the same
     * one, and that paragraph's parent holds the whole post.
     */
    fun extract(pageHtml: String, publicHtml: String): Extraction {
        val preview = previewBlocks(publicHtml)
        val first = preview.firstOrNull() ?: return Extraction.NotFound
        val firstText = first.normalizedText()
        val lastText = preview.last().normalizedText().take(MatchLength)

        var found: Element? = null
        for (candidate in Jsoup.parse(pageHtml).body().getElementsByTag(first.tagName())) {
            val text = candidate.normalizedText()
            if (text != firstText && text.take(MatchLength) != firstText.take(MatchLength)) continue
            val container = candidate.parent() ?: continue
            if (lastText !in container.normalizedText()) continue
            // Ghost's own class, when it is this one, is the surest sign.
            if (container.hasClass("gh-content")) {
                found = container
                break
            }
            if (found == null) found = container
        }
        val container = found ?: return Extraction.NotFound

        val previewLength = preview.sumOf { it.normalizedText().length }
        val stillGated = container.selectFirst(GatedSelector) != null
        if (stillGated || container.normalizedText().length <= previewLength + GatedMargin) return Extraction.Gated
        return Extraction.Full(container.html())
    }

    /** The public part's top-level blocks that hold some text, without the notice. */
    private fun previewBlocks(publicHtml: String): List<Element> =
        Jsoup.parseBodyFragment(publicHtml).body().children()
            .filter { !it.hasClass(NoticeClass) && it.normalizedText().length >= MinBlockLength }

    private fun Element.normalizedText(): String = text().replace(Whitespace, " ").trim()

    private val Whitespace = Regex("\\s+")

    /** Ghost's "for subscribers only" box, or a theme's own, inside what the member got. */
    private const val GatedSelector = ".gh-post-upgrade-cta, [data-portal=signup], [data-portal=\"account/plans\"]"

    private const val MatchLength = 80
    private const val MinBlockLength = 20

    /** A page this much longer than the public part has more of the post. */
    private const val GatedMargin = 40
}
