package co.magicpages.socialweb.ui.article

import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleTextsTest {
    private val html = "<h2>Tides</h2><p>The sea comes in.</p><ul><li>Salt</li><li>Sand</li></ul><pre><code>val x = 1</code></pre><p>It goes out.</p>"

    @Test
    fun `body texts come in reading order and code is left out`() {
        val texts = ArticleParser.parse(html, null).translatableTexts()

        assertEquals(listOf("Tides", "The sea comes in.", "Salt", "Sand", "It goes out."), texts)
    }

    @Test
    fun `translated texts go back in the same places`() {
        val document = ArticleParser.parse(html, null)
        val translated = document.withTranslatedTexts(document.translatableTexts().map { it.uppercase() })

        assertEquals(
            listOf("TIDES", "THE SEA COMES IN.", "SALT", "SAND", "IT GOES OUT."),
            translated.translatableTexts(),
        )
        assertEquals(document.blocks.size, translated.blocks.size)
    }
}
