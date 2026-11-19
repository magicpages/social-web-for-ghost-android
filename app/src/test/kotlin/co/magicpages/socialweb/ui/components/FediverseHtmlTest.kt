package co.magicpages.socialweb.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FediverseHtmlTest {
    private fun hrefIn(html: String) = Regex("""href="([^"]*)"""").find(tidyFediverseHtml(html))!!.groupValues[1]

    @Test
    fun `Mastodon mentions open the profile in the app`() {
        val html = """<span class="h-card"><a href="https://mastodon.social/@alice" class="u-url mention">@<span>alice</span></a></span>"""

        assertEquals("@alice@mastodon.social" to "https://mastodon.social/@alice", ProfileLinks.parse(hrefIn(html)))
    }

    @Test
    fun `ActivityPub-style user URLs are mentions too`() {
        val html = """<a class="mention u-url" href="https://pixel.example/users/bob_99">@bob_99</a>"""

        assertEquals("@bob_99@pixel.example", ProfileLinks.parse(hrefIn(html))?.first)
    }

    @Test
    fun `Ghost mentions use the handle Ghost recorded, whatever the URL looks like`() {
        val html = """<a href="https://carol.blog/" data-profile="@index@carol.blog" rel="nofollow">@index@carol.blog</a>"""

        assertEquals("@index@carol.blog" to "https://carol.blog/", ProfileLinks.parse(hrefIn(html)))
    }

    @Test
    fun `hashtags, plain links and mentions without a readable handle stay web links`() {
        val hashtag = """<a href="https://mastodon.social/tags/ghost" class="mention hashtag" rel="tag">#ghost</a>"""
        val link = """<a href="https://ghost.org/">ghost.org</a>"""
        val opaque = """<a href="https://example.social/profile/9f8e" class="u-url mention">@dave</a>"""

        for (html in listOf(hashtag, link, opaque)) {
            assertEquals(html, tidyFediverseHtml(html))
        }
    }

    @Test
    fun `profile links keep handles and URLs with reserved characters intact`() {
        val url = "https://example.com/@eve?ref=a&b=c#top"

        assertEquals("@eve@example.com" to url, ProfileLinks.parse(ProfileLinks.create("@eve@example.com", url)))
        assertEquals("@eve@example.com" to null, ProfileLinks.parse(ProfileLinks.create("@eve@example.com", null)))
        assertNull(ProfileLinks.parse("https://example.com/@eve"))
    }

    @Test
    fun `Mastodon quote posts lose their RE line and point at the quoted post`() {
        // As mastodon.social sends it, through Ghost's sanitizer.
        val html = """<p class="quote-inline">RE: <a href="https://mastodon.social/@randahl/117636544581777771" target="_blank" rel="nofollow noopener" translate="no">""" +
            """<span class="invisible">https://</span><span class="ellipsis">mastodon.social/@randahl/11763</span><span class="invisible">6544581777771</span></a></p>""" +
            """<p>You need to see this</p>"""

        assertEquals("<p>You need to see this</p>" to "https://mastodon.social/@randahl/117636544581777771", splitQuote(html))
    }

    @Test
    fun `Misskey quote lines at the end are split off too`() {
        val html = """<p>Look<span class="quote-inline"><br><br>RE: <a href="https://misskey.example/notes/9x?a=1&amp;b=2">https://misskey.example/notes/9x</a></span></p>"""

        assertEquals("<p>Look</p>" to "https://misskey.example/notes/9x?a=1&b=2", splitQuote(html))
    }

    @Test
    fun `posts that only mention RE or have no linked quote stay as they are`() {
        val typed = """<p>RE: <a href="https://example.com/post">my last post</a></p>"""
        val unlinked = """<p class="quote-inline">RE: [deleted]</p><p>Hi</p>"""
        val script = """<p class="quote-inline">RE: <a href="javascript:alert(1)">x</a></p>"""

        for (html in listOf(typed, unlinked, script)) {
            assertEquals(html to null, splitQuote(html))
        }
    }
}
