package co.magicpages.socialweb.data.translation

import java.util.Locale
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationModelsTest {
    private fun record(
        from: String,
        to: String,
        type: String,
        version: String,
        filter: String = "",
        name: String = "$type.$from$to.intgemm.alphas.bin",
    ) = """{"fromLang": "$from", "toLang": "$to", "fileType": "$type", "version": "$version", "name": "$name",
        |"filter_expression": "$filter", "attachment": {"location": "loc/$from$to-$type-$version", "hash": "h", "size": 10}}""".trimMargin()

    private fun pair(from: String, to: String, version: String, filter: String = "", types: List<String> = listOf("model", "lex", "vocab")) =
        types.map { record(from, to, it, version, filter) }

    private fun catalog(vararg records: List<String>) =
        languageModels(Json.parseToJsonElement("""{"data": [${records.flatMap { it }.joinToString(",")}]}"""))

    @Test
    fun `the newest release version of a pair is picked`() {
        val models = catalog(pair("de", "en", "1.0"), pair("de", "en", "2.1"), pair("de", "en", "2.10a1"), pair("de", "en", "2.2"))

        assertEquals("2.2", models.getValue("de" to "en").version)
        assertEquals(setOf("model", "lex", "vocab"), models.getValue("de" to "en").files.map { it.type }.toSet())
    }

    @Test
    fun `models the engine can't run are left out`() {
        assertNull(catalog(pair("de", "en", "3.0"))["de" to "en"])
        assertEquals("2.0", catalog(pair("de", "en", "3.0"), pair("de", "en", "2.0")).getValue("de" to "en").version)
    }

    @Test
    fun `records for other platforms and channels are left out, Android's are kept`() {
        val models = catalog(
            pair("ja", "en", "2.1", filter = "env.appinfo.OS != 'Android' || env.channel != 'release'"),
            pair("ja", "en", "2.0", filter = "env.appinfo.OS == 'Android' "),
            pair("fr", "en", "2.3", filter = "env.channel == 'default' || env.channel == 'nightly'"),
        )

        assertEquals("2.0", models.getValue("ja" to "en").version)
        assertNull(models["fr" to "en"])
    }

    @Test
    fun `a version without a model or vocabulary isn't usable`() {
        val models = catalog(pair("de", "en", "2.1", types = listOf("model", "lex")), pair("de", "en", "2.0"))

        assertEquals("2.0", models.getValue("de" to "en").version)
    }

    @Test
    fun `separate vocabularies for each language are enough`() {
        val models = catalog(pair("zh-Hans", "en", "2.0", types = listOf("model", "lex", "srcvocab", "trgvocab")))

        assertEquals(setOf("model", "lex", "srcvocab", "trgvocab"), models.getValue("zh-Hans" to "en").files.map { it.type }.toSet())
    }

    @Test
    fun `pairs without English go through it`() {
        assertEquals(listOf("de" to "en"), route("de", "en"))
        assertEquals(listOf("en" to "ja"), route("en", "ja"))
        assertEquals(listOf("de" to "en", "en" to "ja"), route("de", "ja"))
    }

    @Test
    fun `languages are named as Firefox's models name them`() {
        assertEquals("nb", modelLanguage("no", Locale.forLanguageTag("nb-NO")))
        assertEquals("zh-Hans", modelLanguage("zh", Locale.forLanguageTag("zh")))
        assertEquals("zh-Hans", modelLanguage("zh", Locale.forLanguageTag("zh-Hans-TW")))
        assertEquals("zh-Hant", modelLanguage("zh", Locale.forLanguageTag("zh-Hant")))
        assertEquals("zh-Hant", modelLanguage("zh", Locale.forLanguageTag("zh-TW")))
        assertEquals("de", modelLanguage("de", Locale.GERMANY))
    }
}
