package co.magicpages.socialweb.data.translation

import android.content.Context
import android.icu.util.ULocale
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tells the language of a text with Android's own text classifier, on the device. */
@Singleton
class LanguageIdentifier @Inject constructor(@ApplicationContext private val context: Context) {
    /** The BCP 47 tag of the language [text] is in, or "und" when the classifier isn't sure. */
    suspend fun identify(text: String): String = withContext(Dispatchers.Default) {
        val classifier = context.getSystemService(TextClassificationManager::class.java).textClassifier
        val result = classifier.detectLanguage(TextLanguage.Request.Builder(text).build())
        val best: ULocale? = if (result.localeHypothesisCount > 0) result.getLocale(0) else null
        if (best != null && result.getConfidenceScore(best) >= MinConfidence) best.toLanguageTag() else "und"
    }

    private companion object {
        /** Below this the classifier is guessing; a wrong language garbles a translation and hides a post that fits. */
        const val MinConfidence = 0.5f
    }
}
