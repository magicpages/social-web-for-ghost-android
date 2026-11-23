package co.magicpages.socialweb.data.member

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cookies as a function of the URL; [cleared] collects the sites whose member session was cleared. */
private class FakeCookies(private val read: (String) -> String?) : SiteCookies {
    val cleared = mutableListOf<String>()

    override fun header(url: String): String? = read(url)

    override fun clearMemberSession(url: String) {
        cleared += url
    }
}

class MemberArticlesTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val memberCookie = "ghost-members-ssr=token123; ghost-members-ssr.sig=sig456"

    private val first = "This is the first paragraph of the free preview of the post."
    private val second = "The second paragraph is still public and ends the preview here."
    private val third = "Only members get this third paragraph, which is long enough to be real content."
    private val publicPart = """
        <p>$first</p><p>$second</p>
        <div class="gh-paid-content-notice"><h3>This post is for subscribers only</h3><p>Become a member.</p></div>
    """.trimIndent()
    private val fullPage = "<html><body><section class=\"gh-content\"><p>$first</p><p>$second</p><p>$third</p></section></body></html>"
    private val postUrl = "https://example.com/members-post/"

    private fun articles(
        cookies: (String) -> String? = { url -> if ("example.com" in url) memberCookie else null },
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): MemberArticles = MemberArticles(
        HttpClient(MockEngine { request -> requests += request; handler(request) }) { expectSuccess = false },
        MemberSessions(FakeCookies(cookies)),
    )

    @Test
    fun `a member gets the whole article, requested with the member cookies`() = runTest {
        val loader = articles { respond(fullPage, HttpStatusCode.OK) }

        val result = loader.load(postUrl, publicPart)

        assertTrue(result is FullArticle.Html)
        assertTrue(third in (result as FullArticle.Html).html)
        assertEquals(memberCookie, requests.single().headers[HttpHeaders.Cookie])
    }

    @Test
    fun `without a member session nothing is requested`() = runTest {
        val loader = articles(cookies = { null }) { respond(fullPage, HttpStatusCode.OK) }

        assertEquals(FullArticle.SignedOut, loader.load(postUrl, publicPart))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a page that still stops at the gate leaves the article unavailable`() = runTest {
        val gated = "<html><body><section class=\"gh-content\"><p>$first</p><p>$second</p></section></body></html>"
        val loader = articles { respond(gated, HttpStatusCode.OK) }

        assertEquals(FullArticle.Unavailable, loader.load(postUrl, publicPart))
    }

    @Test
    fun `an error page or a missing page leaves the article unavailable`() = runTest {
        assertEquals(FullArticle.Unavailable, articles { respond("nope", HttpStatusCode.NotFound) }.load(postUrl, publicPart))
        assertEquals(FullArticle.Unavailable, articles { respond("<html><body>oops</body></html>", HttpStatusCode.OK) }.load(postUrl, publicPart))
    }

    @Test
    fun `an article that isn't served over https is not requested`() = runTest {
        val loader = articles { respond(fullPage, HttpStatusCode.OK) }

        assertEquals(FullArticle.Unavailable, loader.load("http://example.com/members-post/", publicPart))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `redirects within the site are followed with the cookies`() = runTest {
        val loader = articles { request ->
            if (request.url.encodedPath == "/members-post/") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://www.example.com/p/members-post/"))
            } else {
                respond(fullPage, HttpStatusCode.OK)
            }
        }

        val result = loader.load(postUrl, publicPart)

        assertTrue(result is FullArticle.Html)
        assertEquals(2, requests.size)
        assertEquals(memberCookie, requests.last().headers[HttpHeaders.Cookie])
    }

    @Test
    fun `a redirect to another site never carries the cookies there`() = runTest {
        val loader = articles { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example.org/steal")) }

        assertEquals(FullArticle.Unavailable, loader.load(postUrl, publicPart))
        assertEquals(1, requests.size)
        assertTrue(requests.none { it.url.host == "evil.example.org" })
    }

    @Test
    fun `only the exact member cookie counts as a session`() {
        assertTrue(hasMemberCookie(memberCookie))
        assertTrue(hasMemberCookie("theme=dark;ghost-members-ssr=abc"))
        assertFalse(hasMemberCookie("ghost-members-ssr.sig=sig456"))
        assertFalse(hasMemberCookie("x-ghost-members-ssr=abc"))
        assertFalse(hasMemberCookie("ghost-members-ssr="))
        assertFalse(hasMemberCookie("ghost-members-ssr"))
        assertFalse(hasMemberCookie(""))
        assertFalse(hasMemberCookie(null))
    }

    @Test
    fun `sessions are read per site from the browser's cookies`() = runTest {
        val sessions = MemberSessions(FakeCookies { url -> if (url.startsWith("https://example.com")) memberCookie else null })

        assertTrue(sessions.hasSession("https://example.com/some-post/"))
        assertFalse(sessions.hasSession("https://other.example/"))
        assertEquals(memberCookie, sessions.cookieHeader("https://example.com/"))
        assertNull(sessions.cookieHeader("https://other.example/"))
    }

    @Test
    fun `signing out clears that site's session and announces the change`() = runTest {
        val cookies = FakeCookies { memberCookie }
        val sessions = MemberSessions(cookies)
        val before = sessions.changes.value

        sessions.signOut("https://example.com/")

        assertEquals(listOf("https://example.com/"), cookies.cleared)
        assertEquals(before + 1, sessions.changes.value)
    }

    @Test
    fun `the session and its signature are the cookies a sign out expires`() {
        assertEquals(listOf("ghost-members-ssr", "ghost-members-ssr.sig"), memberCookieNames("a=1; $memberCookie; b=2"))
        assertEquals(emptyList<String>(), memberCookieNames("a=1; b=2"))
        assertEquals(emptyList<String>(), memberCookieNames(null))
    }

    @Test
    fun `closing the browser announces that sessions may have changed`() {
        val sessions = MemberSessions(FakeCookies { null })
        val before = sessions.changes.value

        sessions.notifyChanged()

        assertEquals(before + 1, sessions.changes.value)
    }

    @Test
    fun `subscribed needs the cookie and the site's agreement`() {
        assertFalse(isMember(hasCookie = false, memberApiStatus = 200))
        assertFalse(isMember(hasCookie = false, memberApiStatus = null))
        assertTrue(isMember(hasCookie = true, memberApiStatus = 200))
        // Signed out or expired: Ghost answers 204 (older versions 401 or 404).
        assertFalse(isMember(hasCookie = true, memberApiStatus = 204))
        assertFalse(isMember(hasCookie = true, memberApiStatus = 401))
        assertFalse(isMember(hasCookie = true, memberApiStatus = 404))
        // The site can't say: the cookie it set is all there is.
        assertTrue(isMember(hasCookie = true, memberApiStatus = null))
        assertTrue(isMember(hasCookie = true, memberApiStatus = 503))
    }
}
