package co.magicpages.socialweb.ui.feed

import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.ImageRejectedException
import co.magicpages.socialweb.data.activitypub.ImageRejection
import co.magicpages.socialweb.data.activitypub.ImageUploads
import co.magicpages.socialweb.data.activitypub.NoteImage
import co.magicpages.socialweb.data.activitypub.PickedImage
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.di.ApplicationScope
import co.magicpages.socialweb.ui.quote.NoteSeed
import co.magicpages.socialweb.ui.quote.QuoteCardPalette
import co.magicpages.socialweb.ui.quote.QuoteCardRenderer
import co.magicpages.socialweb.ui.quote.QuoteCardSpec
import co.magicpages.socialweb.ui.quote.RenderedCard
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NoteFailure { TooLarge, UnsupportedType, CardFailed, Failed }

/** The picture on a note. */
sealed interface NoteAttachment {
    /** An image the writer picked: a `content://` Uri, read only when the note is sent. */
    data class Picked(val uri: String) : NoteAttachment

    /** A quote card that is still being drawn. */
    data object Rendering : NoteAttachment

    /** A quote card, drawn and ready to upload. */
    class Rendered(val card: RenderedCard) : NoteAttachment
}

data class NoteUiState(
    val text: String = "",
    /** What the note started with, so an untouched prefilled note can be closed without asking. */
    val seedText: String = "",
    val attachment: NoteAttachment? = null,
    val altText: String = "",
    val sending: Boolean = false,
    val failure: NoteFailure? = null,
) {
    /** Whether closing would throw away something the writer made: words, a picked image, or a card's reworded description. */
    val hasDraft: Boolean
        get() = text.trim() != seedText.trim() ||
            attachment is NoteAttachment.Picked ||
            (attachment is NoteAttachment.Rendered && altText.trim() != attachment.card.altText.trim())

    val canSend: Boolean
        get() = text.isNotBlank() && attachment != NoteAttachment.Rendering
}

/**
 * State behind [NoteEditor]: composing a new note, optionally with one image. The image is picked
 * from the gallery, or it is a quote card drawn for a post or a shared page. One instance serves
 * the whole app, so a note can start from any screen and from a share.
 */
@Singleton
class NoteComposer @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
    private val uploads: ImageUploads,
    private val cards: QuoteCardRenderer,
) {
    /** `null` while the sheet is closed. */
    private val _state = MutableStateFlow<NoteUiState?>(null)
    val state: StateFlow<NoteUiState?> = _state.asStateFlow()

    private val _posted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits each time a note goes through. */
    val posted: Flow<Unit> = _posted

    private var drawing: Job? = null

    init {
        // A draft belongs to the account it was started as: another one posts nothing of it. A note
        // being sent keeps going as the account that sent it.
        scope.launch {
            accounts.activeId.drop(1).collect {
                if (_state.value?.sending != true) {
                    drawing?.cancel()
                    _state.value = null
                }
            }
        }
    }

    fun start() {
        _state.update { it ?: NoteUiState() }
    }

    /**
     * Opens a note about [seed]: its text, and its quote card drawn in [palette] or its image. A note
     * the writer already began keeps their words; the seed's text goes below them, and the card or
     * image is attached unless there is a picture already. Ignored while a note is being sent.
     */
    fun startWith(seed: NoteSeed, palette: QuoteCardPalette) {
        val current = _state.value
        if (current?.sending == true) return
        val image = seed.image?.let(NoteAttachment::Picked)
        val failure = seed.imageRejection?.toFailure()
        if (current == null || !current.hasDraft) {
            drawing?.cancel()
            _state.value = NoteUiState(text = seed.text, seedText = seed.text, attachment = image, failure = failure)
            seed.card?.let { draw(it, palette) }
        } else {
            val text = listOf(current.text.trimEnd(), seed.text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
            _state.value = current.copy(text = text, attachment = current.attachment ?: image, failure = failure)
            if (current.attachment == null) seed.card?.let { draw(it, palette) }
        }
    }

    fun onTextChange(text: String) = _state.update { it?.copy(text = text, failure = null) }

    fun onAltTextChange(text: String) = _state.update { it?.copy(altText = text) }

    fun pickImage(uri: String) {
        drawing?.cancel()
        _state.update { it?.copy(attachment = NoteAttachment.Picked(uri), altText = "", failure = null) }
    }

    fun removeImage() {
        drawing?.cancel()
        _state.update { it?.copy(attachment = null, altText = "", failure = null) }
    }

    /** Closes the sheet and drops the draft; ignored while a note is being sent. */
    fun dismiss() {
        if (_state.value?.sending == true) return
        drawing?.cancel()
        _state.value = null
    }

    private fun draw(card: () -> QuoteCardSpec, palette: QuoteCardPalette) {
        _state.update { it?.copy(attachment = NoteAttachment.Rendering, altText = "") }
        drawing?.cancel()
        drawing = scope.launch {
            // Building the spec may parse HTML; the renderer then moves itself off the main thread too.
            val spec = withContext(Dispatchers.Default) { card() }
            val rendered = cards.render(spec, palette, ActivityPubClient.MAX_IMAGE_BYTES)
            _state.update { current ->
                when {
                    current == null || current.attachment != NoteAttachment.Rendering -> current
                    rendered == null -> current.copy(attachment = null, failure = NoteFailure.CardFailed)
                    else -> current.copy(attachment = NoteAttachment.Rendered(rendered), altText = rendered.altText)
                }
            }
        }
    }

    fun send() {
        val current = _state.value ?: return
        if (current.sending || !current.canSend) return
        _state.value = current.copy(sending = true, failure = null)
        scope.launch {
            val failure = publish(current)
            if (failure == null) {
                _state.value = null
                _posted.emit(Unit)
            } else {
                _state.update { it?.copy(sending = false, failure = failure) }
            }
        }
    }

    private suspend fun publish(note: NoteUiState): NoteFailure? {
        val account = accounts.account.first() ?: return NoteFailure.Failed
        return try {
            val image = when (val attachment = note.attachment) {
                null -> null
                NoteAttachment.Rendering -> return NoteFailure.Failed
                is NoteAttachment.Rendered -> {
                    val card = attachment.card
                    NoteImage(
                        uploads.upload(account.site, account.credential, card.bytes, card.fileName, card.mimeType),
                        note.altText.trim().ifBlank { null },
                    )
                }
                is NoteAttachment.Picked -> uploads.upload(account.site, account.credential, PickedImage(attachment.uri, note.altText))
            }
            client.createNote(account.site, account.credential, note.text.trim(), image)
            null
        } catch (e: ImageRejectedException) {
            e.reason.toFailure()
        } catch (_: SocialWebException) {
            NoteFailure.Failed
        } catch (_: GhostException) {
            NoteFailure.Failed
        }
    }
}

private fun ImageRejection.toFailure(): NoteFailure = when (this) {
    ImageRejection.TooLarge -> NoteFailure.TooLarge
    ImageRejection.UnsupportedType -> NoteFailure.UnsupportedType
    ImageRejection.Unreadable -> NoteFailure.Failed
}
