package co.magicpages.socialweb.data.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TranslationTextTest {
    private val supported = setOf("en", "de", "ja", "tr")

    private fun problemOf(detected: String, target: String = "en"): TranslationException =
        assertThrows(TranslationException::class.java) { sourceLanguage(detected, target, supported) }

    @Test
    fun `a single line is one part`() {
        val parts = splitForTranslation("  Hello there  ")

        assertEquals(listOf("Hello there"), parts.parts)
        assertEquals("Hallo", parts.join(listOf("Hallo")))
    }

    @Test
    fun `single and double line breaks survive the round trip`() {
        val parts = splitForTranslation("One\nTwo\n\nThree")

        assertEquals(listOf("One", "Two", "Three"), parts.parts)
        assertEquals("1\n2\n\n3", parts.join(listOf("1", "2", "3")))
    }

    @Test
    fun `blank lines and trailing spaces collapse to one paragraph break`() {
        val parts = splitForTranslation("One  \n \n\n  \nTwo")

        assertEquals(listOf("One", "Two"), parts.parts)
        assertEquals("1\n\n2", parts.join(listOf("1", "2")))
    }

    @Test
    fun `translated pieces are trimmed`() {
        assertEquals("a\nb", splitForTranslation("x\ny").join(listOf(" a ", "b\n")))
    }

    @Test
    fun `blank text has no parts and joins to nothing`() {
        val parts = splitForTranslation(" \n\n ")

        assertEquals(emptyList<String>(), parts.parts)
        assertEquals("", parts.join(emptyList()))
    }

    @Test
    fun `joining the wrong number of pieces is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { splitForTranslation("a\nb").join(listOf("only one")) }
    }

    @Test
    fun `mentions, hashtags and links at the edges of a line stay as they are`() {
        val parts = splitForTranslation("@anna@example.social Thanks for this\nH/T @sundogplanets\nRead more science.nasa.gov/mission/voyag…")

        assertEquals(listOf("Thanks for this", "H/T", "Read more"), parts.parts)
        assertEquals(
            "@anna@example.social Danke dafür\nDanke @sundogplanets\nMehr lesen science.nasa.gov/mission/voyag…",
            parts.join(listOf("Danke dafür", "Danke", "Mehr lesen")),
        )
    }

    @Test
    fun `a line of only links and hashtags is not translated`() {
        val parts = splitForTranslation("Voyager is far away\n\n#space #science https://example.com/a")

        assertEquals(listOf("Voyager is far away"), parts.parts)
        assertEquals("Voyager ist weit weg\n\n#space #science https://example.com/a", parts.join(listOf("Voyager ist weit weg")))
    }

    @Test
    fun `a mention inside a sentence is translated with it`() {
        assertEquals(listOf("Thanks @anna for this"), splitForTranslation("Thanks @anna for this").parts)
    }

    @Test
    fun `language tags map to the codes the translator uses`() {
        assertEquals("en", translationLanguage("en-US"))
        assertEquals("pt", translationLanguage("pt_BR"))
        assertEquals("he", translationLanguage("iw"))
        assertEquals("id", translationLanguage("in-ID"))
        assertEquals("yi", translationLanguage("ji"))
        assertEquals("tl", translationLanguage("fil"))
        assertEquals("no", translationLanguage("nb-NO"))
        assertEquals("no", translationLanguage("nn"))
        assertEquals("zh", translationLanguage("ZH-Hans"))
    }

    @Test
    fun `a supported language other than the target is the source`() {
        assertEquals("de", sourceLanguage("de", "en", supported))
        assertEquals("ja", sourceLanguage("ja-JP", "en", supported))
    }

    @Test
    fun `an undetermined language is unknown`() {
        assertEquals(TranslationProblem.UnknownLanguage, problemOf("und").problem)
        assertEquals(TranslationProblem.UnknownLanguage, problemOf(" ").problem)
    }

    @Test
    fun `the target language is already translated`() {
        val problem = problemOf("en")

        assertEquals(TranslationProblem.AlreadyInTargetLanguage, problem.problem)
        assertEquals("en", problem.language)
    }

    @Test
    fun `a language the device can't translate is unsupported`() {
        val problem = problemOf("sw")

        assertEquals(TranslationProblem.UnsupportedLanguage, problem.problem)
        assertEquals("sw", problem.language)
    }

    @Test
    fun `romanised text is not translated as its language`() {
        val problem = problemOf("ja-Latn")

        assertEquals(TranslationProblem.UnsupportedLanguage, problem.problem)
        assertEquals("ja", problem.language)
    }

    @Test
    fun `a site is its host without www`() {
        assertEquals("example.com", siteOf("https://www.example.com/posts/a/"))
        assertEquals("blog.example.com", siteOf("https://blog.example.com/x"))
        assertNull(siteOf(""))
        assertNull(siteOf(null))
    }
}
