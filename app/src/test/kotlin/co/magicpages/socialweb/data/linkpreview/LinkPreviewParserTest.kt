package co.magicpages.socialweb.data.linkpreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewParserTest {
    @Test
    fun `open graph tags win, relative images resolve against the page, entities are decoded`() {
        val html = """
            <html><head>
            <title>Fallback title</title>
            <meta property="og:title" content="Tom &amp; Jerry&#8217;s &quot;big&quot; day">
            <meta property="og:description" content="Two  lines&#10;of   text &lt;3">
            <meta property="og:image" content="/media/cover.jpg">
            <meta property="og:site_name" content="The Daily">
            </head><body></body></html>
        """.trimIndent()

        val preview = parseLinkPreview(html, "https://www.example.com/news/story")!!

        assertEquals("Tom & Jerry\u2019s \"big\" day", preview.title)
        assertEquals("Two lines of text <3", preview.description)
        assertEquals("https://www.example.com/media/cover.jpg", preview.imageUrl)
        assertEquals("The Daily", preview.siteName)
        assertEquals("https://www.example.com/news/story", preview.url)
    }

    @Test
    fun `an image path without a slash is relative to the page's directory`() {
        val html = """<head><meta property="og:title" content="T"><meta property="og:image" content="cover.jpg"></head>"""
        assertEquals("https://example.com/blog/cover.jpg", parseLinkPreview(html, "https://example.com/blog/post")!!.imageUrl)

        val protocolRelative = """<head><meta property="og:title" content="T"><meta property="og:image" content="//cdn.example.net/c.png"></head>"""
        assertEquals("https://cdn.example.net/c.png", parseLinkPreview(protocolRelative, "https://example.com/blog/post")!!.imageUrl)
    }

    @Test
    fun `twitter tags and the title element fill in what open graph leaves out`() {
        val html = """
            <head>
            <title>  Plain
              title </title>
            <meta name="twitter:image" content="https://img.example.org/a.png">
            <meta name="description" content="Plain description">
            </head>
        """.trimIndent()

        val preview = parseLinkPreview(html, "https://www.example.org/a")!!

        assertEquals("Plain title", preview.title)
        assertEquals("Plain description", preview.description)
        assertEquals("https://img.example.org/a.png", preview.imageUrl)
        // No og:site_name: the host, without www.
        assertEquals("example.org", preview.siteName)
    }

    @Test
    fun `missing tags leave their fields empty, and a page without a title has no card`() {
        val bare = parseLinkPreview("<head><title>Only a title</title></head>", "https://example.com/")!!
        assertEquals("Only a title", bare.title)
        assertNull(bare.description)
        assertNull(bare.imageUrl)

        assertNull(parseLinkPreview("<head><meta property=\"og:image\" content=\"/a.png\"></head>", "https://example.com/"))
        assertNull(parseLinkPreview("<head><meta property=\"og:title\" content=\"   \"></head>", "https://example.com/"))
    }

    @Test
    fun `an image that isn't an http address is dropped`() {
        val html = """<head><meta property="og:title" content="T"><meta property="og:image" content="data:image/png;base64,AAAA"></head>"""
        assertNull(parseLinkPreview(html, "https://example.com/")!!.imageUrl)
    }

    @Test
    fun `only public web pages are previewed`() {
        assertTrue(isPreviewableUrl("https://example.com/a?b=c"))
        assertTrue(isPreviewableUrl("http://news.example.co.uk/story"))
        assertFalse(isPreviewableUrl("ftp://example.com/file"))
        assertFalse(isPreviewableUrl("mailto:me@example.com"))
        assertFalse(isPreviewableUrl("http://localhost:8080/"))
        assertFalse(isPreviewableUrl("http://192.168.1.10/admin"))
        assertFalse(isPreviewableUrl("http://127.0.0.1/"))
        assertFalse(isPreviewableUrl("http://[::1]/"))
        assertFalse(isPreviewableUrl("https://printer.local/"))
    }

    @Test
    fun `the first typed link is found without the punctuation around it`() {
        assertEquals("https://example.com/a", firstLinkIn("Read https://example.com/a, then tell me."))
        assertEquals("https://example.com/a", firstLinkIn("(see https://example.com/a)"))
        assertEquals("https://en.wikipedia.org/wiki/Kotlin_(language)", firstLinkIn("https://en.wikipedia.org/wiki/Kotlin_(language)."))
        assertEquals("https://example.com/second", firstLinkIn("http://localhost/x and https://example.com/second"))
        assertNull(firstLinkIn("no link, just @someone@example.com and #tags"))
        assertNull(firstLinkIn("https://exam"))
    }
}
