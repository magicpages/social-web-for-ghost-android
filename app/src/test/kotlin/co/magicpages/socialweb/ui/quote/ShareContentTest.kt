package co.magicpages.socialweb.ui.quote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareContentTest {
    private fun parse(text: String?, subject: String? = null) = SharedContent.parse(text, subject)!!

    @Test
    fun `a lone link is the page, with nothing to quote`() {
        val shared = parse("https://example.com/a", "Example Domain")

        assertEquals("https://example.com/a", shared.url)
        assertNull(shared.quote)
        assertEquals("Example Domain", shared.title)
        assertNull(shared.plainText)
    }

    @Test
    fun `text before the link is the selection and loses its quotation marks`() {
        val shared = parse("“Quote here.” https://example.com/post")

        assertEquals("https://example.com/post", shared.url)
        assertEquals("Quote here.", shared.quote)
    }

    @Test
    fun `a dash between the selection and the link is dropped`() {
        assertEquals("Words", parse("\"Words\" — https://example.com/a").quote)
    }

    @Test
    fun `quotation marks are only removed when they wrap the whole selection`() {
        assertEquals("He said \"hi\"", parse("He said \"hi\" https://example.com/a").quote)
    }

    @Test
    fun `line breaks in the selection are kept and blank runs collapse`() {
        val shared = parse("First   line\nsecond line\n\n\n\nthird\n\nhttps://example.com/x")

        assertEquals("First line\nsecond line\n\nthird", shared.quote)
    }

    @Test
    fun `text after the link is the selection too`() {
        assertEquals("Some selected words", parse("https://example.com/a Some selected words").quote)
    }

    @Test
    fun `the sentence's punctuation after a link is not part of it`() {
        val shared = parse("Great read, see https://example.com/wiki/Foo_(bar).")

        assertEquals("https://example.com/wiki/Foo_(bar)", shared.url)
        assertEquals("Great read, see", shared.quote)
    }

    @Test
    fun `text that only repeats the title is not a quote`() {
        val shared = parse("Example Domain\nhttps://example.com", "example domain")

        assertNull(shared.quote)
        assertEquals("https://example.com", shared.url)
        assertEquals("example domain", shared.title)
    }

    @Test
    fun `selected text next to the link is kept apart from the title`() {
        val shared = parse("“Words”\nhttps://example.com/a", "Example")

        assertEquals("Words", shared.quote)
        assertEquals("Example", shared.title)
    }

    @Test
    fun `a share with no link keeps its text as it is`() {
        val shared = parse("  Just some words\n", "A subject")

        assertNull(shared.url)
        assertNull(shared.quote)
        assertEquals("Just some words", shared.plainText)
    }

    @Test
    fun `nothing to share is nothing`() {
        assertNull(SharedContent.parse("   ", "Title"))
        assertNull(SharedContent.parse(null, null))
    }

    @Test
    fun `a text fragment is the selection and leaves the link clean`() {
        val shared = parse("https://example.com/about/#:~:text=with%20a%20mission%20to%20create", "About")

        assertEquals("https://example.com/about/", shared.url)
        assertEquals("with a mission to create", shared.quote)
        assertEquals("About", shared.title)
    }

    @Test
    fun `a fragment's prefix and suffix are context, not quote`() {
        val range = "https://example.com/a#:~:text=founded-,with%20a%20mission,journalists,-and"
        assertEquals("with a mission … journalists", parse(range).quote)
        assertEquals("hello world", parse("https://example.com/a#:~:text=hello%20world,-and").quote)
        assertEquals("hello", parse("https://example.com/a#:~:text=the-,hello").quote)
        assertEquals("hello", parse("https://example.com/a#:~:text=the-,hello,-world").quote)
    }

    @Test
    fun `percent-encoded commas, dashes and ampersands belong to the text`() {
        val shared = parse("https://example.com/a#intro:~:text=one%2C%20two&text=three%2Dfour%20Q%26A")

        assertEquals("one, two … three-four Q&A", shared.quote)
        assertEquals("https://example.com/a#intro", shared.url)
    }

    @Test
    fun `fragment text is decoded as UTF-8 and plus signs stay`() {
        val shared = parse("https://example.com/a#:~:text=caf%C3%A9%20%E2%80%94%20ol%C3%A9%20C%2B%2B")

        assertEquals("café — olé C++", shared.quote)
    }

    @Test
    fun `a full stop ending a highlight stays with it`() {
        val shared = parse("https://example.com/a#:~:text=secrets%20of%20ants.")

        assertEquals("secrets of ants.", shared.quote)
        assertEquals("https://example.com/a", shared.url)
    }

    @Test
    fun `selected text beside the link wins over a fragment that only has its ends`() {
        val shared = parse("“Exact words in between”\nhttps://example.com/a#:~:text=Exact,between")

        assertEquals("Exact words in between", shared.quote)
        assertEquals("https://example.com/a", shared.url)
    }

    @Test
    fun `a link with an ordinary anchor and no directive is left alone`() {
        val shared = parse("https://example.com/a#section")

        assertEquals("https://example.com/a#section", shared.url)
        assertNull(shared.quote)
    }

    @Test
    fun `the host is the domain without www`() {
        val shared = SharedContent("https://www.Example.com:8080/a?b=c", null, null, null)

        assertEquals("example.com", shared.host)
        assertNull(SharedContent(null, null, null, "text").host)
    }
}
