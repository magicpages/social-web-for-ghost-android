package co.magicpages.socialweb.ui.posts

import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepostGroupsTest {
    private val ana = Author(id = "1", handle = "@ana@one.example", name = "Ana")
    private val ben = Author(id = "2", handle = "@ben@two.example", name = "Ben")
    private val cleo = Author(id = "3", handle = "@cleo@three.example", name = "Cleo")
    private val dan = Author(id = "4", handle = "@dan@four.example", name = "Dan")
    private val writer = Author(id = "9", handle = "@writer@blog.example", name = "Writer")

    private fun post(id: String, repostedBy: Author? = null) = Post(id = id, author = writer, repostedBy = repostedBy)

    private fun RepostGroups.ids(page: List<Post>) = fold(page).map { it.post.id }

    @Test
    fun `a post reposted by several accounts shows once, at its newest position`() {
        val groups = RepostGroups()

        val shown = groups.fold(
            listOf(post("a", ana), post("b"), post("a", ben), post("c", cleo), post("a", cleo)),
        )

        assertEquals(listOf("a", "b", "c"), shown.map { it.post.id })
        assertEquals(listOf(ana, ben, cleo), shown[0].reposters)
        assertEquals(emptyList<Author>(), shown[1].reposters)
        assertEquals(listOf(cleo), shown[2].reposters)
    }

    @Test
    fun `the kept copy is the newest one`() {
        val newest = post("a", ana)

        val shown = RepostGroups().fold(listOf(newest, post("a", ben)))

        assertEquals(newest, shown.single().post)
    }

    @Test
    fun `a later page repeating an earlier post does not show it again`() {
        val groups = RepostGroups()

        val first = groups.ids(listOf(post("a", ana), post("b")))
        val second = groups.ids(listOf(post("a", ben), post("c")))

        assertEquals(listOf("a", "b"), first)
        assertEquals(listOf("c"), second)
    }

    @Test
    fun `a later page adds its reposters to the post an earlier page showed`() {
        val groups = RepostGroups()
        groups.fold(listOf(post("a", ana), post("b")))
        assertTrue(groups.late.value.isEmpty())

        groups.fold(listOf(post("a", ben), post("c", cleo), post("a", cleo)))

        assertEquals(mapOf("a" to listOf(ana, ben, cleo)), groups.late.value)
    }

    @Test
    fun `an original that is reposted later still gets the reposter`() {
        val groups = RepostGroups()
        val shown = groups.fold(listOf(post("a"))).single()
        assertEquals(emptyList<Author>(), shown.reposters)

        groups.fold(listOf(post("a", ana)))

        assertEquals(mapOf("a" to listOf(ana)), groups.late.value)
    }

    @Test
    fun `a repeat by an account already named changes nothing`() {
        val groups = RepostGroups()
        groups.fold(listOf(post("a", ana)))

        val sameHandleOtherCase = ana.copy(id = "other", handle = "@ANA@one.example")
        groups.fold(listOf(post("a", ana), post("a", sameHandleOtherCase), post("a")))

        assertTrue(groups.late.value.isEmpty())
    }

    @Test
    fun `reposters repeated within one page are folded before the page is shown`() {
        val groups = RepostGroups()

        val shown = groups.fold(listOf(post("a", ana), post("a", ben)))

        assertEquals(listOf(ana, ben), shown.single().reposters)
        assertTrue("nothing was shown yet that could be out of date", groups.late.value.isEmpty())
    }

    @Test
    fun `a fresh instance, as after a refresh, shows everything again`() {
        val page = listOf(post("a", ana), post("b"))
        RepostGroups().fold(page)

        assertEquals(listOf("a", "b"), RepostGroups().ids(page))
    }

    // Wording

    @Test
    fun `nobody reposted, no line`() {
        assertNull(summarizeReposters(emptyList()))
    }

    @Test
    fun `one reposter is named alone`() {
        assertEquals(RepostSummary.One(ana), summarizeReposters(listOf(ana)))
    }

    @Test
    fun `two reposters are both named`() {
        assertEquals(RepostSummary.Two(ana, ben), summarizeReposters(listOf(ana, ben)))
    }

    @Test
    fun `three or more name the newest and count everyone else`() {
        assertEquals(RepostSummary.Many(ana, others = 2), summarizeReposters(listOf(ana, ben, cleo)))
        assertEquals(RepostSummary.Many(ana, others = 3), summarizeReposters(listOf(ana, ben, cleo, dan)))
    }

    @Test
    fun `the signed-in account is named first, as you, wherever it reposted`() {
        assertEquals(RepostSummary.One(ben, firstIsYou = true), summarizeReposters(listOf(ben), "ben@two.example"))
        assertEquals(RepostSummary.Two(ben, ana, firstIsYou = true), summarizeReposters(listOf(ana, ben), "@BEN@two.example"))
        assertEquals(RepostSummary.Many(cleo, others = 2, firstIsYou = true), summarizeReposters(listOf(ana, ben, cleo), "@cleo@three.example"))
    }

    @Test
    fun `without the signed-in account among them, the newest reposter leads`() {
        assertEquals(RepostSummary.Two(ana, ben), summarizeReposters(listOf(ana, ben), "@dan@four.example"))
    }
}
