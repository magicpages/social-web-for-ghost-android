package co.magicpages.socialweb.data.feed

import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.PostsPage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedFilterTest {
    private val writer = Author(id = "9", handle = "@writer@blog.example", name = "Writer")
    private val booster = Author(id = "8", handle = "@booster@fedi.example", name = "Booster")

    private fun post(
        id: String = "p",
        content: String = "",
        title: String = "",
        summary: String? = null,
        repostedBy: Author? = null,
    ) = Post(id = id, title = title, summary = summary, content = content, author = writer, repostedBy = repostedBy)

    private fun muting(vararg words: String) = FeedFilter(mutedWords = words.toList())

    @Test
    fun `a filter with nothing set hides nothing`() {
        val filter = FeedFilter()

        assertFalse(filter.isActive)
        assertFalse(filter.hides(post(content = "<p>anything</p>", repostedBy = booster)))
    }

    @Test
    fun `hide reposts drops reposts and keeps what the account wrote`() {
        val filter = FeedFilter(hideReposts = true)

        assertTrue(filter.hides(post(repostedBy = booster)))
        assertFalse(filter.hides(post()))
    }

    @Test
    fun `a muted word matches whole words only, ignoring case`() {
        val filter = muting("cat")

        assertTrue(filter.hides(post(content = "<p>My Cat sleeps all day</p>")))
        assertTrue(filter.hides(post(content = "<p>Look: CAT.</p>")))
        assertFalse(filter.hides(post(content = "<p>The category page and a concatenation</p>")))
        assertFalse(filter.hides(post(content = "<p>cats</p>")))
    }

    @Test
    fun `a muted word is looked for in the text, the title and the summary`() {
        val filter = muting("spoiler")

        assertTrue(filter.hides(post(title = "A spoiler inside")))
        assertTrue(filter.hides(post(summary = "no Spoiler here")))
        assertTrue(filter.hides(post(content = "<p>one <strong>spoiler</strong></p>")))
    }

    @Test
    fun `html markup and attributes are not text`() {
        val filter = muting("example")

        assertFalse(filter.hides(post(content = """<p><a href="https://example.com/x">a link</a></p>""")))
        assertTrue(filter.hides(post(content = """<p><a href="https://other.test">example</a></p>""")))
    }

    @Test
    fun `words in neighbouring blocks don't fuse`() {
        val filter = muting("dogcat")

        assertFalse(filter.hides(post(content = "<p>dog</p><p>cat</p>")))
    }

    @Test
    fun `a hashtag is caught with or without its hash`() {
        val tagged = post(content = """<p>Hello <a href="https://x.test/tags/cats" rel="tag">#cats</a></p>""")

        assertTrue(muting("#cats").hides(tagged))
        assertTrue(muting("cats").hides(tagged))
        assertFalse(muting("#cat").hides(tagged))
    }

    @Test
    fun `a phrase matches across single spaces`() {
        val filter = muting("breaking  news")

        assertTrue(filter.hides(post(content = "<p>Breaking news: more soon</p>")))
        assertFalse(filter.hides(post(content = "<p>breaking and news</p>")))
    }

    @Test
    fun `regex characters in a word are literal`() {
        val filter = muting("c++", "(a|b)")

        assertTrue(filter.hides(post(content = "<p>I write c++ daily</p>")))
        assertTrue(filter.hides(post(content = "<p>the (a|b) case</p>")))
        assertFalse(filter.hides(post(content = "<p>a or b</p>")))
    }

    @Test
    fun `words in scripts without spaces match inside a run of text`() {
        val filter = muting("猫")

        assertTrue(filter.hides(post(content = "<p>今日は猫がいます</p>")))
        assertFalse(filter.hides(post(content = "<p>今日は犬がいます</p>")))
    }

    @Test
    fun `blank muted words are ignored`() {
        val filter = muting("", "   ")

        assertFalse(filter.isActive)
        assertFalse(filter.hides(post(content = "<p>text</p>")))
    }

    @Test
    fun `a word is kept trimmed, on one line and not too long`() {
        assertEquals("a b", FeedFilter.normalizeWord("  a \n  b "))
        assertNull(FeedFilter.normalizeWord("   "))
        assertEquals(FeedFilter.MaxWordLength, FeedFilter.normalizeWord("x".repeat(500))?.length)
    }

    // MARK: Paging

    private fun page(vararg posts: Post, next: String?) = PostsPage(posts.toList(), next)

    @Test
    fun `without a filter the page comes back as it is`() = runTest {
        val loaded = page(post("a"), next = "n1")
        var requests = 0

        val result = loadFiltered(FeedFilter(), null) { requests++; loaded }

        assertEquals(loaded, result)
        assertEquals(1, requests)
    }

    @Test
    fun `a page that is filtered out entirely is followed by the next one`() = runTest {
        val pages = mapOf(
            null to page(post("a", repostedBy = booster), post("b", repostedBy = booster), next = "n1"),
            "n1" to page(post("c", repostedBy = booster), next = "n2"),
            "n2" to page(post("d"), post("e", repostedBy = booster), next = "n3"),
        )
        val asked = ArrayList<String?>()

        val result = loadFiltered(FeedFilter(hideReposts = true), null) { asked += it; pages.getValue(it) }

        assertEquals(listOf(null, "n1", "n2"), asked)
        assertEquals(listOf("d"), result.posts.map { it.id })
        assertEquals("n3", result.next)
    }

    @Test
    fun `loading stops where the feed ends`() = runTest {
        val result = loadFiltered(FeedFilter(hideReposts = true), null) {
            page(post("a", repostedBy = booster), next = null)
        }

        assertTrue(result.posts.isEmpty())
        assertNull(result.next)
    }

    @Test
    fun `loading stops at the request cap and hands on the cursor`() = runTest {
        var requests = 0

        val result = loadFiltered(FeedFilter(hideReposts = true), null, maxRequests = 3) {
            requests++
            page(post("r$requests", repostedBy = booster), next = "n$requests")
        }

        assertEquals(3, requests)
        assertTrue(result.posts.isEmpty())
        assertEquals("n3", result.next)
    }

    @Test
    fun `articles only skips a page of notes and keeps the articles of the next`() = runTest {
        val pages = mapOf(
            null to page(post("n1"), post("n2"), next = "n1"),
            "n1" to page(post("n3"), articleOf("a1"), next = null),
        )
        val asked = ArrayList<String?>()

        val result = loadFiltered(FeedFilter(), null, articlesOnly = true) { asked += it; pages.getValue(it) }

        assertEquals(listOf(null, "n1"), asked)
        assertEquals(listOf("a1"), result.posts.map { it.id })
    }

    private fun articleOf(id: String) = post(id).copy(type = Post.TYPE_ARTICLE)
}
