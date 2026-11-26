package co.magicpages.socialweb.data.ghost

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FediPubLinksTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val uuid = "d1a83c75-dd07-4edb-8de6-d7789e170df3"

    @Test
    fun `a profile link opens the profile, with or without a trailing slash`() {
        val profile = FediPubLink.Profile("@jannis@jannis.io")
        assertEquals(profile, parseFediPubLink("https://fedi.pub/@jannis@jannis.io"))
        assertEquals(profile, parseFediPubLink("https://fedi.pub/@jannis@jannis.io/"))
    }

    @Test
    fun `a note link opens the note, with or without a trailing slash`() {
        val note = FediPubLink.Note("@jannis@jannis.io", uuid)
        assertEquals(note, parseFediPubLink("https://fedi.pub/@jannis@jannis.io/$uuid/"))
        assertEquals(note, parseFediPubLink("https://fedi.pub/@jannis@jannis.io/$uuid"))
    }

    @Test
    fun `the links the app shares read back as what they were made from`() {
        val noteUrl = fediPubNoteUrl("https://www.jannis.io/.ghost/activitypub/note/$uuid", "@jannis@jannis.io")!!
        assertEquals(FediPubLink.Note("@jannis@jannis.io", uuid), parseFediPubLink(noteUrl))
        assertEquals(FediPubLink.Profile("@index@example.co.uk"), parseFediPubLink(fediPubUrl("index@Example.co.uk")!!))
    }

    @Test
    fun `percent-encoded paths are read decoded`() {
        assertEquals(FediPubLink.Profile("@jannis@jannis.io"), parseFediPubLink("https://fedi.pub/%40jannis%40jannis.io"))
        assertEquals(FediPubLink.Note("@jannis@jannis.io", uuid), parseFediPubLink("https://fedi.pub/@jannis%40jannis.io/$uuid/"))
        // Decoded, these aren't a handle's characters.
        assertNull(parseFediPubLink("https://fedi.pub/@j%C3%BCrgen@example.com"))
        assertNull(parseFediPubLink("https://fedi.pub/@jannis@jannis.io%2Fevil"))
    }

    @Test
    fun `host and domain case don't matter`() {
        assertEquals(FediPubLink.Profile("@jannis@jannis.io"), parseFediPubLink("https://FEDI.PUB/@jannis@Jannis.IO"))
        assertEquals(FediPubLink.Note("@Jannis@jannis.io", uuid), parseFediPubLink("https://Fedi.Pub/@Jannis@JANNIS.io/$uuid/"))
    }

    @Test
    fun `a query string or fragment is ignored`() {
        assertEquals(FediPubLink.Profile("@jannis@jannis.io"), parseFediPubLink("https://fedi.pub/@jannis@jannis.io?utm_source=mastodon"))
        assertEquals(FediPubLink.Note("@jannis@jannis.io", uuid), parseFediPubLink("https://fedi.pub/@jannis@jannis.io/$uuid/?ref=share#replies"))
    }

    @Test
    fun `extra path segments are not ours`() {
        assertNull(parseFediPubLink("https://fedi.pub/@jannis@jannis.io/$uuid/replies"))
        assertNull(parseFediPubLink("https://fedi.pub/@jannis@jannis.io/$uuid/replies/"))
        assertNull(parseFediPubLink("https://fedi.pub/@jannis@jannis.io//$uuid"))
        assertNull(parseFediPubLink("https://fedi.pub/@jannis@jannis.io/feed.xml"))
    }

    @Test
    fun `paths that don't start with an at sign are not ours`() {
        assertNull(parseFediPubLink("https://fedi.pub/"))
        assertNull(parseFediPubLink("https://fedi.pub"))
        assertNull(parseFediPubLink("https://fedi.pub/?handle=@jannis@jannis.io"))
        assertNull(parseFediPubLink("https://fedi.pub/generate"))
        assertNull(parseFediPubLink("https://fedi.pub/users/@jannis@jannis.io"))
    }

    @Test
    fun `incomplete handles and other sites are not ours`() {
        assertNull(parseFediPubLink("https://fedi.pub/@jannis"))
        assertNull(parseFediPubLink("https://fedi.pub/@jannis@localhost"))
        assertNull(parseFediPubLink("https://fedi.pub/@a@b@example.com"))
        assertNull(parseFediPubLink("http://fedi.pub/@jannis@jannis.io"))
        assertNull(parseFediPubLink("https://www.fedi.pub/@jannis@jannis.io"))
        assertNull(parseFediPubLink("https://fedi.pub.example.com/@jannis@jannis.io"))
        assertNull(parseFediPubLink("https://mastodon.social/@jannis@jannis.io"))
        assertNull(parseFediPubLink("https://someone@fedi.pub/@jannis@jannis.io"))
        assertNull(parseFediPubLink("not a link"))
    }

    @Test
    fun `a note's id is built on its author's site`() {
        assertEquals(
            "https://www.jannis.io/.ghost/activitypub/note/$uuid",
            ghostNoteId("https://www.jannis.io/.ghost/activitypub/users/index", uuid),
        )
        assertEquals(
            "https://example.com/blog/.ghost/activitypub/note/$uuid",
            ghostNoteId("https://example.com/blog/.ghost/activitypub/users/index", uuid),
        )
        // Not shaped like Ghost's: its origin.
        assertEquals("https://ap.example.com/.ghost/activitypub/note/$uuid", ghostNoteId("https://AP.example.com/users/index", uuid))
        assertNull(ghostNoteId("http://example.com/.ghost/activitypub/users/index", uuid))
        assertNull(ghostNoteId("not an id", uuid))
    }

    @Test
    fun `webfinger gives the actor its self link points to`() {
        val body = """
            {"subject":"acct:jannis@jannis.io","links":[
              {"rel":"http://webfinger.net/rel/profile-page","type":"text/html","href":"https://www.jannis.io/"},
              {"rel":"self","type":"application/activity+json","href":"https://www.jannis.io/.ghost/activitypub/users/index"}
            ]}
        """.trimIndent()

        assertEquals("https://www.jannis.io/.ghost/activitypub/users/index", parseWebFingerActor(body, json))
    }

    @Test
    fun `webfinger without an https actor link gives no actor`() {
        val profilePageOnly = """{"links":[{"rel":"http://webfinger.net/rel/profile-page","type":"text/html","href":"https://jannis.io/"}]}"""
        val cleartext = """{"links":[{"rel":"self","type":"application/activity+json","href":"http://jannis.io/users/index"}]}"""

        assertNull(parseWebFingerActor(profilePageOnly, json))
        assertNull(parseWebFingerActor(cleartext, json))
        assertNull(parseWebFingerActor("not json", json))
    }
}
