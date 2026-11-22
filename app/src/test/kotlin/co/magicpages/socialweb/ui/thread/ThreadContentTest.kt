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

    // Replies from the post's home server

    private fun group(id: String, vararg followUps: String, hasMore: Boolean = false) =
        ReplyGroup(item(id), followUps.map(::item), hasMore)

    private fun threadWith(vararg replies: ReplyGroup, next: String? = null) =
        thread(emptyList(), hasEarlier = false).copy(replies = replies.toList(), next = next)

    /** Each direct reply with its follow-ups, as "id:follow,up". */
    private fun ThreadContent.layout() = replies.map { (it.reply.post.id + ":" + it.followUps.joinToString(",") { f -> f.post.id }) }

    @Test
    fun `the home server's replies come after the site's own`() {
        val merged = threadWith(group("a", "a1"), group("b")).withOrigin(listOf(group("x", "x1"), group("y")))

        assertEquals(listOf("a:a1", "b:", "x:x1", "y:"), merged.layout())
        assertEquals(setOf("x", "y"), merged.fromOrigin)
    }

    @Test
    fun `a reply both have is the site's, with the site's follow-ups`() {
        val merged = threadWith(group("a", "a1")).withOrigin(listOf(group("a", "other"), group("x")))

        assertEquals(listOf("a:a1", "x:"), merged.layout())
    }

    @Test
    fun `follow-ups the site already shows anywhere aren't repeated`() {
        val merged = threadWith(group("a", "a1"), group("b")).withOrigin(listOf(group("x", "a1", "x1", "b"), group("a1")))

        assertEquals(listOf("a:a1", "b:", "x:x1"), merged.layout())
    }

    @Test
    fun `the focused post and its ancestors aren't added as replies`() {
        val content = thread(listOf("up"), hasEarlier = false, focused = "focus")

        assertEquals(listOf("x:"), content.withOrigin(listOf(group("focus"), group("up"), group("x"))).layout())
    }

    @Test
    fun `merging again replaces what the last merge added`() {
        val once = threadWith(group("a")).withOrigin(listOf(group("x"), group("y")))
        val twice = once.withOrigin(listOf(group("y"), group("z")))

        assertEquals(listOf("a:", "y:", "z:"), twice.layout())
        assertEquals(setOf("y", "z"), twice.fromOrigin)
    }

    @Test
    fun `no replies from the home server leave the thread as it was`() {
        val content = threadWith(group("a"))

        assertEquals(content.layout(), content.withOrigin(emptyList()).layout())
    }

    @Test
    fun `a page from the site goes before the home server's replies and wins over its copy of a reply`() {
        val merged = threadWith(group("a"), next = "p2").withOrigin(listOf(group("x"), group("b", "origin-follow-up")))
        val paged = merged.copy(appending = true).withPage(threadWith(group("b", "b1"), group("c"), group("a"), next = null))

        assertEquals(listOf("a:", "b:b1", "c:"), paged.layout())
        assertEquals(null, paged.next)
        assertFalse(paged.appending)
        // The home server's replies are put back after the page, minus the ones the page has.
        assertEquals(listOf("a:", "b:b1", "c:", "x:"), paged.withOrigin(listOf(group("x"), group("b"))).layout())
    }

    @Test
    fun `counts are raised to the home server's and the site's flags stay`() {
        val own = Post(id = "focus", author = writer, likeCount = 1, likedByMe = true, replyCount = 0)
        val content = thread(emptyList(), hasEarlier = false).copy(post = FeedItem(own, AnnotatedString(""), null))

        val counted = content.withCounts(Post(id = "focus", author = writer, likeCount = 111, replyCount = 9, repostCount = 4, likedByMe = false))

        assertEquals(111, counted.post.post.likeCount)
        assertEquals(9, counted.post.post.replyCount)
        assertEquals(4, counted.post.post.repostCount)
        assertTrue(counted.post.post.likedByMe)
    }

    @Test
    fun `counts the site has higher are kept`() {
        val own = Post(id = "focus", author = writer, likeCount = 7)
        val content = thread(emptyList(), hasEarlier = false).copy(post = FeedItem(own, AnnotatedString(""), null))

        assertSame(content, content.withCounts(Post(id = "focus", author = writer, likeCount = 3)))
    }
}
