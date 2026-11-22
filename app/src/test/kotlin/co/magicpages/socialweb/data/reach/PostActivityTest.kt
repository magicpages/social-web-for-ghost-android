package co.magicpages.socialweb.data.reach

import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Notification
import co.magicpages.socialweb.data.activitypub.NotificationPost
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostActivityTest {
    private fun author(handle: String) = Author(id = handle, handle = handle)

    private fun notification(
        type: Notification.Type,
        actor: String,
        post: String?,
        inReplyTo: String? = null,
        at: String = "2025-05-01T10:00:00Z",
    ) = Notification(
        id = "$type-$actor-$post-$at",
        createdAt = at,
        type = type,
        actor = author(actor),
        post = post?.let { NotificationPost(id = it) },
        inReplyTo = inReplyTo?.let { NotificationPost(id = it) },
    )

    @Test
    fun `replies count toward the post they answer, not the reply`() {
        val activity = activityByPost(
            listOf(notification(Notification.Type.Reply, "@a@one.social", post = "reply-1", inReplyTo = "mine")),
        )

        assertEquals(setOf("mine"), activity.keys)
        assertEquals("reply-1", activity.getValue("mine").replies.single().reply?.id)
    }

    @Test
    fun `liking again after an unlike counts once, keeping the newest`() {
        val activity = activityByPost(
            listOf(
                notification(Notification.Type.Like, "@a@one.social", "mine", at = "2025-05-03T10:00:00Z"),
                notification(Notification.Type.Like, "@a@one.social", "mine", at = "2025-05-01T10:00:00Z"),
                notification(Notification.Type.Repost, "@a@one.social", "mine"),
            ),
        ).getValue("mine")

        assertEquals(1, activity.likes.size)
        assertEquals(Instant.parse("2025-05-03T10:00:00Z").toEpochMilli(), activity.likes.single().atMillis)
        // The same person reposting is a separate interaction.
        assertEquals(1, activity.reposts.size)
    }

    @Test
    fun `follows and mentions aren't about a post of ours`() {
        val activity = activityByPost(
            listOf(
                notification(Notification.Type.Follow, "@a@one.social", post = null),
                notification(Notification.Type.Mention, "@b@two.social", post = "their-post"),
            ),
        )

        assertEquals(emptyMap<String, PostActivity>(), activity)
    }

    @Test
    fun `servers are counted once each, whatever the handle's case`() {
        val activity = activityByPost(
            listOf(
                notification(Notification.Type.Like, "@a@One.Social", "mine"),
                notification(Notification.Type.Repost, "@b@one.social", "mine"),
                notification(Notification.Type.Like, "@c@two.social", "mine"),
            ),
        ).getValue("mine")

        assertEquals(setOf("one.social", "two.social"), activity.servers)
    }

    @Test
    fun `days run from the publishing day, in local time`() {
        val zone = ZoneOffset.ofHours(2)
        val published = Instant.parse("2025-05-01T21:00:00Z").toEpochMilli() // 23:00 local, May 1
        val times = listOf(
            "2025-05-01T20:00:00Z", // before publishing (clock drift): day one
            "2025-05-01T21:30:00Z", // 23:30 local: day one
            "2025-05-01T22:30:00Z", // 00:30 local, May 2: day two
            "2025-05-09T12:00:00Z", // past the last day: left out
        ).map { Instant.parse(it).toEpochMilli() }

        assertArrayEquals(intArrayOf(2, 1, 0), dailyCounts(times, published, days = 3, zone = zone))
    }

    @Test
    fun `first day share counts the first 24 hours`() {
        val start = 0L
        val times = listOf(start, DayMillis - 1, DayMillis, DayMillis * 3)

        assertEquals(50, firstDayPercent(times, start))
        assertNull(firstDayPercent(emptyList(), start))
    }

    @Test
    fun `potential reach adds every reposter's followers to the site's own`() {
        assertEquals(3_000_000_100L, potentialReach(100, listOf(Int.MAX_VALUE, 852_516_353)))
    }
}
