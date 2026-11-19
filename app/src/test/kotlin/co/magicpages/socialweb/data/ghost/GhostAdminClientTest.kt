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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GhostAdminClientTest {
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

    @Test
    fun `site lookup follows the redirect to a separate admin domain`() = runTest {
        val ghost = client { request ->
            when (request.url.host) {
                "example.com" -> respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "https://admin.example.com/ghost/api/admin/site/"))
                else -> respond(
                    """{"site":{"title":"Example","url":"https://example.com","version":"6.78","icon":null,"locale":"en"}}""",
                    HttpStatusCode.OK,
                )
            }
        }

        val found = ghost.lookUpSite("example.com")

        assertEquals("https://example.com/", found.siteUrl)
        assertEquals("https://admin.example.com/ghost/api/admin/", found.adminApiUrl)
        assertEquals("https://admin.example.com", found.adminOrigin)
    }

    @Test
    fun `site lookup refuses Ghost versions without the social web`() = runTest {
        val ghost = client { respond("""{"site":{"title":"Old","url":"https://old.example","version":"5.130"}}""", HttpStatusCode.OK) }

        try {
            ghost.lookUpSite("old.example")
            fail("Expected UnsupportedVersion")
        } catch (e: GhostException.UnsupportedVersion) {
            assertEquals("5.130", e.version)
        }
    }

    @Test
    fun `email code flow replays the pending session cookie verbatim with the admin origin`() = runTest {
        val cookie = "ghost-admin-api-session=s%3Aabc.def"
        val ghost = client { request ->
            when (request.method) {
                HttpMethod.Post -> respond(
                    """{"errors":[{"message":"User must verify session to login.","type":"Needs2FAError","code":"2FA_NEW_DEVICE_DETECTED"}]}""",
                    HttpStatusCode.Forbidden,
                    headersOf(HttpHeaders.SetCookie, "$cookie; Path=/ghost; HttpOnly; SameSite=Lax"),
                )
                else -> respond("", HttpStatusCode.OK)
            }
        }

        val result = ghost.signIn(site, " owner@example.com ", "hunter2")
        assertTrue(result is SignInResult.NeedsVerification)
        val credential = ghost.verifySession(site, (result as SignInResult.NeedsVerification).pendingCookies, "123456")

        val signIn = requests[0]
        assertEquals("""{"username":"owner@example.com","password":"hunter2"}""", signIn.body.toByteArray().decodeToString())
        val verify = requests[1]
        assertEquals(HttpMethod.Put, verify.method)
        assertEquals("https://admin.example.com/ghost/api/admin/session/verify/", verify.url.toString())
        assertEquals(cookie, verify.headers[HttpHeaders.Cookie])
        assertEquals("https://admin.example.com", verify.headers[HttpHeaders.Origin])
        assertEquals("""{"token":"123456"}""", verify.body.toByteArray().decodeToString())
        assertEquals(Credential.Session(mapOf("ghost-admin-api-session" to "s%3Aabc.def")), credential)
    }

    @Test
    fun `wrong password surfaces Ghost's own message`() = runTest {
        val ghost = client {
            respond("""{"errors":[{"message":"Your password is incorrect.","type":"ValidationError"}]}""", HttpStatusCode.UnprocessableEntity)
        }

        try {
            ghost.signIn(site, "owner@example.com", "wrong")
            fail("Expected Rejected")
        } catch (e: GhostException.Rejected) {
            assertEquals("Your password is incorrect.", e.ghostMessage)
        }
    }

    @Test
    fun `identity token uses a Ghost staff JWT for staff tokens`() = runTest {
        val ghost = client { respond("""{"identities":[{"token":"ap-jwt"}]}""", HttpStatusCode.OK) }

        val token = ghost.identityToken(site, Credential.StaffToken("5f4e3d2c1b0a998877665544:a1b2"))

        assertEquals("ap-jwt", token)
        assertTrue(requests.single().headers[HttpHeaders.Authorization]!!.startsWith("Ghost ey"))
        assertNull(requests.single().headers[HttpHeaders.Cookie])
    }

    @Test
    fun `site input normalization`() {
        assertEquals("https://example.com/", normalizeSiteInput(" example.com "))
        assertEquals("https://example.com/blog/", normalizeSiteInput("https://example.com/blog"))
        assertEquals("https://example.com/", normalizeSiteInput("https://example.com/ghost/#/dashboard"))
        assertEquals("https://example.com/blog/", normalizeSiteInput("example.com/blog/ghost/"))
        assertEquals("https://ghostly.example/", normalizeSiteInput("ghostly.example"))
        assertEquals("http://localhost:2368/", normalizeSiteInput("http://localhost:2368"))
        assertNull(normalizeSiteInput("   "))
        assertNull(normalizeSiteInput("not a url"))
        assertNull(normalizeSiteInput("http://localhost:87870.0.2.2:8787"))
        assertNull(normalizeSiteInput("example.com:99999"))
        assertNull(normalizeSiteInput("ftp://example.com"))
    }
}
