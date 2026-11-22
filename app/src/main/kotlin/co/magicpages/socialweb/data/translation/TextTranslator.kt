package co.magicpages.socialweb.data.translation

import android.content.Context
import android.icu.util.ULocale
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Texts translated into the chosen language, and the code of the language they were in. */
class Translated(val texts: List<String>, val sourceLanguage: String)

/**
 * A language that can be translated from, by its model code ("ja", "zh-Hant"), with its [name] and the
 * bytes its files take or would take to download. [needed] when other downloaded languages translate
 * through its files, so deleting it frees nothing.
 */
data class TranslationLanguage(val code: String, val name: String, val bytes: Long, val needed: Boolean = false)

/**
 * Translates text on the device, without Google, into the language picked in [TranslationSettings]
 * or else the app's: Android's own text classifier tells the language, and Bergamot, Firefox's
 * translation engine, translates with Firefox's language models. Their files are downloaded from
 * Mozilla once the user agrees ([TranslationProblem.NeedsDownload]) or from Settings; after that it
 * all runs offline.
 */
@Singleton
class TextTranslator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: TranslationFiles,
    private val engine: TranslationEngine,
    private val settings: TranslationSettings,
) {
    private val appLocale: Locale get() = context.resources.configuration.locales[0]

    /** The language picked to translate into, or else the app's, as a BCP 47 tag. */
    private val targetTag: String get() = settings.target.value ?: appLocale.toLanguageTag()

    /** The code of the language texts are translated into, or null when there are no models for it. */
    val targetLanguage: String?
        get() = translationLanguage(targetTag).takeIf { it in SupportedLanguages }

    /** The target language as Firefox's models name it, or null when there are none for it. */
    private val targetModel: String?
        get() = targetLanguage?.let { modelLanguage(it, Locale.forLanguageTag(targetTag)) }

    /** "Turkish": the name of the language with [code], in the app's language. */
    fun languageName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(appLocale).ifBlank { code }

    /**
     * Translates every one of [texts] into the target language, keeping each text's line breaks; the
     * language they are in is identified from all of them together. Blank texts come back empty.
     * The language files a translation needs are only downloaded when [download] says the user agreed.
     *
     * @throws TranslationException when there is nothing to translate, the language can't be told,
     * is the target or can't be translated, when its files need downloading, or when the download
     * or the translation fail
     */
    suspend fun translate(texts: List<String>, download: Boolean = false): Translated {
        try {
            val (source, models) = prepare(texts, download)
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

    /**
     * Whether [texts] would be translated without asking: translating automatically is on, they're in
     * a language other than the target and the ones the user reads, and its files are on the device.
     * Quick and offline: it only identifies the language and looks for the files.
     */
    suspend fun translatesAutomatically(texts: List<String>): Boolean {
        if (!settings.automatic.value) return false
        return try {
            val (source, _) = prepare(texts, download = false, offline = true)
            source !in settings.skipped.value
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** The language [texts] are in and the models from it to the target; [offline] doesn't ask Mozilla's catalog what's missing. */
    private suspend fun prepare(texts: List<String>, download: Boolean, offline: Boolean = false): Pair<String, List<InstalledModel>> {
        val target = targetLanguage
            ?: throw TranslationException(TranslationProblem.UnsupportedLanguage, translationLanguage(targetTag))
        val sample = texts.filter { it.isNotBlank() }.joinToString("\n").take(IdentifiedLength)
        if (sample.isEmpty()) throw TranslationException(TranslationProblem.Empty)
        val detected = identify(sample)
        val source = sourceLanguage(detected, target, SupportedLanguages)
        val route = route(modelLanguage(source, Locale.forLanguageTag(detected)), modelLanguage(target, Locale.forLanguageTag(targetTag)))
        return source to installedModels(route, source, download, offline)
    }

    /** "Japanese", "Chinese (Traditional)": the name of the language a model is for, in the app's language. */
    fun modelLanguageName(code: String): String =
        Locale.forLanguageTag(code).getDisplayName(appLocale).ifBlank { code }

    /**
     * The languages whose files for translating into the target are all on the device. Each one's size is what
     * deleting it frees, or, when other languages need all its files, the space those take.
     */
    suspend fun downloadedLanguages(): List<TranslationLanguage> {
        val target = targetModel ?: return emptyList()
        val installed = files.installedPairs().toSet()
        val sources = sourcesOf(installed, target)
        return sources.map { code ->
            val kept = sources.filter { it != code }.flatMap { route(it, target) }.toSet()
            val models = route(code, target)
            val own = models.filter { it !in kept }
            TranslationLanguage(
                code,
                modelLanguageName(code),
                own.ifEmpty { models }.sumOf { (from, to) -> files.modelBytes(from, to) },
                needed = own.isEmpty(),
            )
        }.sortedBy { it.name }
    }

    /**
     * The languages that can be translated into the target and aren't on the device yet, with their download sizes.
     *
     * @throws IOException when Mozilla's catalog can't be loaded
     */
    suspend fun downloadableLanguages(): List<TranslationLanguage> {
        val target = targetModel ?: return emptyList()
        val downloaded = sourcesOf(files.installedPairs().toSet(), target)
        return sourcesOf(files.availablePairs(), target).filter { it !in downloaded }.mapNotNull { code ->
            val models = route(code, target).map { (from, to) -> files.available(from, to) ?: return@mapNotNull null }
            TranslationLanguage(code, modelLanguageName(code), files.downloadSize(models))
        }.sortedBy { it.name }
    }

    /**
     * Downloads what translating from the language [code] (a model code) into the target takes.
     *
     * @throws IOException when the files can't be downloaded
     */
    suspend fun downloadLanguage(code: String) {
        val target = targetModel ?: return
        files.download(route(code, target).map { (from, to) -> files.available(from, to) ?: throw IOException("No model for $from-$to") })
    }

    /** Deletes the files for translating from the language [code], but not the ones another downloaded language still needs. */
    suspend fun deleteLanguage(code: String) {
        val target = targetModel ?: return
        val others = sourcesOf(files.installedPairs().toSet(), target).filter { it != code }
        val kept = others.flatMap { route(it, target) }.toSet()
        files.delete(route(code, target).filter { it !in kept })
    }

    /** Deletes every downloaded translation file, for any language. */
    suspend fun deleteAllLanguages() = files.deleteAll()

    /** How many bytes the downloaded translation files take. */
    suspend fun downloadedBytes(): Long = files.usedBytes()

    /** The languages that [pairs] have every model for, to translate into [target]. */
    private fun sourcesOf(pairs: Set<Pair<String, String>>, target: String): List<String> =
        pairs.flatMap { listOf(it.first, it.second) }.distinct()
            .filter { it != target && route(it, target).all { pair -> pair in pairs } }

    /** The models for [route], downloaded first if [download] allows it; [offline] skips sizing up a download. */
    private suspend fun installedModels(
        route: List<Pair<String, String>>,
        source: String,
        download: Boolean,
        offline: Boolean = false,
    ): List<InstalledModel> {
        route.map { (from, to) -> files.installed(from, to) }.let { installed ->
            if (installed.all { it != null }) return installed.filterNotNull()
        }
        if (offline) throw TranslationException(TranslationProblem.NeedsDownload, source)
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
