package co.magicpages.socialweb.data.ghost

import co.magicpages.socialweb.data.account.Credential
import co.magicpages.socialweb.data.account.GhostSite
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GhostRecommendationsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GhostAdminClient(
            HttpClient(MockEngine { request -> requests += request; handler(request) }) { expectSuccess = false },
            json,
        )

    private val site = GhostSite(
        title = "Example",
        siteUrl = "https://example.com/",
        adminApiUrl = "https://admin.example.com/ghost/api/admin/",
        version = "6.78",
    )
    private val session = Credential.Session(mapOf("ghost-admin-api-session" to "abc"))

    private suspend fun HttpRequestData.bodyJson(): JsonObject = json.parseToJsonElement(String(body.toByteArray())).jsonObject

    private fun JsonObject.firstRecommendation(): JsonObject = (getValue("recommendations") as JsonArray).first().jsonObject

    @Test
    fun `own recommendations are read with the staff session`() = runTest {
        val ghost = client {
            respond(
                """{"recommendations":[{"id":"r1","url":"https://www.abeautifulsite.net/","title":"A Beautiful Site","one_click_subscribe":true,"featured_image":null}]}""",
                HttpStatusCode.OK,
            )
        }

        val list = ghost.recommendations(site, session)

        assertEquals(HttpMethod.Get, requests.single().method)
        assertEquals("https://admin.example.com/ghost/api/admin/recommendations/?limit=all", requests.single().url.toString())
        assertTrue(requests.single().headers[HttpHeaders.Cookie]!!.contains("ghost-admin-api-session=abc"))
        assertEquals("r1", list.single().id)
        assertEquals("A Beautiful Site", list.single().title)
        assertTrue(list.single().oneClickSubscribe)
    }

    @Test
    fun `recommending checks the site, then adds what the check found`() = runTest {
        val ghost = client { request ->
            if (request.url.encodedPath.endsWith("/recommendations/check/")) {
                respond(
                    """{"recommendations":[{"url":"https://jannis.io/","title":"Jannis","description":"Notes","excerpt":"Hello","featured_image":"https://jannis.io/cover.png","favicon":"https://jannis.io/icon.png","one_click_subscribe":true}]}""",
                    HttpStatusCode.OK,
                )
            } else {
                respond(
                    """{"recommendations":[{"id":"new1","url":"https://jannis.io/","title":"Jannis"}]}""",
                    HttpStatusCode.Created,
                )
            }
        }

        val added = ghost.recommend(site, session, "https://www.jannis.io")

        assertEquals("new1", added.id)
        assertEquals(2, requests.size)
        val (check, add) = requests
        assertEquals(HttpMethod.Post, check.method)
        assertEquals("https://admin.example.com/ghost/api/admin/recommendations/check/", check.url.toString())
        assertEquals("https://www.jannis.io", check.bodyJson().firstRecommendation().getValue("url").let { (it as JsonPrimitive).content })
        assertEquals(HttpMethod.Post, add.method)
        assertEquals("https://admin.example.com/ghost/api/admin/recommendations/", add.url.toString())
        val sent = add.bodyJson().firstRecommendation()
        assertEquals("https://jannis.io/", (sent.getValue("url") as JsonPrimitive).content)
        assertEquals("Jannis", (sent.getValue("title") as JsonPrimitive).content)
        assertEquals("Notes", (sent.getValue("description") as JsonPrimitive).content)
        assertEquals("Hello", (sent.getValue("excerpt") as JsonPrimitive).content)
        assertEquals("https://jannis.io/cover.png", (sent.getValue("featured_image") as JsonPrimitive).content)
        assertEquals("https://jannis.io/icon.png", (sent.getValue("favicon") as JsonPrimitive).content)
        assertTrue((sent.getValue("one_click_subscribe") as JsonPrimitive).boolean)
        assertTrue(add.headers[HttpHeaders.Cookie]!!.contains("ghost-admin-api-session=abc"))
    }

    @Test
    fun `a site that is recommended already is not added a second time`() = runTest {
        val ghost = client {
            respond("""{"recommendations":[{"id":"existing","url":"https://jannis.io/","title":"Jannis"}]}""", HttpStatusCode.OK)
        }

        val found = ghost.recommend(site, session, "https://jannis.io/")

        assertEquals("existing", found.id)
        assertEquals(1, requests.size)
        assertTrue(requests.single().url.encodedPath.endsWith("/recommendations/check/"))
    }

    @Test
    fun `a title-less check falls back to the site's host and sends explicit nulls`() {
        val draft = AdminRecommendation(url = "https://jannis.io/")

        val sent = json.parseToJsonElement(recommendationAddBody(draft, "https://www.jannis.io")).jsonObject.firstRecommendation()

        assertEquals("www.jannis.io", (sent.getValue("title") as JsonPrimitive).content)
        assertEquals(JsonNull, sent.getValue("description"))
        assertEquals(JsonNull, sent.getValue("featured_image"))
        assertFalse((sent.getValue("one_click_subscribe") as JsonPrimitive).boolean)
    }

    @Test
    fun `stopping a recommendation deletes it by id`() = runTest {
        val ghost = client { respond("", HttpStatusCode.NoContent) }

        ghost.removeRecommendation(site, session, "r1")

        assertEquals(HttpMethod.Delete, requests.single().method)
        assertEquals("https://admin.example.com/ghost/api/admin/recommendations/r1/", requests.single().url.toString())
        assertTrue(requests.single().headers[HttpHeaders.Cookie]!!.contains("ghost-admin-api-session=abc"))
    }

    @Test
    fun `Ghost refusing a recommendation surfaces as an error`() = runTest {
        val ghost = client {
            respond("""{"errors":[{"message":"No permission to call this endpoint.","type":"NoPermissionError"}]}""", HttpStatusCode.Forbidden)
        }

        try {
            ghost.recommend(site, session, "https://jannis.io/")
            fail("Expected an error")
        } catch (_: GhostException) {
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun `a site is recommended when any entry is the same site`() {
        val list = listOf(
            AdminRecommendation(id = "a", url = "https://www.abeautifulsite.net/"),
            AdminRecommendation(id = "b", url = null),
        )

        assertTrue(list.recommends("https://abeautifulsite.net"))
        assertTrue(list.recommends("http://WWW.ABEAUTIFULSITE.NET/"))
        assertFalse(list.recommends("https://abeautifulsite.net/blog"))
        assertFalse(emptyList<AdminRecommendation>().recommends("https://abeautifulsite.net"))
    }
}
