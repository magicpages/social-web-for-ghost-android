package co.magicpages.socialweb.data.translation

import android.content.Context
import android.icu.util.ULocale
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Texts translated into the app's language, and the code of the language they were in. */
class Translated(val texts: List<String>, val sourceLanguage: String)

/**
 * Translates text into the app's language on the device, without Google: Android's own text
 * classifier tells the language, and Bergamot, Firefox's translation engine, translates with
 * Firefox's language models. Their files are downloaded from Mozilla the first time a language is
 * translated, once the user agrees ([TranslationProblem.NeedsDownload]); after that it all runs offline.
 */
@Singleton
class TextTranslator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: TranslationFiles,
    private val engine: TranslationEngine,
) {
    private val appLocale: Locale get() = context.resources.configuration.locales[0]

    /** The code of the language texts are translated into (the app's), or null when there are no models for it. */
    val targetLanguage: String?
        get() = translationLanguage(appLocale.toLanguageTag()).takeIf { it in SupportedLanguages }

    /** "Turkish": the name of the language with [code], in the app's language. */
    fun languageName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(appLocale).ifBlank { code }

    /**
     * Translates every one of [texts] into the app's language, keeping each text's line breaks; the
     * language they are in is identified from all of them together. Blank texts come back empty.
     * The language files a translation needs are only downloaded when [download] says the user agreed.
     *
     * @throws TranslationException when there is nothing to translate, the language can't be told,
     * is the app's own or can't be translated, when its files need downloading, or when the download
     * or the translation fail
     */
    suspend fun translate(texts: List<String>, download: Boolean = false): Translated {
        try {
            val target = targetLanguage
                ?: throw TranslationException(TranslationProblem.UnsupportedLanguage, translationLanguage(appLocale.toLanguageTag()))
            val sample = texts.filter { it.isNotBlank() }.joinToString("\n").take(IdentifiedLength)
            if (sample.isEmpty()) throw TranslationException(TranslationProblem.Empty)
            val detected = identify(sample)
            val source = sourceLanguage(detected, target, SupportedLanguages)
            val route = route(modelLanguage(source, Locale.forLanguageTag(detected)), modelLanguage(target, appLocale))
            val models = installedModels(route, source, download)
            // Line by line: the translator merges lines it is given together, and the breaks must survive.
            val split = texts.map(::splitForTranslation)
            val translatedLines = engine.translate(models, split.flatMap { it.parts })
            var next = 0
            val translated = split.map { text ->
                text.join(translatedLines.subList(next, next + text.parts.size)).also { next += text.parts.size }
            }
            return Translated(translated, source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException(TranslationProblem.Failed, cause = e)
        }
    }

    /** The models for [route], downloaded first if [download] allows it. */
    private suspend fun installedModels(route: List<Pair<String, String>>, source: String, download: Boolean): List<InstalledModel> {
        route.map { (from, to) -> files.installed(from, to) }.let { installed ->
            if (installed.all { it != null }) return installed.filterNotNull()
        }
        val available = route.map { (from, to) ->
            files.available(from, to) ?: throw TranslationException(TranslationProblem.UnsupportedLanguage, source)
        }
        if (!download) {
            throw TranslationException(TranslationProblem.NeedsDownload, source, downloadBytes = files.downloadSize(available))
        }
        files.download(available)
        return route.map { (from, to) -> checkNotNull(files.installed(from, to)) { "$from-$to didn't install" } }
    }

    /** The BCP 47 tag of the language [text] is in, or "und" when the classifier isn't sure. */
    private suspend fun identify(text: String): String = withContext(Dispatchers.Default) {
        val classifier = context.getSystemService(TextClassificationManager::class.java).textClassifier
        val result = classifier.detectLanguage(TextLanguage.Request.Builder(text).build())
        val best: ULocale? = if (result.localeHypothesisCount > 0) result.getLocale(0) else null
        if (best != null && result.getConfidenceScore(best) >= MinConfidence) best.toLanguageTag() else "und"
    }

    private companion object {
        /** Plenty to tell a language by; the identifier gains nothing from a whole article. */
        const val IdentifiedLength = 2000

        /** Below this the classifier is guessing; a wrong source language gives a garbled translation. */
        const val MinConfidence = 0.5f
    }
}

/**
 * The languages Firefox has models for, from or to English, as [translationLanguage] names them.
 * Mozilla's catalog has the final say on each pair; this is what the app can offer without asking it.
 */
internal val SupportedLanguages = setOf(
    "af", "ar", "az", "be", "bg", "bn", "bs", "ca", "cs", "da", "de", "el", "en", "es", "et", "eu", "fa", "fi",
    "fr", "gl", "gu", "he", "hi", "hr", "hu", "id", "is", "it", "ja", "kn", "ko", "lt", "lv", "ml", "mr", "ms",
    "no", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sr", "sv", "ta", "te", "th", "tl", "tr", "ug", "uk",
    "ur", "vi", "zh",
)

/**
 * The language [code] (as [translationLanguage] gives it) as Firefox's models name it: Norwegian is
 * Bokmål, and Chinese is a model per script, told by the [locale] it was found in.
 */
internal fun modelLanguage(code: String, locale: Locale): String = when (code) {
    "no" -> "nb"
    "zh" -> {
        val traditional = locale.script.equals("Hant", ignoreCase = true) ||
            (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))
        if (traditional) "zh-Hant" else "zh-Hans"
    }
    else -> code
}

/** The models from [source] to [target]: Firefox's go from or to English, so other pairs go through it. */
internal fun route(source: String, target: String): List<Pair<String, String>> =
    if (source == Pivot || target == Pivot) listOf(source to target) else listOf(source to Pivot, Pivot to target)

private const val Pivot = "en"
