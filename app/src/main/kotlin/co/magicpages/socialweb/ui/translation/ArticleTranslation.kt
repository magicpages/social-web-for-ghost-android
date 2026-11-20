package co.magicpages.socialweb.ui.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.translation.TextTranslator
import co.magicpages.socialweb.data.translation.TranslationException
import co.magicpages.socialweb.data.translation.TranslationSites
import co.magicpages.socialweb.ui.article.ArticleDocument
import co.magicpages.socialweb.ui.article.translatableTexts
import co.magicpages.socialweb.ui.article.withTranslatedTexts
import co.magicpages.socialweb.ui.components.ActionSheetScope
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.SheetAction
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Whether the article on show is translated into the app's language. */
@Immutable
sealed interface ArticleTranslation {
    data object Off : ArticleTranslation

    data object Translating : ArticleTranslation

    /** The article in the app's language: [title] and [summary] for its header, [document] for its body. */
    data class Translated(
        val sourceName: String,
        val title: String,
        val summary: String?,
        val document: ArticleDocument,
    ) : ArticleTranslation
}

/**
 * The translation of the article a screen shows. Whether a site's articles are read translated is
 * remembered ([TranslationSites]): the last choice made on a site applies to its next article,
 * without a message when it doesn't work out. The translation is kept, so switching it on again
 * after "Show original" is instant.
 */
@HiltViewModel
class ArticleTranslationViewModel @Inject constructor(
    private val translator: TextTranslator,
    private val sites: TranslationSites,
) : ViewModel() {
    private class Source(val post: Post, val document: ArticleDocument)

    private val _state = MutableStateFlow<ArticleTranslation>(ArticleTranslation.Off)
    val state: StateFlow<ArticleTranslation> = _state.asStateFlow()

    private val _failures = Channel<TranslationFailure>(Channel.BUFFERED)

    /** Why a translation the reader asked for didn't happen. */
    val failures: Flow<TranslationFailure> = _failures.receiveAsFlow()

    /** Whether articles can be translated: the app's language is one the device translates into. */
    val isAvailable: Boolean get() = translator.targetLanguage != null

    private var source: Source? = null
    private var firstArticle = true

    /** Translating is on: for the article on show, and for the new text if its body changes (a members-only post unlocking). */
    private var wanted = false
    private var quiet = false
    private var job: Job? = null
    private var cached: Pair<Source, ArticleTranslation.Translated>? = null

    /**
     * The article on show. The first one is translated straight away when the site's articles were
     * last read translated; after that, a changed [document] is translated again if translation is on.
     */
    fun show(post: Post, document: ArticleDocument) {
        val previous = source
        if (previous != null && previous.post.id == post.id && previous.document == document) return
        source = Source(post, document)
        if (firstArticle) {
            firstArticle = false
            if (isAvailable && sites.isOn(post.url)) {
                wanted = true
                translate(quiet = true)
            }
        } else if (wanted) {
            translate(quiet = quiet)
        }
    }

    /** Translate on, or "Show original" when it is on. */
    fun toggle() {
        when (_state.value) {
            ArticleTranslation.Off -> {
                wanted = true
                translate(quiet = false)
            }

            ArticleTranslation.Translating -> Unit
            is ArticleTranslation.Translated -> showOriginal()
        }
    }

    fun showOriginal() {
        job?.cancel()
        wanted = false
        _state.value = ArticleTranslation.Off
        sites.set(source?.post?.url, false)
    }

    private fun translate(quiet: Boolean) {
        val shown = source ?: return
        this.quiet = quiet
        job?.cancel()
        cached?.takeIf { it.first.post.id == shown.post.id && it.first.document == shown.document }?.let { (_, translated) ->
            _state.value = translated
            sites.set(shown.post.url, true)
            return
        }
        _state.value = ArticleTranslation.Translating
        job = viewModelScope.launch {
            try {
                val dek = shown.document.dek(shown.post.summary)
                // The header's title and summary first, then the body's texts in reading order.
                val result = translator.translate(listOf(shown.post.title, dek.orEmpty()) + shown.document.translatableTexts())
                val translated = ArticleTranslation.Translated(
                    sourceName = translator.languageName(result.sourceLanguage),
                    title = result.texts[0].ifBlank { shown.post.title },
                    summary = result.texts[1].takeIf { it.isNotBlank() },
                    document = shown.document.withTranslatedTexts(result.texts.drop(2)),
                )
                cached = shown to translated
                _state.value = translated
                sites.set(shown.post.url, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationException) {
                wanted = false
                _state.value = ArticleTranslation.Off
                if (!quiet) _failures.send(e.toFailure(translator))
            }
        }
    }
}

/** "Translating…", then "Translated from Turkish · Show original", above the article. Nothing while it isn't translated. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArticleTranslationBanner(state: ArticleTranslation, onShowOriginal: () -> Unit, modifier: Modifier = Modifier) {
    if (state == ArticleTranslation.Off) return
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                ArticleTranslation.Off -> Unit
                ArticleTranslation.Translating -> {
                    LoadingIndicator(Modifier.size(28.dp))
                    Text(
                        stringResource(R.string.translation_translating),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ArticleTranslation.Translated ->
                    TranslationNotice(state.sourceName, onShowOriginal, MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** The article menu's Translate, or Show original once it's translated. */
@Composable
fun ActionSheetScope.ArticleTranslateAction(state: ArticleTranslation, onToggle: () -> Unit) {
    SheetAction(
        stringResource(if (state is ArticleTranslation.Translated) R.string.translation_show_original else R.string.translation_translate),
        AppIcons.Translate,
        enabled = state != ArticleTranslation.Translating,
        onClick = onToggle,
    )
}
