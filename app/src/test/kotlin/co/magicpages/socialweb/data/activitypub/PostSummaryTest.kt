package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.ui.article.ArticleParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostSummaryTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    private fun post(summary: String?, excerpt: String? = summary): Post {
        fun field(value: String?) = value?.let { JsonPrimitive(it).toString() } ?: "null"
        return json.decodeFromString(
            """{"id": "p", "type": 1, "summary": ${field(summary)}, "excerpt": ${field(excerpt)}, "author": {"id": "a", "handle": "@matt@write.as"}}""",
        )
    }

    // What `posts/@matt@write.as` returns: WriteFreely's summary, passed through from the outbox
    private val writeAsSummary =
        "<p>I&#39;ve been working on some Android apps <a href=\"https://micro.baer.works/\" rel=\"nofollow ugc\">for WriteFreely</a> " +
            "and the rest lately &amp; wanted to share one.</p>\n\n<p> [...]</p>"

    @Test
    fun `an HTML summary from a remote outbox reads as plain text without its read-more paragraph`() {
        val post = post(writeAsSummary)

        val text = "I've been working on some Android apps for WriteFreely and the rest lately & wanted to share one."
        assertEquals(text, post.summary)
        assertEquals(text, post.excerpt)
    }

    @Test
    fun `a stored plain summary keeps its text, with blank lines folded`() {
        val post = post("Write.as is 10 years old. Is a < b?\n\n\n\n[...]")

        assertEquals("Write.as is 10 years old. Is a < b?", post.summary)
    }

    @Test
    fun `missing and empty summaries stay empty`() {
        assertNull(post(null).summary)
        assertEquals("", post(null).excerpt)
        assertNull(post("<p> [...]</p>").summary)
    }

    @Test
    fun `the reader drops a write as summary that repeats the article's opening`() {
        val document = ArticleParser.parse(
            "<p>I&#39;ve been working on some Android apps <a href=\"https://micro.baer.works/\">for WriteFreely</a> " +
                "and the rest lately &amp; wanted to share one.</p>\n\n<h2>Some history</h2><p>More.</p>",
            "https://write.as/matt/coming-soon-read-as-for-android",
        )

        assertNull(document.dek(post(writeAsSummary).summary))
    }
}
