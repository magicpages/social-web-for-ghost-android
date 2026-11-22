package co.magicpages.socialweb.ui.quote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.MaterialTheme
import co.magicpages.socialweb.data.activitypub.ImageRejection
import co.magicpages.socialweb.data.activitypub.Post
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a new note starts with when it is about something: the words to begin with and a quote card or an image to attach. */
class NoteSeed(
    val text: String,
    /** Makes the card's content. Runs off the main thread, as it may parse HTML. */
    val card: (() -> QuoteCardSpec)?,
    /** A `file:` Uri of a shared image to attach instead of a card. */
    val image: String? = null,
    /** Why a shared image was turned away; the note opens showing it. */
    val imageRejection: ImageRejection? = null,
)

/**
 * Quoting [post]: its link below an empty first line for the writer's own words, and a picture of it.
 * Posts without a web address are quoted by the picture alone.
 */
fun postSeed(post: Post): NoteSeed =
    NoteSeed(text = linkBelowEmptyLines(post.url), card = { postQuote(post) })

/**
 * A shared page: its link, and a card of the selected words when the share carried some. A shared
 * image takes the card's place, so the selected words go into the text instead.
 */
fun SharedContent.toSeed(): NoteSeed {
    val content = this
    val words = quote
    if (imageUri != null) {
        val text = if (url != null) listOfNotNull(words, url).joinToString("\n\n") else plainText.orEmpty()
        return NoteSeed(text = text, card = null, image = imageUri)
    }
    return NoteSeed(
        text = url?.let(::linkBelowEmptyLines) ?: plainText.orEmpty(),
        card = words?.let { { textQuote(content, it) } },
        imageRejection = imageRejection,
    )
}

/** The writer's own words go first; the keyboard opens with the caret there. */
private fun linkBelowEmptyLines(url: String): String = if (url.isBlank()) "" else "\n\n${url.trim()}"

/**
 * Words selected in the writer's own [article], quoted on a card with the article's title and site, and the
 * article's link below an empty first line: the same note as sharing the selection from a browser.
 */
fun passageSeed(article: Post, passage: String): NoteSeed =
    SharedContent(
        url = article.webUrl,
        quote = passage,
        title = article.title.trim().takeIf { it.isNotEmpty() && !it.equals(passage, ignoreCase = true) },
        plainText = null,
    ).toSeed()

/**
 * Notes to start that came in before they could be shown, from outside the signed-in screens: a share
 * from another app (a page to write about), or a tapped "share again" reminder (one of the writer's own
 * posts). The sign-in comes first when there is no account. Each stays pending until the signed-in screens take it.
 */
@Singleton
class SharedContentRequests @Inject constructor() {
    private val _pending = MutableStateFlow<SharedContent?>(null)
    val pending: StateFlow<SharedContent?> = _pending.asStateFlow()

    private val _pendingPost = MutableStateFlow<Post?>(null)

    /** A post to write about, quoted as [postSeed] does. */
    val pendingPost: StateFlow<Post?> = _pendingPost.asStateFlow()

    fun submit(content: SharedContent) {
        _pending.value = content
    }

    /** Forgets [content] unless a newer share has replaced it meanwhile. */
    fun consume(content: SharedContent) {
        _pending.compareAndSet(content, null)
    }

    fun submit(post: Post) {
        _pendingPost.value = post
    }

    /** Forgets [post] unless a newer one has replaced it meanwhile. */
    fun consume(post: Post) {
        _pendingPost.compareAndSet(post, null)
    }
}

/** Starts a new note quoting a post; null where there is no composer to open (previews). */
val LocalQuotePost = staticCompositionLocalOf<((Post) -> Unit)?> { null }

/** Starts a new note quoting a passage the writer selected in their own article: the article, then the selected words. Null where there is no composer to open. */
val LocalQuotePassage = staticCompositionLocalOf<((Post, String) -> Unit)?> { null }

/** The quote card's colours, from the theme in use. */
@Composable
fun rememberQuoteCardPalette(): QuoteCardPalette {
    val colors = MaterialTheme.colorScheme
    return remember(colors) { QuoteCardPalette.of(colors) }
}
