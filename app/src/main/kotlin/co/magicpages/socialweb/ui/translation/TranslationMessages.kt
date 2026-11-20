package co.magicpages.socialweb.ui.translation

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.translation.TextTranslator
import co.magicpages.socialweb.data.translation.TranslationException
import co.magicpages.socialweb.data.translation.TranslationProblem

/** What went wrong translating, with the name of the language it is about (in the app's language) when there is one. */
@Immutable
data class TranslationFailure(val problem: TranslationProblem, val languageName: String?)

fun TranslationException.toFailure(translator: TextTranslator) =
    TranslationFailure(problem, language?.let(translator::languageName))

/** The sentence telling the user about this failure; [article] picks what the general failure says it was translating. */
fun TranslationFailure.message(resources: Resources, article: Boolean): String = when (problem) {
    TranslationProblem.Empty -> resources.getString(R.string.translation_error_empty)
    TranslationProblem.UnknownLanguage -> resources.getString(R.string.translation_error_unknown_language)
    TranslationProblem.AlreadyInTargetLanguage -> resources.getString(R.string.translation_error_already, languageName.orEmpty())
    TranslationProblem.UnsupportedLanguage -> resources.getString(R.string.translation_error_unsupported, languageName.orEmpty())
    TranslationProblem.NoTranslator -> resources.getString(R.string.translation_error_no_translator)
    TranslationProblem.Failed ->
        resources.getString(if (article) R.string.translation_failed_article else R.string.translation_failed_note)
}

/** Where [TranslationProblem.NoTranslator] sends the reader: Offline Translator on F-Droid. */
const val OfflineTranslatorUrl = "https://f-droid.org/packages/dev.davidv.translator/"
