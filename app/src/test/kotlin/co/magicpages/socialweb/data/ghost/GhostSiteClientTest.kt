package co.magicpages.socialweb.data.ghost

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostSiteClientTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `a Ghost account's site is read from its actor id`() {
        assertEquals("https://muratcorlu.com/", ghostSiteUrl("https://muratcorlu.com/.ghost/activitypub/users/index"))
        assertEquals("https://example.com/blog/", ghostSiteUrl("https://example.com/blog/.ghost/activitypub/users/index"))
        assertNull(ghostSiteUrl("https://mastodon.social/users/alice"))
        assertNull(ghostSiteUrl("http://example.com/.ghost/activitypub/users/index"))
        assertNull(ghostSiteUrl(null))
    }

    @Test
    fun `fedi pub links need a full handle`() {
        assertEquals("https://fedi.pub/@jannis@jannis.io", fediPubUrl("@jannis@jannis.io"))
        assertEquals("https://fedi.pub/@index@example.co.uk", fediPubUrl(" index@Example.co.uk "))
        assertNull(fediPubUrl("@jannis"))
        assertNull(fediPubUrl("@jannis@localhost"))
        assertNull(fediPubUrl("@a@b@example.com"))
        assertNull(fediPubUrl("@x@example.com/evil"))
    }

    @Test
    fun `fedi pub note links take the note's uuid and the author's handle`() {
        val uuid = "d1a83c75-dd07-4edb-8de6-d7789e170df3"
        assertEquals(
            "https://fedi.pub/@jannis@jannis.io/$uuid/",
            fediPubNoteUrl("https://www.jannis.io/.ghost/activitypub/note/$uuid", "@jannis@jannis.io"),
        )
        assertNull(fediPubNoteUrl("https://example.com/.ghost/activitypub/article/$uuid", "@index@example.com"))
        assertNull(fediPubNoteUrl("https://mastodon.social/users/a/statuses/1", "@a@mastodon.social"))
        assertNull(fediPubNoteUrl("https://example.com/.ghost/activitypub/note/$uuid/replies", "@index@example.com"))
        assertNull(fediPubNoteUrl("https://example.com/.ghost/activitypub/note/$uuid", "@index"))
    }

    @Test
    fun `sites are the same across www, scheme, case and a trailing slash`() {
        assertTrue(isSameSite("https://www.Example.com/", "http://example.com"))
        assertTrue(isSameSite("https://example.com/blog/", "https://example.com/blog"))
        assertFalse(isSameSite("https://example.com/blog", "https://example.com/"))
        assertFalse(isSameSite("https://example.com", "https://example.org"))
        assertFalse(isSameSite("not a url", "not a url"))
    }

    @Test
    fun `addresses read without scheme, www and trailing slash`() {
        assertEquals("example.com", displayAddress("https://www.example.com/"))
        assertEquals("example.com/blog", displayAddress("https://example.com/blog/"))
    }

    @Test
    fun `recommendations list keeps order, drops duplicates and entries without an address`() {
        val body = """
            [
              {"url":"https://www.abeautifulsite.net/","created_at":"2025-01-01T00:00:00.000Z"},
              {"title":"no url"},
              {"url":"mailto:me@example.com"},
              {"url":"https://jannis.io"},
              {"url":"https://www.abeautifulsite.net/"}
            ]
        """.trimIndent()

        assertEquals(listOf("https://www.abeautifulsite.net/", "https://jannis.io"), parseRecommendedUrls(body, json))
    }

    @Test
    fun `an empty or malformed recommendations file is no recommendations`() {
        assertEquals(emptyList<String>(), parseRecommendedUrls("[]", json))
        assertEquals(emptyList<String>(), parseRecommendedUrls("""{"url":"https://example.com"}""", json))
        assertEquals(emptyList<String>(), parseRecommendedUrls("<html>Not found</html>", json))
    }

    @Test
    fun `a Ghost site is told by allow_external_signup`() {
        val body = """
            {"site":{"title":"Murat","description":"Notes","logo":null,"icon":"/content/images/icon.png",
            "url":"https://muratcorlu.com/","version":"6.78","allow_external_signup":true}}
        """.trimIndent()

        val site = parseGhostSite(body, "https://muratcorlu.com/", json)!!

        assertEquals("Murat", site.title)
        assertEquals("Notes", site.description)
        assertEquals("https://muratcorlu.com/content/images/icon.png", site.iconUrl)
        assertTrue(site.allowsSubscribing)
    }

    @Test
    fun `a closed Ghost site doesn't offer subscribing`() {
        val site = parseGhostSite("""{"site":{"title":"Closed","allow_external_signup":false}}""", "https://closed.example/", json)!!

        assertFalse(site.allowsSubscribing)
    }

    @Test
    fun `answers that aren't from a Ghost site are not one`() {
        assertNull(parseGhostSite("""{"site":{"title":"Old Ghost"}}""", "https://old.example/", json))
        assertNull(parseGhostSite("<html></html>", "https://other.example/", json))
        assertNull(parseGhostSite("""["site"]""", "https://other.example/", json))
    }

    @Test
    fun `webfinger gives the account's real handle`() {
        val body = """
            {"subject":"acct:murat@muratcorlu.com","aliases":[],
             "links":[{"rel":"self","type":"application/activity+json","href":"https://muratcorlu.com/.ghost/activitypub/users/index"}]}
        """.trimIndent()

        assertEquals("@murat@muratcorlu.com", parseWebFingerHandle(body, json))
    }

    @Test
    fun `webfinger without an actor link gives no handle`() {
        val profilePageOnly = """
            {"subject":"acct:murat@muratcorlu.com","links":[{"rel":"http://webfinger.net/rel/profile-page","type":"text/html","href":"https://muratcorlu.com/"}]}
        """.trimIndent()

        assertNull(parseWebFingerHandle(profilePageOnly, json))
        assertNull(parseWebFingerHandle("not json", json))
    }

    @Test
    fun `a page head gives its name, description and the largest icon`() {
        val html = """
            <html><head>
              <title>Fallback title</title>
              <meta property="og:site_name" content="A Beautiful Site">
              <meta name="description" content="Plain description">
              <meta property="og:description" content="Social description">
              <link rel="icon" href="/favicon-16.png" sizes="16x16">
              <link rel="icon" href="/favicon-64.png" sizes="64x64">
            </head><body></body></html>
        """.trimIndent()

        val head = parsePageHead(html, "https://www.abeautifulsite.net/")

        assertEquals("A Beautiful Site", head.title)
        assertEquals("Social description", head.description)
        assertEquals("https://www.abeautifulsite.net/favicon-64.png", head.iconUrl)
    }

    @Test
    fun `a page head without social tags falls back to the title`() {
        val head = parsePageHead("<html><head><title> Hello </title></head></html>", "https://example.com/")

        assertEquals("Hello", head.title)
        assertNull(head.description)
        assertNull(head.iconUrl)
    }

    @Test
    fun `paths resolve under the site's directory`() {
        assertEquals("https://example.com/.well-known/recommendations.json", resolveUrl(".well-known/recommendations.json", "https://example.com/"))
        assertEquals("https://example.com/blog/members/api/site/", resolveUrl("members/api/site/", "https://example.com/blog"))
        assertEquals("https://example.com/favicon.ico", resolveUrl("/favicon.ico", "https://example.com/blog/"))
        assertNull(resolveUrl("javascript:alert(1)", "https://example.com/"))
    }
}
