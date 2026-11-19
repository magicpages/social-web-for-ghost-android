package co.magicpages.socialweb.data.member

import co.magicpages.socialweb.data.member.MemberContent.Extraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberContentTest {
    private val first = "This is the first paragraph of the free preview of the post."
    private val second = "The second paragraph is still public and ends the preview here."
    private val third = "Only members get this third paragraph, which is long enough to be real content."
    private val fourth = "And this fourth one, with plenty more words, comes after it for good measure."

    /** What the social web carries of a gated post: the public part, then Ghost's notice. */
    private val publicPart = """
        <p>$first</p>
        <p>$second</p>
        <div class="gh-paid-content-notice">
          <h3>This post is for subscribers only</h3>
          <p>Become a member to get access to all content.</p>
          <a class="gh-paid-content-cta" href="https://example.com/#/portal/signup">Subscribe now</a>
        </div>
    """.trimIndent()

    private fun page(content: String) = """
        <html><body>
          <header><h1>The post</h1><p>By someone, a short byline that is not the post.</p></header>
          <article><section class="gh-content gh-canvas">$content</section></article>
          <footer><p>Subscribe to the newsletter for more posts like this one, every week.</p></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun `the notice marks a post that is only partly there`() {
        assertTrue(MemberContent.isPaywalled(publicPart))
        assertFalse(MemberContent.isPaywalled("<p>$first</p><p>$second</p>"))
    }

    @Test
    fun `a member's page has the whole post`() {
        val html = page("<p>$first</p><p>$second</p><p>$third</p><h2>More</h2><p>$fourth</p>")

        val result = MemberContent.extract(html, publicPart)

        assertTrue(result is Extraction.Full)
        val content = (result as Extraction.Full).html
        assertTrue(third in content)
        assertTrue(fourth in content)
        assertTrue(first in content)
        // Only the post: not the byline above it or the footer below it.
        assertFalse("short byline" in content)
        assertFalse("newsletter" in content)
    }

    @Test
    fun `a page that still offers the upgrade is still gated`() {
        val html = page(
            """<p>$first</p><p>$second</p><div class="gh-post-upgrade-cta"><h2>Upgrade to continue reading, become a paid member today</h2></div>""",
        )

        assertEquals(Extraction.Gated, MemberContent.extract(html, publicPart))
    }

    @Test
    fun `a page with no more text than the preview is still gated`() {
        val html = page("<p>$first</p><p>$second</p>")

        assertEquals(Extraction.Gated, MemberContent.extract(html, publicPart))
    }

    @Test
    fun `a page without the post is not found`() {
        val html = "<html><body><main><p>Something else entirely, such as an error page for the visitor.</p></main></body></html>"

        assertEquals(Extraction.NotFound, MemberContent.extract(html, publicPart))
    }

    @Test
    fun `a preview with no text is not found`() {
        assertEquals(Extraction.NotFound, MemberContent.extract(page("<p>$first</p><p>$third</p>"), "<p>Hi</p>"))
    }

    @Test
    fun `the paragraph is matched on its first words when the theme changes the rest`() {
        val long = "A long opening paragraph about many things, " + "word ".repeat(40)
        val preview = "<p>$long and the preview ends</p><p>$second</p>" +
            """<div class="gh-paid-content-notice"><h3>Members only</h3><p>Sign up.</p></div>"""
        val html = page("<p>$long</p><p>$second</p><p>$third</p><p>$fourth</p>")

        assertTrue(MemberContent.extract(html, preview) is Extraction.Full)
    }
}
