package co.magicpages.socialweb.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSitesTest {
    private val hidden = setOf("example.com")

    @Test
    fun `a site is its host, with or without www`() {
        assertTrue(hidden.hidesReaderFor("https://example.com/post/"))
        assertTrue(hidden.hidesReaderFor("https://www.Example.com/other/"))
        assertFalse(hidden.hidesReaderFor("https://blog.example.com/post/"))
        assertFalse(hidden.hidesReaderFor("https://other.example/post/"))
    }

    @Test
    fun `an article without an address is never hidden`() {
        assertFalse(hidden.hidesReaderFor(null))
        assertFalse(hidden.hidesReaderFor(""))
    }
}
