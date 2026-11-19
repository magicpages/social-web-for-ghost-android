package co.magicpages.socialweb.ui.quote

import co.magicpages.socialweb.data.activitypub.Attachment
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteCardSpecTest {
    private val author = Author(id = "1", handle = "@ada@remote.example", name = "Ada", avatarUrl = "https://remote.example/ada.png")

    private fun post(
        content: String = "<p>Hello</p>",
        type: Int = Post.TYPE_NOTE,
        url: String = "https://remote.example/@ada/1",
        author: Author = this.author,
        sensitive: Boolean = false,
        contentWarning: String? = null,
        attachments: List<Attachment> = emptyList(),
        title: String = "",
        summary: String? = null,
        excerpt: String = "",
        featureImageUrl: String? = null,
        publishedAt: String? = null,
    ) = Post(
        id = "https://remote.example/notes/1",
        type = type,
        title = title,
        excerpt = excerpt,
        summary = summary,
        sensitive = sensitive,
        contentWarning = contentWarning,
        content = content,
        url = url,
        featureImageUrl = featureImageUrl,
        publishedAt = publishedAt,
        attachments = attachments,
        author = author,
    )

    @Test
    fun `a note's words keep their paragraphs, line breaks and shortened links`() {
        val html = """<p>Hello <a href="https://example.com/abc"><span class="invisible">https://</span>""" +
            """<span class="ellipsis">example.com/a</span><span class="invisible">bc</span></a> &amp; you</p>""" +
            "<p>Second<br>line</p>"

        val card = postQuote(post(content = html))

        assertEquals("Hello example.com/a… & you\n\nSecond\nline", card.body)
    }

    @Test
    fun `the quote line other servers add for quoted posts is left out`() {
        val html = """<p>My take</p><p class="quote-inline">RE: <a href="https://remote.example/@a/1">https://remote.example/@a/1</a></p>"""

        assertEquals("My take", postQuote(post(content = html)).body)
    }

    @Test
    fun `a note shows its first picture`() {
        val card = postQuote(
            post(
                attachments = listOf(
                    Attachment(type = "Document", mediaType = "video/mp4", url = "https://remote.example/v.mp4"),
                    Attachment(type = "Image", mediaType = "image/png", url = "https://remote.example/1.png"),
                    Attachment(type = "Image", mediaType = "image/png", url = "https://remote.example/2.png"),
                ),
            ),
        )

        assertEquals("https://remote.example/1.png", card.imageUrl)
        assertFalse(card.warned)
    }

    @Test
    fun `an article shows its title, summary and feature image`() {
        val card = postQuote(
            post(
                type = Post.TYPE_ARTICLE,
                title = " A title ",
                summary = " The summary ",
                excerpt = "The excerpt",
                featureImageUrl = "https://remote.example/cover.jpg",
                content = "<p>The whole article</p>",
            ),
        )

        assertEquals("A title", card.title)
        assertEquals("The summary", card.body)
        assertEquals("https://remote.example/cover.jpg", card.imageUrl)
    }

    @Test
    fun `an article without a summary falls back to its excerpt`() {
        val card = postQuote(post(type = Post.TYPE_ARTICLE, title = "T", summary = "  ", excerpt = "The excerpt"))

        assertEquals("The excerpt", card.body)
    }

    @Test
    fun `a content-warned post shows only the warning, as the feed does`() {
        val card = postQuote(
            post(
                content = "<p>hidden</p>",
                contentWarning = " Politics ",
                attachments = listOf(Attachment(type = "Image", url = "https://remote.example/1.png")),
            ),
        )

        assertTrue(card.warned)
        assertEquals("Politics", card.warning)
        assertEquals("", card.body)
        assertNull(card.imageUrl)
    }

    @Test
    fun `a sensitive post without a warning hides its words and pictures too`() {
        val card = postQuote(
            post(sensitive = true, attachments = listOf(Attachment(type = "Image", url = "https://remote.example/1.png"))),
        )

        assertTrue(card.warned)
        assertNull(card.warning)
        assertEquals("", card.body)
        assertNull(card.imageUrl)
    }

    @Test
    fun `the author is shown by name, or by handle without one`() {
        assertEquals("Ada", postQuote(post()).authorName)
        assertEquals("@ada@remote.example", postQuote(post(author = author.copy(name = ""))).authorName)
        assertEquals("https://remote.example/ada.png", postQuote(post()).avatarUrl)
        assertNull(postQuote(post(author = author.copy(avatarUrl = " "))).avatarUrl)
    }

    @Test
    fun `the card credits the post's server, from its link or else from the handle`() {
        assertEquals("mastodon.social", postQuote(post(url = "https://www.mastodon.social/@ada/1")).sourceHost)
        assertEquals("remote.example", postQuote(post(url = "")).sourceHost)
    }

    @Test
    fun `the publication time is read, and a bad one is not shown`() {
        val published = "2026-11-18T10:00:00.000Z"

        assertEquals(Instant.parse(published).toEpochMilli(), postQuote(post(publishedAt = published)).publishedAtMillis)
        assertNull(postQuote(post(publishedAt = "yesterday")).publishedAtMillis)
    }

    @Test
    fun `quoting a post starts the note with its link below an empty line, and a card`() {
        val seed = postSeed(post())

        assertEquals("\n\nhttps://remote.example/@ada/1", seed.text)
        assertEquals("Ada", (seed.card!!() as PostQuote).authorName)
    }

    @Test
    fun `quoting a post without a web address starts with no text`() {
        assertEquals("", postSeed(post(url = "")).text)
    }

    @Test
    fun `a shared page with selected words starts the note with its link and a text card`() {
        val shared = SharedContent("https://example.com/a", "Selected words", "The page", null)

        val seed = shared.toSeed()

        assertEquals("\n\nhttps://example.com/a", seed.text)
        assertEquals(TextQuote("Selected words", "The page", "example.com"), seed.card!!())
    }

    @Test
    fun `a shared link alone starts the note with the link and no card`() {
        val seed = SharedContent("https://example.com/a", null, "The page", null).toSeed()

        assertEquals("\n\nhttps://example.com/a", seed.text)
        assertNull(seed.card)
    }

    @Test
    fun `shared text without a link starts the note as it is`() {
        val seed = SharedContent(null, null, null, "Just words").toSeed()

        assertEquals("Just words", seed.text)
        assertNull(seed.card)
    }

    @Test
    fun `long quotes are cut at a word with an ellipsis`() {
        assertEquals("short", ellipsize("short", 10))
        assertEquals("one two…", ellipsize("one two three four", 10))
        assertEquals("abcdefghij…", ellipsize("abcdefghijklmnop", 10))
    }
}
