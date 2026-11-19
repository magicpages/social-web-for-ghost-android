package co.magicpages.socialweb.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import co.magicpages.socialweb.data.activitypub.CustomEmoji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomEmojisTest {
    private val blobcat = CustomEmoji("blobcat", "https://social.example/emoji/blobcat.png")

    /** The shortcodes marked as inline content, with where each one sits. */
    private fun AnnotatedString.marked() = getStringAnnotations(0, length).map { Triple(it.item, it.start, it.end) }

    @Test
    fun `known shortcodes become inline content and the rest stay text`() {
        val text = AnnotatedString("hi :blobcat: and :nope: and :blobcat:")

        val result = text.withCustomEmojis(listOf(blobcat))

        assertEquals(text.text, result.text)
        assertEquals(
            listOf(
                Triple("blobcat", 3, 12),
                Triple("blobcat", text.text.lastIndexOf(":blobcat:"), text.length),
            ),
            result.marked(),
        )
    }

    @Test
    fun `links and styles around a shortcode keep their ranges`() {
        val link = LinkAnnotation.Url("https://ghost.org")
        val text = buildAnnotatedString {
            append("see ")
            withLink(link) { append("ghost :blobcat:") }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(" :blobcat: :nope:") }
        }

        val result = text.withCustomEmojis(listOf(blobcat))

        assertEquals(text.spanStyles, result.spanStyles)
        assertEquals(
            text.getLinkAnnotations(0, text.length).map { Triple(it.item, it.start, it.end) },
            result.getLinkAnnotations(0, result.length).map { Triple(it.item, it.start, it.end) },
        )
        // One shortcode inside the link, one inside the bold run; the unknown one is left alone.
        assertEquals(2, result.marked().size)
        assertTrue(result.marked().all { it.first == "blobcat" })
    }

    @Test
    fun `a shortcode right after an unknown one is still found`() {
        val result = AnnotatedString(":x:blobcat:").withCustomEmojis(listOf(blobcat))

        assertEquals(listOf(Triple("blobcat", 2, 11)), result.marked())
    }

    @Test
    fun `text without a matching emoji comes back unchanged`() {
        val text = AnnotatedString("nothing :nope: here")

        assertSame(text, text.withCustomEmojis(listOf(blobcat)))
        assertSame(text, text.withCustomEmojis(emptyList()))
    }

    @Test
    fun `only text that looks like a shortcode asks for emoji`() {
        assertTrue(hasShortcode("hello :blobcat:"))
        assertFalse(hasShortcode("meet at 10:30, bring snacks"))
        assertFalse(hasShortcode("https://example.com/post"))
    }
}
