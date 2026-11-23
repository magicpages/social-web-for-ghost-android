package co.magicpages.socialweb.ui.article

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.ViewModel
import co.magicpages.socialweb.data.settings.AppSettings
import co.magicpages.socialweb.data.settings.hidesReaderFor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** The sites whose articles skip the reader view ([AppSettings.readerHiddenSites]), for the app's navigation and menus. */
@HiltViewModel
class ReaderSitesViewModel @Inject constructor(private val settings: AppSettings) : ViewModel() {
    val hidden: StateFlow<Set<String>> = settings.readerHiddenSites

    fun setHidden(articleUrl: String, hidden: Boolean) = settings.setReaderHidden(articleUrl, hidden)
}

/**
 * Which sites have their reader view hidden, and a way to change that. A hidden site's articles open as their page in
 * the browser; "Show reader" in an article's menu turns the reader view back on. Provided as [LocalReaderSites].
 */
@Immutable
class ReaderSites(private val hidden: Set<String>, private val change: (articleUrl: String, hidden: Boolean) -> Unit) {
    /** Whether articles of the site [articleUrl] is on open as their page instead of in the reader view. */
    fun isHidden(articleUrl: String?): Boolean = hidden.hidesReaderFor(articleUrl)

    fun setHidden(articleUrl: String, hidden: Boolean) = change(articleUrl, hidden)
}

/** Null where menus can't change it (previews), which leaves their reader entries out. */
val LocalReaderSites = compositionLocalOf<ReaderSites?> { null }
