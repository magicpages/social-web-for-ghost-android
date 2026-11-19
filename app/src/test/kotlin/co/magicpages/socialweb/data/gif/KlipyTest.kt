package co.magicpages.socialweb.data.gif

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class KlipyTest {
    private val config = KlipyConfig("k3y", "medium")
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        KlipyClient(HttpClient(MockEngine { request -> requests += request; handler(request) }) { expectSuccess = false })

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a blank term asks for featured GIFs with Ghost's default term, a term searches`() {
        val featured = Url(klipyUrl(config, "", next = null, limit = 30))
        assertEquals("/v2/featured", featured.encodedPath)
        assertEquals("excited", featured.parameters["q"])
        assertEquals("k3y", featured.parameters["key"])
        assertEquals("social-web-app", featured.parameters["client_key"])
        assertEquals("medium", featured.parameters["contentfilter"])
        assertEquals("tinygif,mediumgif,gif", featured.parameters["media_filter"])
        assertNull(featured.parameters["pos"])

        val search = Url(klipyUrl(config, "high five", next = "abc", limit = 30))
        assertEquals("/v2/search", search.encodedPath)
        assertEquals("high five", search.parameters["q"])
        assertEquals("abc", search.parameters["pos"])
    }

    @Test
    fun `a result lists its https sizes largest first, previews the tiny one and describes itself`() {
        val gif = klipyGif(
            json(
                """{"id":42,"title":"Dog","content_description":"  dog dancing  ","media_formats":{
                "tinygif":{"url":"https://media.klipy.com/t.gif","dims":[120,90],"size":4000},
                "mediumgif":{"url":"http://media.klipy.com/m.gif","dims":[320,240],"size":90000},
                "gif":{"url":"https://media.klipy.com/g.gif","dims":[640,480]}}}""",
            ),
        )!!
        assertEquals("42", gif.id)
        assertEquals("dog dancing", gif.title)
        assertEquals(listOf("https://media.klipy.com/g.gif", "https://media.klipy.com/t.gif"), gif.sizes.map { it.url })
        assertEquals(KlipyGif.Media("https://media.klipy.com/t.gif", 120, 90, 4000), gif.preview)
        assertNull(gif.sizes.first().bytes)
    }

    @Test
    fun `a result without a tiny size previews its smallest, and one without sizes is dropped`() {
        val gif = klipyGif(
            json("""{"id":"a","title":"Wave","media_formats":{"gif":{"url":"https://x/g.gif"},"mediumgif":{"url":"https://x/m.gif"}}}"""),
        )!!
        assertEquals("https://x/m.gif", gif.preview.url)
        assertEquals("Wave", gif.title)
        assertNull(klipyGif(json("""{"id":"b","media_formats":{"gif":{"url":"http://x/g.gif"}}}""")))
    }

    @Test
    fun `a page ends when next is missing, empty, zero or there are no results`() {
        val result = """{"id":"1","media_formats":{"tinygif":{"url":"https://x/t.gif"}}}"""
        assertEquals("P2", klipyPage(json("""{"results":[$result],"next":"P2"}""")).next)
        assertNull(klipyPage(json("""{"results":[$result],"next":"0"}""")).next)
        assertNull(klipyPage(json("""{"results":[$result],"next":""}""")).next)
        assertNull(klipyPage(json("""{"results":[],"next":"P2"}""")).next)
    }

    @Test
    fun `KLIPY's refusal carries its message, whether sent with 200 or an error status`() = runTest {
        try {
            client { respond("""{"result":false,"errors":{"message":["The provided API key is invalid"]}}""", HttpStatusCode.OK) }
                .gifs(config, "cat")
            fail("Expected Refused")
        } catch (e: KlipyException.Refused) {
            assertEquals("The provided API key is invalid", e.klipyMessage)
        }
        try {
            client { respond("""{"error":{"message":"Rate limited"}}""", HttpStatusCode.TooManyRequests) }.gifs(config, "cat")
            fail("Expected Refused")
        } catch (e: KlipyException.Refused) {
            assertEquals("Rate limited", e.klipyMessage)
        }
        try {
            client { respond("<html>", HttpStatusCode.BadGateway) }.gifs(config, "cat")
            fail("Expected Failed")
        } catch (e: KlipyException.Failed) {
            assertEquals(502, e.status)
        }
    }

    @Test
    fun `a GIF over the limit is attached in the next smaller size that fits`() = runTest {
        val small = "GIF89a".toByteArray() + ByteArray(10)
        val klipy = client { request ->
            when (request.url.encodedPath) {
                "/g.gif" -> respond(ByteArray(500), HttpStatusCode.OK)
                "/m.gif" -> respond("GIF89a".toByteArray() + ByteArray(200), HttpStatusCode.OK)
                else -> respond(small, HttpStatusCode.OK)
            }
        }
        val gif = KlipyGif(
            id = "1",
            title = "",
            preview = KlipyGif.Media("https://x/t.gif", 1, 1, null),
            sizes = listOf(
                KlipyGif.Media("https://x/g.gif", 1, 1, bytes = 500),
                KlipyGif.Media("https://x/m.gif", 1, 1, bytes = null),
                KlipyGif.Media("https://x/t.gif", 1, 1, bytes = 16),
            ),
        )

        assertArrayEquals(small, klipy.download(gif, maxBytes = 100))
        // The size KLIPY gave rules out the full GIF without downloading it; the unsized one is cut off at the limit.
        assertEquals(listOf("/m.gif", "/t.gif"), requests.map { it.url.encodedPath })

        try {
            klipy.download(gif, maxBytes = 10)
            fail("Expected TooLarge")
        } catch (e: KlipyException) {
            assertSame(KlipyException.TooLarge, e)
        }
    }

    @Test
    fun `Ghost's config gives the key and filter, and no GIFs without a key`() {
        assertEquals(
            KlipyConfig("abc", "off"),
            klipyConfigFrom(json("""{"config":{"klipy":{"apiKey":" abc ","contentFilter":""}}}""")),
        )
        assertEquals(
            KlipyConfig("abc", "high"),
            klipyConfigFrom(json("""{"config":{"klipy":{"apiKey":"abc","contentFilter":"high"}}}""")),
        )
        assertNull(klipyConfigFrom(json("""{"config":{"klipy":{"apiKey":null,"contentFilter":"off"}}}""")))
        assertNull(klipyConfigFrom(json("""{"config":{"klipy":{"apiKey":"   "}}}""")))
        assertNull(klipyConfigFrom(json("""{"config":{"version":"6.10"}}""")))
    }
}
