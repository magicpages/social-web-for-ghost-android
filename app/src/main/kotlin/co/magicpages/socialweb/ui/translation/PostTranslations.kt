package co.magicpages.socialweb.ui.translation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import co.magicpages.socialweb.data.translation.TextTranslator
import co.magicpages.socialweb.data.translation.TranslationException
import co.magicpages.socialweb.data.translation.TranslationProblem
import co.magicpages.socialweb.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The translations of notes into the app's language, kept per post for as long as the app runs: a
 * note keeps its translation (or its translating) while it scrolls out of view and back, and
 * translating it again after "Show original" is instant. Shared by every post card through
 * [LocalPostTranslations]; translations run in the application scope, so leaving a screen doesn't
 * lose the one that is under way.
 */
@Singleton
class PostTranslations @Inject constructor(
    private val translator: TextTranslator,
    @ApplicationScope private val scope: CoroutineScope,
) {
    @Immutable
    sealed interface State {
        data object Translating : State

        /** The note's text in the app's language, from the language called [sourceName]; [showing] it in place of the original or not. */
        data class Translated(val text: String, val sourceName: String, val showing: Boolean) : State

        /** Shown for a few seconds, then forgotten. */
        data class Failed(val failure: TranslationFailure) : State
    }

    /** Whether notes can be translated: the app's language is one the device translates into. */
    val isAvailable: Boolean get() = translator.targetLanguage != null

    // Compose state, so cards recompose when their post's state changes.
    private val states = mutableStateMapOf<String, State>()
    private val order = ArrayDeque<String>()

    fun stateOf(postId: String): State? = key(postId)?.let { states[it] }

    /**
     * Starts translating the note [postId], whose text is [text] (paragraphs and lines separated by line
     * breaks), or shows its translation again when it has one. Does nothing while it is being translated.
     * [download] is the user's go-ahead to download the language files it takes.
     */
    fun translate(postId: String, text: String, download: Boolean = false) {
        val key = key(postId) ?: return
        when (val current = states[key]) {
            is State.Translating -> return
            is State.Translated -> {
                states[key] = current.copy(showing = true)
                return
            }
            is State.Failed, null -> Unit
        }
        put(key, State.Translating)
        scope.launch {
            val result = try {
                val translated = translator.translate(listOf(text), download)
                State.Translated(translated.texts.single(), translator.languageName(translated.sourceLanguage), showing = true)
            } catch (e: TranslationException) {
                State.Failed(e.toFailure(translator))
            }
            put(key, result)
            // Asking to download stays, with its link, until the next try; the rest fade.
            if (result is State.Failed && result.failure.problem != TranslationProblem.NeedsDownload) {
                delay(FailureShownMillis)
                if (states[key] === result) forget(key)
            }
        }
    }

    /** Shows the note's own text again; its translation stays for [translate]. */
    fun showOriginal(postId: String) {
        val key = key(postId) ?: return
        (states[key] as? State.Translated)?.let { states[key] = it.copy(showing = false) }
    }

    /** Per language too: a translation is into the app's language, which can change while the app runs. */
    private fun key(postId: String): String? = translator.targetLanguage?.let { "$it/$postId" }

    private fun put(key: String, state: State) {
        if (key !in states) order.addLast(key)
        states[key] = state
        // Bounded: the oldest settled translations go first.
        while (order.size > MaxEntries) {
            val oldest = order.removeFirst()
            if (states[oldest] is State.Translating) {
                order.addLast(oldest)
                break
            }
            states.remove(oldest)
        }
    }

    private fun forget(key: String) {
        states.remove(key)
        order.remove(key)
    }

    private companion object {
        const val MaxEntries = 200
        const val FailureShownMillis = 6_000L
    }
}

/** Provided once signed in; null in previews, where posts offer no translation. */
val LocalPostTranslations = staticCompositionLocalOf<PostTranslations?> { null }
