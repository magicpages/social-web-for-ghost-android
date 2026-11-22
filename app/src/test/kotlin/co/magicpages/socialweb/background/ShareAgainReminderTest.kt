package co.magicpages.socialweb.background

import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareAgainReminderTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `reminders go off at nine in the morning, counted in calendar days`() {
        val lateEvening = ZonedDateTime.of(2026, 11, 22, 23, 40, 0, 0, berlin)
        assertEquals(ZonedDateTime.of(2026, 11, 23, 9, 0, 0, 0, berlin), ShareAgainDelay.Tomorrow.triggerAt(lateEvening))
        assertEquals(ZonedDateTime.of(2026, 11, 25, 9, 0, 0, 0, berlin), ShareAgainDelay.InThreeDays.triggerAt(lateEvening))
        assertEquals(ZonedDateTime.of(2026, 11, 29, 9, 0, 0, 0, berlin), ShareAgainDelay.InAWeek.triggerAt(lateEvening))
    }

    @Test
    fun `a reminder set after midnight still waits for the next morning`() {
        val afterMidnight = ZonedDateTime.of(2026, 11, 22, 0, 30, 0, 0, berlin)
        assertEquals(ZonedDateTime.of(2026, 11, 23, 9, 0, 0, 0, berlin), ShareAgainDelay.Tomorrow.triggerAt(afterMidnight))
    }

    @Test
    fun `the hour holds across a clock change`() {
        // Europe/Berlin leaves summer time on 2026-10-25.
        val before = ZonedDateTime.of(2026, 10, 23, 18, 0, 0, 0, berlin)
        assertEquals(9, ShareAgainDelay.InThreeDays.triggerAt(before).hour)
        assertEquals(26, ShareAgainDelay.InThreeDays.triggerAt(before).dayOfMonth)
    }

    private fun article(summary: String? = "A short summary.", excerpt: String = "") = Post(
        id = "https://example.com/.ghost/activitypub/article/1",
        type = Post.TYPE_ARTICLE,
        title = " Hosting for Ghost ",
        excerpt = excerpt,
        summary = summary,
        url = "https://example.com/hosting/",
        featureImageUrl = "https://example.com/cover.jpg",
        publishedAt = "2026-11-20T09:00:00Z",
        author = Author(id = "https://example.com/.ghost/activitypub/users/index", handle = "@index@example.com", name = "Example", avatarUrl = "https://example.com/a.png"),
        authoredByMe = true,
    )

    @Test
    fun `a reminder rebuilds the article it was made from`() {
        val reminder = ShareAgainReminder.of("account-1", article())!!
        val back = ShareAgainReminder.from(reminder.values()::get)
        assertEquals(reminder, back)

        val post = back!!.toPost()
        assertEquals("Hosting for Ghost", post.title)
        assertEquals("A short summary.", post.excerpt)
        assertEquals("https://example.com/hosting/", post.url)
        assertEquals("https://example.com/cover.jpg", post.featureImageUrl)
        assertEquals("@index@example.com", post.author.handle)
        assertEquals("Example", post.author.name)
        assertTrue(post.isArticle)
    }

    @Test
    fun `warnings and missing optional parts survive`() {
        val warned = article().copy(sensitive = true, contentWarning = "Politics", featureImageUrl = null, publishedAt = null)
        val back = ShareAgainReminder.from(ShareAgainReminder.of("a", warned)!!.values()::get)!!
        assertTrue(back.sensitive)
        assertEquals("Politics", back.warning)
        assertNull(back.imageUrl)
        assertNull(back.publishedAt)
    }

    @Test
    fun `a long summary is cut so the work input stays small`() {
        val reminder = ShareAgainReminder.of("a", article(summary = "word ".repeat(400)))!!
        assertTrue(reminder.excerpt.length <= 301)
        assertTrue(reminder.excerpt.endsWith("…"))
    }

    @Test
    fun `the excerpt stands in when the article has no summary`() {
        assertEquals("From the excerpt.", ShareAgainReminder.of("a", article(summary = null, excerpt = "From the excerpt."))!!.excerpt)
    }

    @Test
    fun `an article without a web page has nothing to remind about`() {
        assertNull(ShareAgainReminder.of("a", article().copy(url = "")))
        assertNotNull(ShareAgainReminder.of("a", article()))
    }

    @Test
    fun `values that aren't a reminder are not read as one`() {
        assertNull(ShareAgainReminder.from { null })
        val partial = mapOf("co.magicpages.socialweb.share_again.account" to "a")
        assertNull(ShareAgainReminder.from(partial::get))
    }
}
