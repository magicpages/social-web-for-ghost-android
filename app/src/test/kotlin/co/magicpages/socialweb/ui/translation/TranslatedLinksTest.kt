package co.magicpages.socialweb.ui.translation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslatedLinksTest {
    /** Each link's address, with the text it sits on. */
    private fun AnnotatedString.links() =
        getLinkAnnotations(0, length).map { (it.item as LinkAnnotation.Url).url to text.substring(it.start, it.end) }

    private fun note(vararg pieces: Pair<String, String?>) = buildAnnotatedString {
        for ((text, url) in pieces) if (url == null) append(text) else withLink(LinkAnnotation.Url(url)) { append(text) }
    }

    @Test
    fun `mentions, hashtags and links keep their targets where the translation moved them`() {
        val original = note(
            "@sundog" to "https://mastodon.social/@sundog",
            " look at this " to null,
            "#astronomy" to "https://mastodon.social/tags/astronomy",
            "\n" to null,
            "example.com/sky" to "https://example.com/sky",
        )

        val result = relinkTranslation(original, "@sundog schau dir das an #astronomy\nexample.com/sky")

        assertEquals(
            listOf(
                "https://mastodon.social/@sundog" to "@sundog",
                "https://mastodon.social/tags/astronomy" to "#astronomy",
                "https://example.com/sky" to "example.com/sky",
            ),
            result.links(),
        )
    }

    @Test
    fun `a link the translator changed stays plain and the ones after it are still found`() {
        val original = note(
            "Ana" to "https://a.example/@ana",
            " and " to null,
            "#ai" to "https://a.example/tags/ai",
        )

        val result = relinkTranslation(original, "Anna und #ai")

        assertEquals(listOf("https://a.example/tags/ai" to "#ai"), result.links())
    }

    @Test
    fun `a label inside a longer word or tag is not linked`() {
        val original = note("#ai" to "https://a.example/tags/ai", " rocks" to null)

        val result = relinkTranslation(original, "#aid rockt, #ai")

        assertEquals(listOf("https://a.example/tags/ai" to "#ai"), result.links())
        assertEquals(12, result.getLinkAnnotations(0, result.length).single().start)
    }

    @Test
    fun `the same mention twice links both in order`() {
        val original = note(
            "@ana" to "https://a.example/@ana",
            " hi " to null,
            "@ana" to "https://a.example/@ana",
        )

        val result = relinkTranslation(original, "@ana hallo @ana")

        assertEquals(listOf(0, 11), result.getLinkAnnotations(0, result.length).map { it.start })
    }
}
