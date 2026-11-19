package co.magicpages.socialweb.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MastodonLengthTest {
    @Test
    fun `a link counts as 23 however long it is`() {
        val url = "https://example.com/a/very/long/path/that/goes/on/and/on?with=query&and=more"

        assertEquals(23, mastodonLength(url))
        assertEquals("see ".length + 23, mastodonLength("see $url"))
        assertEquals(23, mastodonLength("http://a.co"))
    }

    @Test
    fun `a remote mention counts without its domain`() {
        assertEquals("@alice hi".length, mastodonLength("@alice@social.example hi"))
    }

    @Test
    fun `a local mention counts in full`() {
        assertEquals("@alice hi".length, mastodonLength("@alice hi"))
    }

    @Test
    fun `an emoji counts as one`() {
        assertEquals(3, mastodonLength("a😀b"))
    }

    @Test
    fun `surrounding whitespace is not counted`() {
        assertEquals(2, mastodonLength("  hi \n"))
    }
}
