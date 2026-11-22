package co.magicpages.socialweb.data.activitypub

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingTimeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun words(count: Int) = List(count) { "word" }.joinToString(" ")

    private fun post(type: Int, minutes: Int, content: String): Post = json.decodeFromString(
        """{"id":"1","type":$type,"readingTimeMinutes":$minutes,"content":${Json.encodeToString(content)},
           "author":{"id":"a","handle":"@a@example.com"}}""",
    )

    @Test
    fun `words round up to whole minutes at 275 a minute`() {
        assertEquals(1, estimatedReadingMinutes("<p>${words(1)}</p>"))
        assertEquals(1, estimatedReadingMinutes(words(275)))
        assertEquals(2, estimatedReadingMinutes(words(276)))
        assertEquals(null, estimatedReadingMinutes("<p> </p><img src=\"a b c\">"))
    }

    @Test
    fun `tags separate words but never count as words`() {
        val html = "<p>" + List(550) { "w" }.joinToString("</p><p>") + "</p><figure class=\"x y z\"></figure>"
        assertEquals(2, estimatedReadingMinutes(html))
    }

    @Test
    fun `an article's placeholder count gives way to its words`() {
        assertEquals(6, post(Post.TYPE_ARTICLE, 1, "<p>${words(1_500)}</p>").readingTimeMinutes)
    }

    @Test
    fun `a real count from the service is kept`() {
        assertEquals(9, post(Post.TYPE_ARTICLE, 9, "<p>${words(1_500)}</p>").readingTimeMinutes)
    }

    @Test
    fun `notes keep what the service says`() {
        assertEquals(1, post(Post.TYPE_NOTE, 1, words(1_500)).readingTimeMinutes)
    }
}
