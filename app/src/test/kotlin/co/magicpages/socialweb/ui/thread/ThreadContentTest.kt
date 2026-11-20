package co.magicpages.socialweb.ui.thread

import androidx.compose.ui.text.AnnotatedString
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.ui.posts.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadContentTest {
    private val writer = Author(id = "9", handle = "@writer@blog.example", name = "Writer")

    private fun item(id: String) = FeedItem(Post(id = id, author = writer), AnnotatedString(""), null)

    private fun thread(ancestors: List<String>, hasEarlier: Boolean, focused: String = "focus") = ThreadContent(
        ancestors = ancestors.map(::item),
        hasEarlier = hasEarlier,
        post = item(focused),
        replies = emptyList(),
        next = null,
    )

    private fun ThreadContent.ancestorIds() = ancestors.map { it.post.id }

    private fun chain(vararg ids: String) = ids.map(::item)

    // Walking up

    @Test
    fun `earlier posts go in front of the ancestors, oldest first`() {
        val content = thread(listOf("c", "d"), hasEarlier = true).withEarlier(chain("a", "b"), hasMore = true)
        assertEquals(listOf("a", "b", "c", "d"), content.ancestorIds())
    }

    @Test
    fun `whether there is more comes from the latest response`() {
        val start = thread(listOf("c"), hasEarlier = true)
        assertTrue(start.withEarlier(chain("b"), hasMore = true).hasEarlier)
        assertFalse(start.withEarlier(chain("b"), hasMore = false).hasEarlier)
    }

    @Test
    fun `taps keep prepending until the root`() {
        var content = thread(listOf("h", "i"), hasEarlier = true)
        content = content.withEarlier(chain("f", "g"), hasMore = true)
        content = content.withEarlier(chain("d", "e"), hasMore = false)
        assertEquals(listOf("d", "e", "f", "g", "h", "i"), content.ancestorIds())
        assertFalse(content.hasEarlier)
    }

    @Test
    fun `posts that overlap what is shown are not added twice`() {
        val content = thread(listOf("c", "d"), hasEarlier = true).withEarlier(chain("a", "b", "c"), hasMore = true)
        assertEquals(listOf("a", "b", "c", "d"), content.ancestorIds())
    }

    @Test
    fun `the focused post is not added as its own ancestor`() {
        val content = thread(listOf("b"), hasEarlier = true).withEarlier(chain("a", "focus"), hasMore = false)
        assertEquals(listOf("a", "b"), content.ancestorIds())
    }

    @Test
    fun `a repeated post within one response is added once`() {
        val content = thread(listOf("c"), hasEarlier = true).withEarlier(chain("b", "b"), hasMore = false)
        assertEquals(listOf("b", "c"), content.ancestorIds())
    }

    @Test
    fun `a response with nothing new ends the walk even if it claims more`() {
        val content = thread(listOf("b", "c"), hasEarlier = true).withEarlier(chain("b"), hasMore = true)
        assertEquals(listOf("b", "c"), content.ancestorIds())
        assertFalse(content.hasEarlier)
    }

    @Test
    fun `adding earlier posts ends the loading state`() {
        val loading = thread(listOf("c"), hasEarlier = true).copy(loadingEarlier = true)
        assertFalse(loading.withEarlier(chain("b"), hasMore = true).loadingEarlier)
    }

    // Refreshing

    @Test
    fun `a refresh keeps the earlier posts that run into the fresh ancestors`() {
        val before = thread(listOf("a", "b", "c", "d"), hasEarlier = false)
        val fresh = thread(listOf("c", "d"), hasEarlier = true).keepingEarlier(before)
        assertEquals(listOf("a", "b", "c", "d"), fresh.ancestorIds())
        assertFalse(fresh.hasEarlier)
    }

    @Test
    fun `a refresh keeps the walk where it stopped`() {
        val before = thread(listOf("b", "c", "d"), hasEarlier = true)
        val fresh = thread(listOf("c", "d"), hasEarlier = true).keepingEarlier(before)
        assertEquals(listOf("b", "c", "d"), fresh.ancestorIds())
        assertTrue(fresh.hasEarlier)
    }

    @Test
    fun `a refresh with nothing extra loaded changes nothing`() {
        val fresh = thread(listOf("c", "d"), hasEarlier = true)
        assertSame(fresh, fresh.keepingEarlier(thread(listOf("c", "d"), hasEarlier = true)))
    }

    @Test
    fun `fresh ancestors that do not continue the old chain replace it`() {
        val before = thread(listOf("a", "b", "c"), hasEarlier = false)
        val fresh = thread(listOf("x", "y"), hasEarlier = true)
        assertSame(fresh, fresh.keepingEarlier(before))
    }

    @Test
    fun `a refresh without ancestors drops the old ones`() {
        val fresh = thread(emptyList(), hasEarlier = false)
        assertSame(fresh, fresh.keepingEarlier(thread(listOf("a", "b"), hasEarlier = true)))
    }
}
