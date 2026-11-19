package co.magicpages.socialweb.ui.notifications

import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Notification.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationGroupsTest {
    private val ana = Author(id = "1", handle = "@ana@one.example", name = "Ana")
    private val ben = Author(id = "2", handle = "@ben@two.example", name = "Ben")
    private val cleo = Author(id = "3", handle = "@cleo@three.example", name = "Cleo")
    private val dan = Author(id = "4", handle = "@dan@four.example", name = "Dan")

    private fun item(id: String, type: Type, actor: Author, postId: String? = null) = NotificationItem(
        id = id,
        type = type,
        actor = actor,
        createdAtMillis = null,
        post = null,
        postId = postId,
        postUrl = null,
        inReplyTo = null,
    )

    private fun like(id: String, actor: Author, postId: String) = item(id, Type.Like, actor, postId)
    private fun repost(id: String, actor: Author, postId: String) = item(id, Type.Repost, actor, postId)
    private fun follow(id: String, actor: Author) = item(id, Type.Follow, actor)
    private fun reply(id: String, actor: Author, postId: String) = item(id, Type.Reply, actor, postId)

    private fun List<NotificationGroup>.ids() = map { it.id }
    private fun List<NotificationGroup>.names() = map { group -> group.actors.map { it.name } }

    @Test
    fun `likes of the same post merge into one row with every liker`() {
        val rows = groupNotifications(listOf(like("a", ana, "p"), like("b", ben, "p"), like("c", cleo, "p")))
        assertEquals(listOf("a"), rows.ids())
        assertEquals(listOf(listOf("Ana", "Ben", "Cleo")), rows.names())
        assertEquals(3, rows.single().actors.size)
    }

    @Test
    fun `likes of different posts stay apart`() {
        val rows = groupNotifications(listOf(like("a", ana, "p"), like("b", ben, "q")))
        assertEquals(listOf("a", "b"), rows.ids())
        assertEquals(listOf(1, 1), rows.map { it.actors.size })
    }

    @Test
    fun `likes of one post merge even with other rows between them`() {
        val rows = groupNotifications(
            listOf(like("a", ana, "p"), like("b", ben, "q"), like("c", cleo, "p")),
        )
        assertEquals(listOf("a", "b"), rows.ids())
        assertEquals(listOf(listOf("Ana", "Cleo"), listOf("Ben")), rows.names())
    }

    @Test
    fun `a like and a repost of the same post are separate rows`() {
        val rows = groupNotifications(listOf(like("a", ana, "p"), repost("b", ben, "p"), repost("c", cleo, "p")))
        assertEquals(listOf("a", "b"), rows.ids())
        assertEquals(listOf(Type.Like, Type.Repost), rows.map { it.type })
        assertEquals(listOf(1, 2), rows.map { it.actors.size })
    }

    @Test
    fun `replies and mentions are never merged`() {
        val rows = groupNotifications(
            listOf(
                reply("a", ana, "p"),
                reply("b", ana, "p"),
                item("c", Type.Mention, ben, "p"),
                item("d", Type.Mention, ben, "p"),
            ),
        )
        assertEquals(listOf("a", "b", "c", "d"), rows.ids())
        assertTrue(rows.all { it.actors.size == 1 })
    }

    @Test
    fun `follows next to each other merge`() {
        val rows = groupNotifications(listOf(follow("a", ana), follow("b", ben), follow("c", cleo)))
        assertEquals(listOf("a"), rows.ids())
        assertEquals(listOf(listOf("Ana", "Ben", "Cleo")), rows.names())
    }

    @Test
    fun `a row between two follows splits them`() {
        val rows = groupNotifications(
            listOf(follow("a", ana), follow("b", ben), reply("c", cleo, "p"), follow("d", dan)),
        )
        assertEquals(listOf("a", "c", "d"), rows.ids())
        assertEquals(listOf(2, 1, 1), rows.map { it.actors.size })
    }

    @Test
    fun `a like folded into an earlier row does not split a run of follows`() {
        val rows = groupNotifications(
            listOf(like("a", ana, "p"), follow("b", ben), like("c", cleo, "p"), follow("d", dan)),
        )
        assertEquals(listOf("a", "b"), rows.ids())
        assertEquals(listOf(listOf("Ana", "Cleo"), listOf("Ben", "Dan")), rows.names())
    }

    @Test
    fun `rows keep the order of their newest notification`() {
        val rows = groupNotifications(
            listOf(
                follow("a", ana),
                like("b", ben, "p"),
                reply("c", cleo, "q"),
                like("d", dan, "p"),
                repost("e", ana, "p"),
            ),
        )
        assertEquals(listOf("a", "b", "c", "e"), rows.ids())
        assertEquals(listOf(Type.Follow, Type.Like, Type.Reply, Type.Repost), rows.map { it.type })
    }

    @Test
    fun `the row is led by its newest notification and lists people newest first`() {
        val newest = like("a", ana, "p")
        val rows = groupNotifications(listOf(newest, like("b", ben, "p")))
        assertSame(newest, rows.single().newest)
        assertEquals("Ana", rows.single().actors.first().name)
    }

    @Test
    fun `an account counts once however many times it appears`() {
        val rows = groupNotifications(listOf(like("a", ana, "p"), like("b", ben, "p"), like("c", ana, "p")))
        assertEquals(listOf(listOf("Ana", "Ben")), rows.names())
    }

    @Test
    fun `likes without a post are not merged`() {
        val rows = groupNotifications(listOf(item("a", Type.Like, ana), item("b", Type.Like, ben)))
        assertEquals(listOf("a", "b"), rows.ids())
    }

    @Test
    fun `every notification lands in exactly one row`() {
        val page = listOf(
            like("1", ana, "p"), follow("2", ben), follow("3", cleo), reply("4", dan, "p"),
            like("5", ben, "p"), repost("6", cleo, "p"), follow("7", ana), like("8", dan, "q"),
        )
        val rows = groupNotifications(page)
        // Distinct people per row here, so the people counted equal the notifications folded.
        assertEquals(page.size, rows.sumOf { it.actors.size })
    }

    @Test
    fun `an empty page has no rows`() {
        assertTrue(groupNotifications(emptyList()).isEmpty())
    }

    @Test
    fun `hiding people drops them from a row and leaves untouched rows as they are`() {
        val rows = groupNotifications(listOf(like("a", ana, "p"), like("b", ben, "p"), like("c", cleo, "q")))
        val hidden = rows.map { row -> row.without { it.id == "2" } }
        assertEquals(listOf(listOf("Ana"), listOf("Cleo")), hidden.names())
        assertSame(rows[1], hidden[1])
        assertTrue(rows[0].without { true }.actors.isEmpty())
    }
}
