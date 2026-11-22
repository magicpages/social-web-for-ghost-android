package co.magicpages.socialweb.data.translation

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The choices on the Translation settings screen. Device-wide, and nothing here is secret, so plain preferences do. */
@Singleton
class TranslationSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("translation_settings", Context.MODE_PRIVATE)

    private val _target = MutableStateFlow(prefs.getString(KeyTarget, null))

    /** The BCP 47 tag of the language to translate into ("zh-Hant" picks the script); null for the app's language. */
    val target: StateFlow<String?> = _target.asStateFlow()

    fun setTarget(tag: String?) {
        prefs.edit { putString(KeyTarget, tag) }
        _target.value = tag
    }

    private val _automatic = MutableStateFlow(prefs.getBoolean(KeyAutomatic, false))

    /** Whether opening a post translates it, from languages already on the device; nothing is downloaded for it. */
    val automatic: StateFlow<Boolean> = _automatic.asStateFlow()

    fun setAutomatic(enabled: Boolean) {
        prefs.edit { putBoolean(KeyAutomatic, enabled) }
        _automatic.value = enabled
    }

    // A copy: the set `getStringSet` returns must not be modified.
    private val _skipped = MutableStateFlow(prefs.getStringSet(KeySkipped, null)?.toSet() ?: emptySet())

    /** The languages the user reads, as [translationLanguage] names them: never translated automatically. */
    val skipped: StateFlow<Set<String>> = _skipped.asStateFlow()

    fun setSkipped(language: String, skip: Boolean) {
        val next = if (skip) _skipped.value + language else _skipped.value - language
        prefs.edit { putStringSet(KeySkipped, next) }
        _skipped.value = next
    }

    private companion object {
        const val KeyTarget = "target"
        const val KeyAutomatic = "automatic"
        const val KeySkipped = "skipped"
    }
}
