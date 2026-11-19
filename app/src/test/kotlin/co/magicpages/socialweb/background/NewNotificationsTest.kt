package co.magicpages.socialweb.background

import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Notification
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class NewNotificationsTest {
    private fun notification(id: String) = Notification(id = id, actor = Author(id = "a", handle = "@a@example.com"))

    private fun ids(list: List<Notification>) = list.map { it.id }

    @Test
    fun `keeps only notifications above the marker`() {
        val page = listOf("12", "11", "10", "9").map(::notification)
        assertEquals(listOf("12", "11"), ids(page.newerThan("10")))
    }

    @Test
    fun `numeric ids compare by value not text`() {
        val page = listOf("100", "99", "9").map(::notification)
        assertEquals(listOf("100", "99"), ids(page.newerThan("9")))
    }

    @Test
    fun `nothing new when the marker is the newest`() {
        val page = listOf("12", "11").map(::notification)
        assertEquals(emptyList<String>(), ids(page.newerThan("12")))
    }

    @Test
    fun `a marker that was deleted still separates old from new`() {
        val page = listOf("12", "9", "8").map(::notification)
        assertEquals(listOf("12"), ids(page.newerThan("10")))
    }

    @Test
    fun `everything is new when the whole page is above the marker`() {
        val page = listOf("30", "29").map(::notification)
        assertEquals(listOf("30", "29"), ids(page.newerThan("0")))
    }

    @Test
    fun `non-numeric ids stop at the marker`() {
        val page = listOf("c", "b", "a").map(::notification)
        assertEquals(listOf("c"), ids(page.newerThan("b")))
    }

    private fun typed(id: String, type: Notification.Type) =
        Notification(id = id, type = type, actor = Author(id = "a", handle = "@a@example.com"))

    private val page = listOf(
        typed("15", Notification.Type.Like),
        typed("14", Notification.Type.Reply),
        typed("13", Notification.Type.Follow),
        typed("12", Notification.Type.Unknown),
        typed("11", Notification.Type.Mention),
    )

    @Test
    fun `announces only the chosen types`() {
        val outcome = page.check("11", setOf(Notification.Type.Reply, Notification.Type.Follow))
        assertEquals(listOf("14", "13"), ids(outcome.announce))
    }

    @Test
    fun `the marker moves over notifications that were not announced`() {
        val outcome = page.check("11", setOf(Notification.Type.Reply))
        assertEquals("15", outcome.marker)
    }

    @Test
    fun `the marker still advances when no type is switched on`() {
        val outcome = page.check("11", emptySet())
        assertEquals("15", outcome.marker)
        assertEquals(emptyList<String>(), ids(outcome.announce))
    }

    @Test
    fun `a type switched back on does not bring back what arrived while it was off`() {
        val first = page.check("11", setOf(Notification.Type.Reply))
        val second = page.check(first.marker, Notification.Type.entries.toSet())
        assertEquals(emptyList<String>(), ids(second.announce))
        assertNull(second.marker)
    }

    @Test
    fun `unknown kinds are never announced`() {
        val outcome = page.check("11", Notification.Type.entries.toSet())
        assertEquals(listOf("15", "14", "13"), ids(outcome.announce))
    }

    @Test
    fun `nothing new leaves the stored marker alone`() {
        val outcome = page.check("15", Notification.Type.entries.toSet())
        assertNull(outcome.marker)
        assertEquals(emptyList<String>(), ids(outcome.announce))
    }

    @Test
    fun `the first check announces nothing and starts at the newest`() {
        val outcome = page.check(null, Notification.Type.entries.toSet())
        assertEquals("15", outcome.marker)
        assertEquals(emptyList<String>(), ids(outcome.announce))
    }

    @Test
    fun `the first check on an empty page starts at zero`() {
        assertEquals("0", emptyList<Notification>().check(null, Notification.Type.entries.toSet()).marker)
    }
}
