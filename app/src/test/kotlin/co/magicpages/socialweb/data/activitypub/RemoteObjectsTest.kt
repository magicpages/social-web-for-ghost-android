package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.ghost.GhostException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteObjectsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private var requests = 0

    private fun remote(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        RemoteObjects(HttpClient(MockEngine { requests++; respond(body, status) }) { expectSuccess = false }, json)

    /** A server answering by address; anything not listed is a 404. */
    private fun server(vararg pages: Pair<String, String>, unauthorized: Set<String> = emptySet()) = RemoteObjects(
        HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    url in unauthorized -> respond("""{"error":"Request not signed"}""", HttpStatusCode.Unauthorized)
                    else -> pages.toMap()[url]?.let { respond(it) } ?: respond("", HttpStatusCode.NotFound)
                }
            },
        ) { expectSuccess = false },
        json,
    )

    private fun create(id: String, inReplyTo: String?): String {
        val answers = inReplyTo?.let { ",\"inReplyTo\":\"$it\"" }.orEmpty()
        return "{\"type\":\"Create\",\"object\":{\"type\":\"Note\",\"id\":\"$id\"$answers}}"
    }

    @Test
    fun `lists the replies on the newest outbox page and where the next page is`() = runTest {
        val remote = server(
            "https://m.example/users/ann" to """{"type":"Person","outbox":"https://m.example/users/ann/outbox"}""",
            "https://m.example/users/ann/outbox" to """{"type":"OrderedCollection","first":"https://m.example/users/ann/outbox?page=true"}""",
            "https://m.example/users/ann/outbox?page=true" to """{"type":"OrderedCollectionPage",
                "next":"https://m.example/users/ann/outbox?max_id=1&page=true",
                "orderedItems":[
                  ${create("https://m.example/notes/3", "https://x.example/notes/9")},
                  {"type":"Announce","object":"https://y.example/notes/8"},
                  ${create("https://m.example/notes/2", null)},
                  {"type":"Create","object":"https://m.example/notes/1"},
                  {"type":"Create","object":{"id":"https://m.example/notes/0","inReplyTo":{"id":"https://z.example/notes/7"}}}
                ]}""",
        )

        val page = remote.outboxReplies("https://m.example/users/ann", page = null)

        assertEquals(
            listOf(
                OutboxReply("https://m.example/notes/3", "https://x.example/notes/9"),
                OutboxReply("https://m.example/notes/0", "https://z.example/notes/7"),
            ),
            page.replies,
        )
        assertEquals("https://m.example/users/ann/outbox?max_id=1&page=true", page.next)
    }

    @Test
    fun `finds the outbox of an actor that is only shown to signed requests`() = runTest {
        val remote = server(
            "https://m.example/users/ann/outbox" to """{"type":"OrderedCollection","first":{"type":"OrderedCollectionPage",
                "orderedItems":[${create("https://m.example/notes/3", "https://x.example/notes/9")}]}}""",
            unauthorized = setOf("https://m.example/users/ann"),
        )

        val page = remote.outboxReplies("https://m.example/users/ann", page = null)

        assertEquals(listOf("https://m.example/notes/3"), page.replies.map { it.id })
        assertNull(page.next)
    }

    @Test
    fun `a private outbox has no replies and no further pages`() = runTest {
        val remote = server(unauthorized = setOf("https://m.example/users/ann", "https://m.example/users/ann/outbox"))

        val page = remote.outboxReplies("https://m.example/users/ann", page = null)

        assertEquals(emptyList<OutboxReply>(), page.replies)
        assertNull(page.next)
    }

    @Test
    fun `a page that names itself as the next one ends the list`() = runTest {
        val self = "https://m.example/users/ann/outbox?page=2"
        val remote = server(self to """{"next":"$self","orderedItems":[]}""")

        assertNull(remote.outboxReplies("https://m.example/users/ann", page = self).next)
    }

    @Test
    fun `an unreachable server is a network error, not an empty list`() = runTest {
        val remote = RemoteObjects(HttpClient(MockEngine { throw IOException("offline") }) { expectSuccess = false }, json)

        val error = runCatching { remote.outboxReplies("https://m.example/users/ann", page = null) }.exceptionOrNull()

        assertTrue(error.toString(), error is GhostException.Network)
    }

    @Test
    fun `reads what a reply answers whether given as an id, an object or a list`() = runTest {
        val forms = listOf(
            """"https://a.example/notes/1"""",
            """{"type":"Note","id":"https://a.example/notes/1"}""",
            """[{"id":"https://a.example/notes/1"}]""",
        )
        for (form in forms) {
            val obj = remote("""{"type":"Note","inReplyTo":$form}""").fetch("https://b.example/notes/2")
            assertEquals(form, "https://a.example/notes/1", obj?.inReplyTo)
        }
    }

    @Test
    fun `takes a video's title and its page rather than its media files`() = runTest {
        val obj = remote(
            """{"type":"Video","name":"Haken dran","url":[
                {"type":"Link","mediaType":"text/html","href":"https://tube.example/w/abc"},
                {"type":"Link","mediaType":"video/mp4","href":"https://tube.example/abc.mp4"}]}""",
        ).fetch("https://tube.example/videos/watch/abc")

        assertEquals("Haken dran", obj?.name)
        assertEquals("https://tube.example/w/abc", obj?.url)
        assertNull(obj?.inReplyTo)
    }

    @Test
    fun `servers that refuse or answer with something else give nothing`() = runTest {
        // Authorized-fetch servers refuse anonymous requests; this one isn't Mastodon-shaped.
        assertNull(remote("""{"error":"Request not signed"}""", HttpStatusCode.Unauthorized).fetch("https://a.example/notes/1"))
        assertNull(remote("<html>Not found</html>").fetch("https://a.example/notes/1"))
    }

    @Test
    fun `a Mastodon server refusing anonymous requests still tells what a status answers`() = runTest {
        val remote = server(
            "https://h.example/api/v1/statuses/200" to """{"uri":"https://h.example/users/ann/statuses/200",
                "url":"https://h.example/@ann/200","in_reply_to_id":"150",
                "emojis":[{"shortcode":"blobcat","url":"https://h.example/e/blobcat.png"}]}""",
            "https://h.example/api/v1/statuses/150" to """{"uri":"https://x.example/notes/9","in_reply_to_id":null}""",
            unauthorized = setOf("https://h.example/users/ann/statuses/200"),
        )

        val obj = remote.fetch("https://h.example/users/ann/statuses/200")

        assertEquals("https://x.example/notes/9", obj?.inReplyTo)
        assertEquals("https://h.example/@ann/200", obj?.url)
        assertEquals(listOf(CustomEmoji("blobcat", "https://h.example/e/blobcat.png")), obj?.emojis)
    }

    @Test
    fun `a Mastodon account refusing anonymous requests still tells its name and emoji`() = runTest {
        val remote = server(
            "https://h.example/api/v1/accounts/lookup?acct=ann" to """{"display_name":"Ann :verified:",
                "url":"https://h.example/@ann","emojis":[{"shortcode":"verified","url":"https://h.example/e/v.png"}]}""",
            unauthorized = setOf("https://h.example/users/ann"),
        )

        val obj = remote.fetch("https://h.example/users/ann")

        assertEquals("Ann :verified:", obj?.name)
        assertEquals(listOf(CustomEmoji("verified", "https://h.example/e/v.png")), obj?.emojis)
    }

    @Test
    fun `reads custom emoji from the tags, one or many, skipping other tags`() = runTest {
        val emoji = """{"type":"Emoji","name":":blobcat:","icon":{"type":"Image","url":"https://a.example/e/blobcat.png"}}"""
        val many = remote("""{"tag":[{"type":"Hashtag","name":"#cats"},$emoji]}""").fetch("https://a.example/notes/1")
        val one = remote("""{"tag":$emoji}""").fetch("https://a.example/notes/1")

        val expected = listOf(CustomEmoji("blobcat", "https://a.example/e/blobcat.png"))
        assertEquals(expected, many?.emojis)
        assertEquals(expected, one?.emojis)
    }

    @Test
    fun `only web addresses are fetched`() = runTest {
        assertNull(remote("""{"inReplyTo":"https://a.example/notes/1"}""").fetch("file:///data/secret"))
        assertEquals(0, requests)
    }
}
