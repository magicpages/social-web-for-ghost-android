package co.magicpages.socialweb.ui.feed

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.ui.components.AddGifButton
import co.magicpages.socialweb.ui.components.AddImageButton
import co.magicpages.socialweb.ui.components.AltTextDialog
import co.magicpages.socialweb.ui.components.AttachedImage
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.ComposerBottomBar
import co.magicpages.socialweb.ui.components.ComposerDialog
import co.magicpages.socialweb.ui.components.ComposerFailure
import co.magicpages.socialweb.ui.components.ComposerField
import co.magicpages.socialweb.ui.components.ComposerLinkPreview
import co.magicpages.socialweb.ui.components.LocalOwnProfile
import co.magicpages.socialweb.ui.components.PickedImagePreview
import co.magicpages.socialweb.ui.components.rememberComposerText
import co.magicpages.socialweb.ui.quote.LocalQuotePost
import co.magicpages.socialweb.ui.quote.SharedContentRequests
import co.magicpages.socialweb.ui.quote.postSeed
import co.magicpages.socialweb.ui.quote.rememberQuoteCardPalette
import co.magicpages.socialweb.ui.quote.toSeed
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** Shows the [NoteEditor] while [composer] has a note open. */
@Composable
fun NoteEditorHost(composer: NoteComposer) {
    val state by composer.state.collectAsStateWithLifecycle()
    state?.let {
        NoteEditor(
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
 * Hosts the app's one note composer above [content]: posts can be quoted from any screen through
 * [LocalQuotePost], and a page shared from another app ([shares]) opens a note as soon as the
 * signed-in screens are on show.
 */
@Composable
fun NoteComposerScope(composer: NoteComposer, shares: SharedContentRequests, content: @Composable () -> Unit) {
    val palette = rememberQuoteCardPalette()
    val quotePost: (Post) -> Unit = remember(composer, palette) { { post -> composer.startWith(postSeed(post), palette) } }
    LaunchedEffect(composer, shares, palette) {
        shares.pending.filterNotNull().collect { shared ->
            // A note being sent can't be changed under the sender; the share waits for it.
            composer.state.first { it?.sending != true }
            composer.startWith(shared.toSeed(), palette)
            shares.consume(shared)
        }
    }
    CompositionLocalProvider(LocalQuotePost provides quotePost) {
        // The host comes before [content], not after it. Switching accounts tears down the screens in
        // [content]; a sibling composed after them then read a screen's lifecycle owner, which was
        // destroyed a moment later, and the note editor stopped opening until the app restarted.
        NoteEditorHost(composer)
        content()
    }
}

/**
 * Writing a new note. The writer's avatar sits beside a borderless field, like a post in the feed;
 * the image button rides on a toolbar above the keyboard.
 */
@Composable
fun NoteEditor(
    state: NoteUiState,
    onTextChange: (String) -> Unit,
    onAltTextChange: (String) -> Unit,
    onPickImage: (String) -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
) {
    var editAlt by rememberSaveable { mutableStateOf(false) }
    val author = LocalOwnProfile.current
    val text = rememberComposerText(state.text)

    ComposerDialog(
        title = stringResource(R.string.note_new),
        sendLabel = stringResource(R.string.composer_post),
        discardTitle = stringResource(R.string.note_discard_title),
        canSend = state.canSend,
        sending = state.sending,
        hasDraft = state.hasDraft,
        onSend = onSend,
        onDiscard = onDiscard,
        bottomBar = {
            ComposerBottomBar(text, thread = emptyList(), onPick = onTextChange) {
                AddImageButton(enabled = !state.sending, onPick = onPickImage)
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
                .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)),
        ) {
            state.failure?.let { ComposerFailure(stringResource(it.message)) }
            Row {
                Avatar(author?.avatarUrl, author?.name.orEmpty(), 40.dp, Modifier.padding(top = 8.dp))
                Column(Modifier.weight(1f)) {
                    ComposerField(text, state.text, onTextChange, stringResource(R.string.note_hint), enabled = !state.sending)
                    ComposerLinkPreview(state.text, hidden = state.attachment != null, Modifier.padding(start = 16.dp, top = 8.dp))
                    state.attachment?.let { attachment ->
                        AttachedImage(
                            hasAlt = state.altText.isNotBlank().takeIf { attachment != NoteAttachment.Rendering },
                            enabled = !state.sending,
                            onEditAlt = { editAlt = true },
                            onRemove = onRemoveImage,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                        ) { NoteAttachmentImage(attachment) }
                    }
                }
            }
        }
    }
    if (editAlt && state.attachment != null && state.attachment != NoteAttachment.Rendering) {
        AltTextDialog(state.altText, onAltTextChange, onDone = { editAlt = false })
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NoteAttachmentImage(attachment: NoteAttachment) {
    when (attachment) {
        is NoteAttachment.Picked -> PickedImagePreview(attachment.uri)
        // The alt text, set through the frame's button, is what describes it. Shown whole, at its own
        // shape: the card is the quote, so cropping it would hide who said it.
        is NoteAttachment.Rendered -> {
            val bitmap = remember(attachment) { attachment.card.bitmap.asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height),
            )
        }
        NoteAttachment.Rendering -> Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            val drawing = stringResource(R.string.quote_card_drawing)
            LoadingIndicator(Modifier.semantics { contentDescription = drawing })
        }
    }
}

private val NoteFailure.message: Int
    get() = when (this) {
        NoteFailure.TooLarge -> R.string.note_image_too_large
        NoteFailure.UnsupportedType -> R.string.note_image_unsupported
        NoteFailure.CardFailed -> R.string.quote_card_failed
        NoteFailure.Failed -> R.string.note_failed
    }
