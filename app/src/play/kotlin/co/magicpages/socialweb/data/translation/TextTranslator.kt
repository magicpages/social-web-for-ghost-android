package co.magicpages.socialweb.data.translation

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.tasks.await

/** Texts translated into the app's language, and the code of the language they were in. */
class Translated(val texts: List<String>, val sourceLanguage: String)

/**
 * Translates text into the app's language on the device, with ML Kit: the language of the text is
 * identified, then the language files for the pair are downloaded once (by Google Play services
 * and ML Kit) and translation runs offline.
 */
@Singleton
class TextTranslator @Inject constructor(@ApplicationContext private val context: Context) {
    private val supported: Set<String> by lazy { TranslateLanguage.getAllLanguages().toSet() }

    private val appLocale: Locale get() = context.resources.configuration.locales[0]

    /** The code of the language texts are translated into (the app's), or null when ML Kit can't translate into it. */
    val targetLanguage: String?
        get() = translationLanguage(appLocale.toLanguageTag()).takeIf { it in supported }

    /** "Turkish": the name of the language with [code], in the app's language. */
    fun languageName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(appLocale).ifBlank { code }

    /**
     * Translates every one of [texts] into the app's language, keeping each text's line breaks; the
     * language they are in is identified from all of them together. Blank texts come back empty.
     *
     * @throws TranslationException when there is nothing to translate, the language can't be told,
     * is the app's own or can't be translated, or when the language files or the translation fail
     */
    suspend fun translate(texts: List<String>): Translated {
        try {
            val target = targetLanguage
                ?: throw TranslationException(TranslationProblem.UnsupportedLanguage, translationLanguage(appLocale.toLanguageTag()))
            val sample = texts.filter { it.isNotBlank() }.joinToString("\n").take(IdentifiedLength)
            if (sample.isEmpty()) throw TranslationException(TranslationProblem.Empty)
            val source = sourceLanguage(identify(sample), target, supported)
            // Line by line: the translator merges lines it is given together, and the breaks must survive.
            val split = texts.map(::splitForTranslation)
            val lines = split.flatMap { it.parts }
            // The client holds native resources: closed when done, also when the caller gives up.
            val translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
            )
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                val translatedLines = lines.map { translator.translate(it).await() }
                var next = 0
                val translated = split.map { text ->
                    text.join(translatedLines.subList(next, next + text.parts.size)).also { next += text.parts.size }
                }
                return Translated(translated, source)
            } finally {
                translator.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException(TranslationProblem.Failed, cause = e)
        }
    }

    /** The BCP 47 tag of the language [text] is in, or "und". */
    private suspend fun identify(text: String): String =
        LanguageIdentification.getClient().use { it.identifyLanguage(text).await() }

    private companion object {
        /** Plenty to tell a language by; the identifier gains nothing from a whole article. */
        const val IdentifiedLength = 2000
    }
}
