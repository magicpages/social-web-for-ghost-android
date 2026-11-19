package co.magicpages.socialweb.ui.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.components.ActionSheetScope
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.HtmlText
import co.magicpages.socialweb.ui.components.SheetAction
import co.magicpages.socialweb.ui.posts.FeedItem

/** Whether [item] is a note whose text the reader can have translated. */
private fun PostTranslations.canTranslate(item: FeedItem): Boolean =
    !item.post.isArticle && !item.post.isDeleted && item.content.isNotEmpty() && isAvailable

/**
 * A note's text. After "Translate" it shows "Translating…" above the text, then the translation in
 * its place under a notice saying where it came from, with "Show original" to go back.
 */
@Composable
fun TranslatableText(
    item: FeedItem,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val post = item.post
    val translations = LocalPostTranslations.current
    val state = translations?.stateOf(post.id)
    if (translations == null || state == null) {
        HtmlText(item.content, modifier, style, emojiSourceId = post.id)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (state) {
            PostTranslations.State.Translating -> {
                TranslationStatus(stringResource(R.string.translation_translating))
                HtmlText(item.content, style = style, emojiSourceId = post.id)
            }

            is PostTranslations.State.Failed -> {
                Text(
                    state.failure.message(LocalResources.current, article = false),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                HtmlText(item.content, style = style, emojiSourceId = post.id)
            }

            is PostTranslations.State.Translated -> if (state.showing) {
                TranslationNotice(state.sourceName, onShowOriginal = { translations.showOriginal(post.id) })
                val translated = remember(state.text, item.content) { relinkTranslation(item.content, state.text) }
                HtmlText(translated, style = style, emojiSourceId = post.id)
            } else {
                HtmlText(item.content, style = style, emojiSourceId = post.id)
            }
        }
    }
}

/** The translate icon and [text], quietly, as the line above a note being translated. */
@Composable
private fun TranslationStatus(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(AppIcons.Translate, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Translated from Turkish · Show original". */
@Composable
internal fun TranslationNotice(
    sourceName: String,
    onShowOriginal: () -> Unit,
    style: TextStyle = MaterialTheme.typography.labelMedium,
) {
    val translatedFrom = stringResource(R.string.translation_translated_from, sourceName)
    val showOriginal = stringResource(R.string.translation_show_original)
    val linkColor = MaterialTheme.colorScheme.primary
    val label = remember(translatedFrom, showOriginal, linkColor, onShowOriginal) {
        buildAnnotatedString {
            append(translatedFrom)
            append(" · ")
            withLink(LinkAnnotation.Clickable("show-original", TextLinkStyles(SpanStyle(color = linkColor))) { onShowOriginal() }) {
                append(showOriginal)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(AppIcons.Translate, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The more sheet's Translate row, or Show original once translated. Nothing for what can't be
 * translated, or where the app's language isn't one the device translates into.
 */
@Composable
fun ActionSheetScope.TranslateAction(item: FeedItem) {
    val translations = LocalPostTranslations.current ?: return
    if (!translations.canTranslate(item)) return
    val post = item.post
    val state = translations.stateOf(post.id)
    if (state is PostTranslations.State.Translated && state.showing) {
        SheetAction(stringResource(R.string.translation_show_original), AppIcons.Translate) {
            translations.showOriginal(post.id)
        }
    } else {
        SheetAction(
            stringResource(R.string.translation_translate),
            AppIcons.Translate,
            enabled = state !is PostTranslations.State.Translating,
        ) { translations.translate(post.id, item.content.text) }
    }
}
