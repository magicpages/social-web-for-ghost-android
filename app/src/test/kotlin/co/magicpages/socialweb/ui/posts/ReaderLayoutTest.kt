package co.magicpages.socialweb.ui.posts

import androidx.compose.ui.text.AnnotatedString
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLayoutTest {
    private val site = Author(id = "1", handle = "@index@site.example", name = "Site")

    private fun article(id: String, cover: Boolean = true, likes: Int = 0, reposts: Int = 0) = FeedItem(
        Post(
            id = id,
            type = Post.TYPE_ARTICLE,
            title = id,
            featureImageUrl = if (cover) "https://site.example/$id.jpg" else null,
            likeCount = likes,
            repostCount = reposts,
            author = site,
        ),
        AnnotatedString(""),
        publishedAtMillis = null,
    )

    private fun note(id: String) = FeedItem(Post(id = id, author = site), AnnotatedString(""), publishedAtMillis = null)

    private fun List<ReaderBlock>.shape() = map { block ->
        when (block) {
            is ReaderBlock.Single -> "${block.item.post.id}:${block.style.name.first()}"
            is ReaderBlock.TallPair -> "pair(${block.left.post.id},${block.right.post.id})"
        }
    }

    @Test
    fun `the first article with a cover leads, and the top third of the first page by score stands out`() {
        val items = listOf(
            article("a", cover = false, likes = 50),
            article("b"),
            article("c", likes = 3),
            article("d", reposts = 1),
            article("e", likes = 1),
        )

        val styles = ReaderLayout.styles(items)

        // Scores of covered articles: 0, 3, 2, 1, so the bar is the second highest, 2. An article without a cover never stands out.
        assertEquals(
            mapOf(
                "a" to ReaderStyle.Compact,
                "b" to ReaderStyle.Prominent,
                "c" to ReaderStyle.Prominent,
                "d" to ReaderStyle.Prominent,
                "e" to ReaderStyle.Compact,
            ),
            styles,
        )
    }

    @Test
    fun `nothing liked stays compact, however the first page scores`() {
        val styles = ReaderLayout.styles(listOf(article("a", cover = false), article("b"), article("c"), article("d")))

        assertEquals(listOf(ReaderStyle.Compact, ReaderStyle.Prominent, ReaderStyle.Compact, ReaderStyle.Compact), styles.values.toList())
    }

    @Test
    fun `in a run of compact articles with covers, the third and fourth are a pair, and a note ends the run`() {
        val items = listOf(article("lead", likes = 9)) +
            (1..8).map { article("c$it") } +
            listOf(note("n")) +
            (9..12).map { article("c$it") }

        val blocks = ReaderLayout.blocks(items, ReaderLayout.styles(items))

        assertEquals(
            listOf(
                "lead:P", "c1:C", "c2:C", "pair(c3,c4)", "c5:C", "c6:C", "pair(c7,c8)",
                "c9:C", "c10:C", "pair(c11,c12)",
            ),
            blocks.shape(),
        )
    }

    @Test
    fun `a pair needs two covers in a row, or the run starts again`() {
        val items = listOf(article("lead", likes = 9), article("c1"), article("c2"), article("c3"), article("x", cover = false), article("c4"))

        val blocks = ReaderLayout.blocks(items, ReaderLayout.styles(items))

        assertEquals(listOf("lead:P", "c1:C", "c2:C", "c3:C", "x:C", "c4:C"), blocks.shape())
    }

    @Test
    fun `each post keeps its own list item, a pair draws at its first post, and a note is left to its row`() {
        val items = listOf(article("lead", likes = 9), article("c1"), article("c2"), article("c3"), article("c4"), note("n"))

        val magazine = ReaderLayout.arrange(items)

        assertEquals(listOf("lead", "c1", "c2", "c3"), magazine.blocks.keys.toList())
        assertEquals(setOf("c4"), magazine.absorbed)
    }
}
