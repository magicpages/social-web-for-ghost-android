package co.magicpages.socialweb.ui.posts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.components.AddGifButton
import co.magicpages.socialweb.ui.components.AddImageButton
import co.magicpages.socialweb.ui.components.AltTextDialog
import co.magicpages.socialweb.ui.components.AttachedImage
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.ComposerBottomBar
import co.magicpages.socialweb.ui.components.ComposerDialog
import co.magicpages.socialweb.ui.components.ComposerFailure
import co.magicpages.socialweb.ui.components.ComposerField
import co.magicpages.socialweb.ui.components.LocalOwnProfile
import co.magicpages.socialweb.ui.components.PickedImagePreview
import co.magicpages.socialweb.ui.components.TakePhotoButton
import co.magicpages.socialweb.ui.components.rememberComposerText
import co.magicpages.socialweb.ui.translation.LocalPostTranslations
import co.magicpages.socialweb.ui.translation.PostTranslations
import co.magicpages.socialweb.ui.translation.TranslationNotice

/** Shows the [ReplyEditor] while [composer] has a reply open. */
@Composable
fun ReplyEditorHost(composer: ReplyComposer) {
    val state by composer.state.collectAsStateWithLifecycle()
    state?.let {
        ReplyEditor(
            state = it,
            onTextChange = composer::onTextChange,
            onAltTextChange = composer::onAltTextChange,
            onPickImage = composer::pickImage,
            onRemoveImage = composer::removeImage,
            onSend = composer::send,
            onDiscard = composer::dismiss,
        )
    }
}

/**
 * Writing a reply, in the same full-screen composer as a new note. The post being answered sits
 * on top, joined by the thread line to the writer's avatar and the field, as the reply will look
 * in the thread; an image can go with it.
 */
@Composable
fun ReplyEditor(
    state: ReplyUiState,
    onTextChange: (String) -> Unit,
    onAltTextChange: (String) -> Unit,
    onPickImage: (String) -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
) {
    var editAlt by rememberSaveable { mutableStateOf(false) }
    val writer = LocalOwnProfile.current
    val target = state.target.post
    val thread = remember(target) { listOf(target.author) }
    val text = rememberComposerText(state.text)
    ComposerDialog(
        title = stringResource(R.string.reply_title),
        sendLabel = stringResource(R.string.composer_post),
        discardTitle = stringResource(R.string.reply_discard_title),
        canSend = state.canSend,
        sending = state.sending,
        hasDraft = state.hasDraft,
        onSend = onSend,
        onDiscard = onDiscard,
        bottomBar = {
            ComposerBottomBar(text, thread, onPick = onTextChange) {
                AddImageButton(enabled = !state.sending, onPick = onPickImage)
                TakePhotoButton(enabled = !state.sending, onPick = onPickImage)
                AddGifButton(enabled = !state.sending) { uri, description ->
                    onPickImage(uri)
                    onAltTextChange(description)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        ) {
            state.failure?.let { ComposerFailure(stringResource(it.message)) }
            val lineColor = MaterialTheme.colorScheme.outlineVariant
            Row(
                Modifier
                    .drawBehind {
                        // From under the answered post's avatar down to the writer's.
                        val x = 20.dp.toPx()
                        drawLine(lineColor, Offset(x, (40 + 4).dp.toPx()), Offset(x, size.height + 4.dp.toPx()), 2.dp.toPx())
                    }
                    .padding(bottom = 12.dp),
            ) {
                Avatar(target.author.avatarUrl, target.author.displayName, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        target.author.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        target.author.handle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // A note the reader had translated is quoted as they read it, with the way back to the original.
                    val translations = LocalPostTranslations.current
                    val translation = (translations?.stateOf(target.id) as? PostTranslations.State.Translated)
                        ?.takeIf { it.showing && !target.isArticle }
                    val quoted = target.title.takeIf { target.isArticle && it.isNotBlank() }
                        ?: translation?.text
                        ?: state.target.content.text
                    if (quoted.isNotBlank()) {
                        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (translation != null && translations != null) {
                                TranslationNotice(translation.sourceName, onShowOriginal = { translations.showOriginal(target.id) })
                            }
                            Text(
                                quoted,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Row {
                Avatar(writer?.avatarUrl, writer?.name.orEmpty(), 40.dp, Modifier.padding(top = 8.dp))
                Column(Modifier.weight(1f)) {
                    ComposerField(
                        text = text,
                        value = state.text,
                        onValueChange = onTextChange,
                        placeholder = stringResource(R.string.reply_to, target.author.displayName),
                        enabled = !state.sending,
                    )
                    state.image?.let { uri ->
                        AttachedImage(
                            hasAlt = state.altText.isNotBlank(),
                            enabled = !state.sending,
                            onEditAlt = { editAlt = true },
                            onRemove = onRemoveImage,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                        ) { PickedImagePreview(uri) }
                    }
                }
            }
        }
    }
    if (editAlt && state.image != null) {
        AltTextDialog(state.altText, onAltTextChange, onDone = { editAlt = false })
    }
}
