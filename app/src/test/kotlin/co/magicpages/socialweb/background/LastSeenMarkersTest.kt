package co.magicpages.socialweb.background

import co.magicpages.socialweb.data.account.Credential
import co.magicpages.socialweb.data.account.GhostSite
import co.magicpages.socialweb.data.account.Profile
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastSeenMarkersTest {
    private class MapValues(val map: MutableMap<String, String> = mutableMapOf()) : KeyValues {
        override fun get(key: String): String? = map[key]

        override fun set(vararg changes: Pair<String, String?>) {
            for ((key, value) in changes) if (value == null) map.remove(key) else map[key] = value
        }
    }

    private fun account(site: String, handle: String) = StoredAccount(
        site = GhostSite(title = site, siteUrl = "https://$site/", adminApiUrl = "https://$site/ghost/api/admin/", version = "6.0"),
        credential = Credential.StaffToken("t"),
        profile = Profile(name = site, handle = handle),
    )

    private val a = account("a.example.com", "@index@a.example.com")
    private val b = account("b.example.com", "@index@b.example.com")

    private val values = MapValues()
    private val markers = LastSeenMarkers(values)

    @Test
    fun `an account without a marker has none`() {
        assertNull(markers.get(a))
    }

    @Test
    fun `markers are kept apart per account`() {
        markers.set(a, "10")
        markers.set(b, "99")

        assertEquals("10", markers.get(a))
        assertEquals("99", markers.get(b))
    }

    @Test
    fun `forgetting one account keeps the other's marker`() {
        markers.set(a, "10")
        markers.set(b, "99")

        markers.forget(a)

        assertNull(markers.get(a))
        assertEquals("99", markers.get(b))
    }

    @Test
    fun `the single marker of earlier versions moves to the account with its handle`() {
        values.map["last_seen_handle"] = a.profile.handle
        values.map["last_seen_id"] = "42"

        assertEquals("42", markers.get(a))
        // Moved, not copied: the old keys are gone and the account has its own marker now.
        assertNull(values.map["last_seen_id"])
        assertNull(values.map["last_seen_handle"])
        assertEquals("42", markers.get(a))
        assertNull(markers.get(b))
    }

    @Test
    fun `the single marker is not handed to another account`() {
        values.map["last_seen_handle"] = a.profile.handle
        values.map["last_seen_id"] = "42"

        assertNull(markers.get(b))
        // Still there for the account it belongs to.
        assertEquals("42", markers.get(a))
    }

    @Test
    fun `an advanced marker replaces the single marker of earlier versions`() {
        values.map["last_seen_handle"] = a.profile.handle
        values.map["last_seen_id"] = "42"

        markers.set(a, "50")

        assertEquals("50", markers.get(a))
        assertNull(values.map["last_seen_id"])
        assertNull(values.map["last_seen_handle"])
    }

    @Test
    fun `forgetting an account also drops the single marker that was its own`() {
        values.map["last_seen_handle"] = a.profile.handle
        values.map["last_seen_id"] = "42"

        markers.forget(a)

        assertNull(markers.get(a))
    }

    // Announcing per account: each account's page is judged against its own marker.

    private fun like(id: String) = Notification(id = id, type = Notification.Type.Like, actor = Author(id = "x", handle = "@x@example.com"))

    private fun reply(id: String) = Notification(id = id, type = Notification.Type.Reply, actor = Author(id = "x", handle = "@x@example.com"))

    private val all = Notification.Type.entries.toSet()

    private fun List<Notification>.ids() = map { it.id }

    @Test
    fun `each account announces what is above its own marker`() {
        markers.set(a, "10")
        markers.set(b, "20")
        val pageA = listOf(reply("12"), like("11"), like("10"))
        val pageB = listOf(reply("22"), like("20"), like("19"))

        val outcomeA = pageA.checkFor(a, markers, all)
        val outcomeB = pageB.checkFor(b, markers, all)

        assertEquals(listOf("12", "11"), outcomeA.announce.ids())
        assertEquals(listOf("22"), outcomeB.announce.ids())
        assertEquals("12", markers.get(a))
        assertEquals("22", markers.get(b))
    }

    @Test
    fun `an account's new items do not hide another account's`() {
        markers.set(a, "5")
        markers.set(b, "5")
        // The sites number their notifications independently; a high number on one means nothing on the other.
        val pageA = listOf(reply("900"))
        val pageB = listOf(reply("7"))

        assertEquals(listOf("900"), pageA.checkFor(a, markers, all).announce.ids())
        assertEquals(listOf("7"), pageB.checkFor(b, markers, all).announce.ids())
    }

    @Test
    fun `the first check of an added account announces nothing and does not disturb the others`() {
        markers.set(a, "10")

        val first = listOf(reply("300"), like("299")).checkFor(b, markers, all)

        assertEquals(emptyList<Notification>(), first.announce)
        assertEquals("300", markers.get(b))
        assertEquals("10", markers.get(a))
    }

    @Test
    fun `the type filter applies to every account`() {
        markers.set(a, "10")
        markers.set(b, "10")
        val onlyReplies = setOf(Notification.Type.Reply)

        val outcomeA = listOf(reply("12"), like("11")).checkFor(a, markers, onlyReplies)
        val outcomeB = listOf(like("14"), reply("13")).checkFor(b, markers, onlyReplies)

        assertEquals(listOf("12"), outcomeA.announce.ids())
        assertEquals(listOf("13"), outcomeB.announce.ids())
        // Filtered items are passed over, not kept for later.
        assertEquals("12", markers.get(a))
        assertEquals("14", markers.get(b))
    }

    @Test
    fun `the single marker of earlier versions decides the first check of its account`() {
        values.map["last_seen_handle"] = a.profile.handle
        values.map["last_seen_id"] = "10"

        val outcome = listOf(reply("12"), like("10")).checkFor(a, markers, all)

        assertEquals(listOf("12"), outcome.announce.ids())
        assertEquals("12", markers.get(a))
    }
}
