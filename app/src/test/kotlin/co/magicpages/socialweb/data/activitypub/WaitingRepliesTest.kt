package co.magicpages.socialweb.data.activitypub

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitingRepliesTest {
    private val now = Instant.parse("2026-03-10T12:00:00Z").toEpochMilli()
    private val ana = Author(id = "https://one.example/users/ana", handle = "@ana@one.example", name = "Ana")
    private val me = "@me@example.com"

    private fun reply(
        hoursAgo: Long = 1,
        likedByMe: Boolean = false,
        type: Notification.Type = Notification.Type.Reply,
        actor: Author = ana,
    ) = Notification(
        id = "n",
        createdAt = Instant.ofEpochMilli(now - hoursAgo * 60 * 60 * 1000).toString(),
        type = type,
        actor = actor,
        post = NotificationPost(id = "https://one.example/posts/reply", likedByMe = likedByMe),
        inReplyTo = NotificationPost(id = "https://example.com/posts/mine"),
    )

    @Test
    fun `a fresh unliked reply from someone else waits`() {
        assertTrue(isWaitingCandidate(reply(), me, now))
    }

    @Test
    fun `a liked reply no longer waits`() {
        assertFalse(isWaitingCandidate(reply(likedByMe = true), me, now))
    }

    @Test
    fun `a reply older than a week no longer waits`() {
        assertFalse(isWaitingCandidate(reply(hoursAgo = 8 * 24), me, now))
        assertTrue(isWaitingCandidate(reply(hoursAgo = 6 * 24), me, now))
    }

    @Test
    fun `mentions and replies by the account itself are not counted`() {
        assertFalse(isWaitingCandidate(reply(type = Notification.Type.Mention), me, now))
        assertFalse(isWaitingCandidate(reply(actor = ana.copy(handle = me.uppercase())), me, now))
    }

    @Test
    fun `a notification without a post or a time can't wait`() {
        assertFalse(isWaitingCandidate(reply().copy(createdAt = null), me, now))
        assertFalse(isWaitingCandidate(reply().copy(post = null), me, now))
    }
}
