package co.magicpages.socialweb.data.fediverse

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerHighlightsTest {
    private var ids = 0

    private fun status(
        acct: String,
        likes: Int = 0,
        reposts: Int = 0,
        replies: Int = 0,
        bot: Boolean = false,
        inReplyTo: String? = null,
        tags: List<String> = emptyList(),
        link: String? = null,
    ): MastodonStatus {
        val id = "${++ids}"
        return MastodonStatus(
            id = id,
            uri = "https://here.example/users/$acct/statuses/$id",
            createdAt = "2026-11-22T12:00:${"%02d".format(ids % 60)}.000Z",
            inReplyToId = inReplyTo,
            account = MastodonAccount(acct = acct, bot = bot),
            favourites = likes,
            reblogs = reposts,
            replies = replies,
            tags = tags.map(::MastodonStatusTag),
            card = link?.let { MastodonCard(url = it, title = "Title of $it", image = if (it.endsWith("no-image")) null else "https://img.example/$id.jpg") },
        )
    }

    @Test
    fun `posts rank by reposts, then replies, then likes, and leave out bots and replies`() {
        val statuses = listOf(
            status("ana", likes = 5),
            status("ben", reposts = 2),
            status("cleo", replies = 2),
            status("weather", reposts = 50, bot = true),
            status("dan", reposts = 50, inReplyTo = "9"),
        )

        val posts = highlightsOf(statuses, "here.example").posts

        assertEquals(listOf("@ben@here.example", "@ana@here.example", "@cleo@here.example"), posts.map { it.author.handle })
    }

    @Test
    fun `no one gets more than two posts in`() {
        val statuses = listOf(status("ana", likes = 9), status("ana", likes = 8), status("ana", likes = 7), status("ben", likes = 1))

        val posts = highlightsOf(statuses, "here.example").posts

        assertEquals(listOf(9, 8, 1), posts.map { it.likeCount })
    }

    @Test
    fun `a hashtag counts each person once and needs two of them`() {
        val statuses = listOf(
            status("ana", tags = listOf("GameDev")),
            status("ana", tags = listOf("gamedev")),
            status("ben", tags = listOf("gamedev", "blender")),
            status("cleo", tags = listOf("gamedev")),
            status("ana", tags = listOf("solo")),
            status("bot", bot = true, tags = listOf("solo")),
        )

        val tags = highlightsOf(statuses, "here.example").tags

        assertEquals(listOf(TrendingTag("GameDev", 3)), tags)
    }

    @Test
    fun `stories are articles whose posts got noticed, most noticed first, counting each person once`() {
        val statuses = listOf(
            status("ana", likes = 4, link = "https://news.example/a"),
            status("ben", likes = 6, link = "https://news.example/a"),
            status("cleo", reposts = 5, link = "https://news.example/b"),
            status("dan", likes = 9, link = "https://dan.example/petition"),
            status("eve", reposts = 9, link = "https://eve.example/no-image"),
        )

        val links = highlightsOf(statuses, "here.example").links

        assertEquals(listOf("https://news.example/b" to 1, "https://news.example/a" to 2), links.map { it.url to it.people })
    }
}
