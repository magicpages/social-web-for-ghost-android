package co.magicpages.socialweb.ui.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HashtagLinkTest {
    @Test
    fun `reads Mastodon and Pixelfed hashtag pages`() {
        assertEquals("caturday", hashtagIn("https://mastodon.social/tags/caturday"))
        assertEquals("photography", hashtagIn("https://pixelfed.social/discover/tags/photography?src=hash"))
        assertEquals("Fotografie", hashtagIn("https://chaos.social/tags/Fotografie/"))
    }

    @Test
    fun `decodes names that aren't ASCII`() {
        assertEquals("日本", hashtagIn("https://mstdn.jp/tags/%E6%97%A5%E6%9C%AC"))
    }

    @Test
    fun `leaves other links alone`() {
        // A Ghost blog's tag page is a website, not a fediverse hashtag.
        assertNull(hashtagIn("https://blog.example.com/tag/news/"))
        assertNull(hashtagIn("https://example.com/tags/a/b"))
        assertNull(hashtagIn("https://example.com/articles/tags"))
        assertNull(hashtagIn("mailto:me@example.com"))
        assertNull(hashtagIn("not a link"))
    }
}
