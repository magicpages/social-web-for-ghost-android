package co.magicpages.socialweb.data.translation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.icu.util.ULocale
import android.os.IBinder
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.davidv.translator.ErrorType
import dev.davidv.translator.ITranslationCallback
import dev.davidv.translator.ITranslationService
import dev.davidv.translator.TranslationError
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Texts translated into the app's language, and the code of the language they were in. */
class Translated(val texts: List<String>, val sourceLanguage: String)

/**
 * Translates text into the app's language on the device, without Google: the language of the text
 * is identified by Android's own text classifier, and the translation is done by the Offline
 * Translator app (F-Droid, Firefox's translation models) through its translation service. Without
 * that app installed, translating fails with [TranslationProblem.NoTranslator].
 */
@Singleton
class TextTranslator @Inject constructor(@ApplicationContext private val context: Context) {
    private val appLocale: Locale get() = context.resources.configuration.locales[0]

    /**
     * The code of the language texts are translated into: the app's. Never null here: which languages
     * Offline Translator has depends on the packs downloaded in it, and it says so when one is missing.
     */
    val targetLanguage: String?
        get() = offlineTranslatorLanguage(appLocale)

    /** "Turkish": the name of the language with [code], in the app's language. */
    fun languageName(code: String): String =
        Locale.forLanguageTag(code.replace('_', '-')).getDisplayLanguage(appLocale).ifBlank { code }

    /**
     * Translates every one of [texts] into the app's language, keeping each text's line breaks; the
     * language they are in is identified from all of them together. Blank texts come back empty.
     *
     * @throws TranslationException when there is nothing to translate, the language can't be told,
     * is the app's own or can't be translated, when Offline Translator isn't installed, or when the
     * translation fails
     */
    suspend fun translate(texts: List<String>): Translated {
        try {
            val target = offlineTranslatorLanguage(appLocale)
            val sample = texts.filter { it.isNotBlank() }.joinToString("\n").take(IdentifiedLength)
            if (sample.isEmpty()) throw TranslationException(TranslationProblem.Empty)
            val detected = identify(sample)
            // Chinese is one language to the identifier and two scripts (two models) to the translator.
            val source = if (translationLanguage(detected) == "zh") {
                if (target.startsWith("zh")) throw TranslationException(TranslationProblem.AlreadyInTargetLanguage, target)
                offlineTranslatorLanguage(Locale.forLanguageTag(detected))
            } else {
                sourceLanguage(detected, target, supported = null)
            }
            // Line by line: the translator merges lines it is given together, and the breaks must survive.
            val split = texts.map(::splitForTranslation)
            val lines = split.flatMap { it.parts }
            // Binder calls block for a moment: off the main thread.
            val translatedLines = withContext(Dispatchers.IO) {
                withService { service -> lines.map { service.translate(it, source, target) } }
            }
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

    /** The BCP 47 tag of the language [text] is in, or "und" when the classifier isn't sure. */
    private suspend fun identify(text: String): String = withContext(Dispatchers.Default) {
        val classifier = context.getSystemService(TextClassificationManager::class.java).textClassifier
        val result = classifier.detectLanguage(TextLanguage.Request.Builder(text).build())
        val best: ULocale? = if (result.localeHypothesisCount > 0) result.getLocale(0) else null
        if (best != null && result.getConfidenceScore(best) >= MinConfidence) best.toLanguageTag() else "und"
    }

    /** Binds Offline Translator's service for [block], and unbinds after. */
    private suspend fun <T> withService(block: suspend (ITranslationService) -> T): T {
        val intent = Intent(ServiceAction).setPackage(OfflineTranslatorPackage)
        var connection: ServiceConnection? = null
        try {
            // A timeout is a failure, not a cancellation: withTimeoutOrNull keeps it from looking like one.
            val service = withTimeoutOrNull(BindTimeoutMillis) {
                suspendCancellableCoroutine<ITranslationService> { continuation ->
                    val bound = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (continuation.isActive) continuation.resume(ITranslationService.Stub.asInterface(binder))
                        }

                        override fun onServiceDisconnected(name: ComponentName?) = Unit
                    }
                    connection = bound
                    if (!context.bindService(intent, bound, Context.BIND_AUTO_CREATE)) {
                        continuation.resumeWith(Result.failure(TranslationException(TranslationProblem.NoTranslator)))
                    }
                }
            } ?: throw TranslationException(TranslationProblem.Failed, cause = IllegalStateException("Offline Translator didn't connect"))
            return block(service)
        } finally {
            connection?.let { runCatching { context.unbindService(it) } }
        }
    }

    /** One line through the service, which answers on a callback. */
    private suspend fun ITranslationService.translate(text: String, from: String, to: String): String =
        withTimeoutOrNull(LineTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                translate(text, from, to, object : ITranslationCallback.Stub() {
                    override fun onTranslationResult(translatedText: String?) {
                        if (continuation.isActive) continuation.resume(translatedText.orEmpty())
                    }

                    override fun onTranslationError(error: TranslationError?) {
                        if (!continuation.isActive) return
                        val problem = when (error?.type) {
                            ErrorType.COULD_NOT_DETECT_LANGUAGE -> TranslationException(TranslationProblem.UnknownLanguage)
                            ErrorType.DETECTED_BUT_UNAVAILABLE -> TranslationException(TranslationProblem.UnsupportedLanguage, from)
                            else -> TranslationException(TranslationProblem.Failed, cause = IllegalStateException(error?.message))
                        }
                        continuation.resumeWith(Result.failure(problem))
                    }
                })
            }
        } ?: throw TranslationException(TranslationProblem.Failed, cause = IllegalStateException("Offline Translator didn't answer"))

    private companion object {
        /** Plenty to tell a language by; the identifier gains nothing from a whole article. */
        const val IdentifiedLength = 2000

        /** Below this the classifier is guessing; a wrong source language gives a garbled translation. */
        const val MinConfidence = 0.5f

        const val OfflineTranslatorPackage = "dev.davidv.translator"
        const val ServiceAction = "dev.davidv.translator.ITranslationService"
        const val BindTimeoutMillis = 10_000L

        /** A line waits for the model to load the first time; after that a line takes milliseconds. */
        const val LineTimeoutMillis = 60_000L

        /** The app's [locale] as Offline Translator names it: Traditional Chinese is its own model. */
        fun offlineTranslatorLanguage(locale: Locale): String {
            val language = translationLanguage(locale.toLanguageTag())
            if (language != "zh") return language
            val traditional = locale.script.equals("Hant", ignoreCase = true) ||
                (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))
            return if (traditional) "zh_hant" else "zh"
        }
    }
}
