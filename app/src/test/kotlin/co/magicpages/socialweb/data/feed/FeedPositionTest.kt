package co.magicpages.socialweb.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPositionTest {
    @Test
    fun `a saved position survives encoding`() {
        val position = SavedFeedPosition(anchorId = "https://x.example/note/1", offset = 143, topId = "https://x.example/note/9")

        assertEquals(position, SavedFeedPosition.decode(position.encode()))
    }

    @Test
    fun `a negative offset is kept`() {
        val position = SavedFeedPosition("a", -4, "b")

        assertEquals(position, SavedFeedPosition.decode(position.encode()))
    }

    @Test
    fun `garbage doesn't decode`() {
        assertNull(SavedFeedPosition.decode(null))
        assertNull(SavedFeedPosition.decode(""))
        assertNull(SavedFeedPosition.decode("12\tonly-two"))
        assertNull(SavedFeedPosition.decode("x\ta\tb"))
        assertNull(SavedFeedPosition.decode("1\t\tb"))
        assertNull(SavedFeedPosition.decode("1\ta\t"))
    }

    @Test
    fun `the user was at the top when the anchor is the newest post seen`() {
        assertTrue(SavedFeedPosition("a", 0, "a").wasAtTop)
        assertFalse(SavedFeedPosition("b", 0, "a").wasAtTop)
    }

    private val ids = listOf("n3", "n2", "n1", "o2", "o1")

    @Test
    fun `at the top, the newest post is the one seen`() {
        assertEquals("n3", seenTopId(ids, previous = "o2", firstVisibleIndex = 0))
        assertEquals("n3", seenTopId(ids, previous = null, firstVisibleIndex = 0))
    }

    @Test
    fun `scrolled down, the post seen before stays the newest seen`() {
        assertEquals("o2", seenTopId(ids, previous = "o2", firstVisibleIndex = 4))
    }

    @Test
    fun `a post seen before that's gone from the list gives way to the list's first`() {
        assertEquals("n3", seenTopId(ids, previous = "deleted", firstVisibleIndex = 2))
        assertEquals("n3", seenTopId(ids, previous = null, firstVisibleIndex = 2))
    }

    @Test
    fun `an empty list keeps what was seen`() {
        assertEquals("o2", seenTopId(emptyList(), previous = "o2", firstVisibleIndex = 0))
        assertNull(seenTopId(emptyList(), previous = null, firstVisibleIndex = 0))
    }

    @Test
    fun `new posts are those above the newest one seen`() {
        assertEquals(3, newPostsAbove(ids, seenTop = "o2", firstVisibleIndex = 4))
        assertEquals(0, newPostsAbove(ids, seenTop = "n3", firstVisibleIndex = 4))
    }

    @Test
    fun `only the new posts above the first visible one count`() {
        assertEquals(1, newPostsAbove(ids, seenTop = "o2", firstVisibleIndex = 1))
        assertEquals(0, newPostsAbove(ids, seenTop = "o2", firstVisibleIndex = 0))
    }

    @Test
    fun `no new posts without a known newest post`() {
        assertEquals(0, newPostsAbove(ids, seenTop = null, firstVisibleIndex = 3))
        assertEquals(0, newPostsAbove(ids, seenTop = "gone", firstVisibleIndex = 3))
    }
}
