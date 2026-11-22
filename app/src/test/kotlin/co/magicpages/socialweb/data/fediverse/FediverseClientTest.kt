package co.magicpages.socialweb.data.fediverse

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FediverseClientTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun status(account: String, extra: String = "") = json.decodeFromString<MastodonStatus>(
        """
        {
          "id": "117660502739386175",
          "uri": "https://remote.example/users/ana/statuses/1",
          "url": "https://remote.example/@ana/1",
          "created_at": "2026-11-22T13:41:00.000Z",
          "content": "<p>Hello</p>",
          "sensitive": false,
          "spoiler_text": "",
          "account": $account,
          "replies_count": 3, "reblogs_count": 5, "favourites_count": 8
          $extra
        }
        """.trimIndent(),
    )

    @Test
    fun `a post is keyed by its ActivityPub id and keeps the server's counts`() {
        val post = status("""{"acct": "ana@remote.example", "uri": "https://remote.example/users/ana"}""").toPost("mastodon.social")

        assertEquals("https://remote.example/users/ana/statuses/1", post.id)
        assertEquals("https://remote.example/@ana/1", post.url)
        assertEquals(8, post.likeCount)
        assertEquals(3, post.replyCount)
        assertEquals(5, post.repostCount)
        assertNull(post.contentWarning)
    }

    @Test
    fun `the server's own accounts get its host in their handle, others keep theirs`() {
        val local = status("""{"acct": "ana", "uri": "https://hachyderm.io/users/ana"}""").toPost("hachyderm.io")
        val remote = status("""{"acct": "bo@remote.example", "uri": "https://remote.example/users/bo"}""").toPost("hachyderm.io")

        assertEquals("@ana@hachyderm.io", local.author.handle)
        assertEquals("@bo@remote.example", remote.author.handle)
    }

    @Test
    fun `an account without an ActivityPub id falls back to its page`() {
        val post = status("""{"acct": "ana", "url": "https://old.example/@ana"}""").toPost("old.example")

        assertEquals("https://old.example/@ana", post.author.id)
    }

    @Test
    fun `media becomes images and videos the cards can show, and unknown kinds are left out`() {
        val post = status(
            """{"acct": "ana"}""",
            """,
            "spoiler_text": "Spoilers",
            "media_attachments": [
              {"type": "image", "url": "https://files.example/a.jpg", "description": "A cat", "meta": {"original": {"width": 1200, "height": 800.0}}},
              {"type": "gifv", "url": "https://files.example/b.mp4", "preview_url": "https://files.example/b.png"},
              {"type": "unknown", "url": "https://files.example/c"},
              {"type": "image", "remote_url": "https://remote.example/d.jpg"}
            ]""",
        ).toPost("mastodon.social")

        assertEquals("Spoilers", post.contentWarning)
        assertEquals(listOf("Image", "Video", "Image"), post.attachments.map { it.type })
        val image = post.attachments[0]
        assertEquals("A cat", image.name)
        assertEquals(1200, image.width)
        assertEquals(800, image.height)
        assertNull(image.posterUrl)
        assertEquals("https://files.example/b.png", post.attachments[1].posterUrl)
        assertEquals("https://remote.example/d.jpg", post.attachments[2].url)
        assertEquals(2, post.images.size)
    }

    @Test
    fun `a typed server becomes a bare lower-case host`() {
        assertEquals("hachyderm.io", normalizeServer("  Hachyderm.IO "))
        assertEquals("hachyderm.io", normalizeServer("https://hachyderm.io/explore?x=1"))
        assertEquals("hachyderm.io", normalizeServer("@me@hachyderm.io"))
        assertEquals("hachyderm.io", normalizeServer("hachyderm.io."))
        assertEquals("xn--mstdn-gra.example", normalizeServer("mästdn.example"))
    }

    @Test
    fun `what isn't a host is refused`() {
        assertNull(normalizeServer(""))
        assertNull(normalizeServer("localhost"))
        assertNull(normalizeServer("two words.example"))
        assertNull(normalizeServer("host.example:8080"))
        assertNull(normalizeServer("-bad.example"))
    }
}
