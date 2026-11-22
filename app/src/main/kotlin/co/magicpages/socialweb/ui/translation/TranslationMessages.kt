package co.magicpages.socialweb.ui.translation

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.translation.TextTranslator
import co.magicpages.socialweb.data.translation.TranslationException
import co.magicpages.socialweb.data.translation.TranslationProblem

/**
 * What went wrong translating, with the name of the language it is about (in the app's language) when there is
 * one, and for [TranslationProblem.NeedsDownload] how many bytes the download is.
 */
@Immutable
data class TranslationFailure(val problem: TranslationProblem, val languageName: String?, val downloadBytes: Long = 0)

fun TranslationException.toFailure(translator: TextTranslator) =
    TranslationFailure(problem, language?.let(translator::languageName), downloadBytes)

/** The sentence telling the user about this failure; [article] picks what the general failure says it was translating. */
fun TranslationFailure.message(resources: Resources, article: Boolean): String = when (problem) {
    TranslationProblem.Empty -> resources.getString(R.string.translation_error_empty)
    TranslationProblem.UnknownLanguage -> resources.getString(R.string.translation_error_unknown_language)
    TranslationProblem.AlreadyInTargetLanguage -> resources.getString(R.string.translation_error_already, languageName.orEmpty())
    TranslationProblem.UnsupportedLanguage -> resources.getString(R.string.translation_error_unsupported, languageName.orEmpty())
    TranslationProblem.NeedsDownload ->
        resources.getString(R.string.translation_needs_download, languageName.orEmpty(), megabytes(downloadBytes))
    TranslationProblem.Failed ->
        resources.getString(if (article) R.string.translation_failed_article else R.string.translation_failed_note)
}

/** Whole megabytes, rounded up: a download is never less than it says. */
private fun megabytes(bytes: Long): Int = ((bytes + 999_999) / 1_000_000).toInt().coerceAtLeast(1)
