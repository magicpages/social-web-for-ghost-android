package co.magicpages.socialweb.ui.components

import android.util.LruCache
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.CustomEmoji
import co.magicpages.socialweb.data.activitypub.RemoteObjects
import co.magicpages.socialweb.di.ApplicationScope
import coil3.compose.AsyncImage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * The custom emoji a post or an account's name and bio use, looked up once per session from the
 * object's own server: the same post shows in the feed, the thread and a profile, and only a few
 * of them use emoji at all. Ids that can't be read (private, gone, offline) count as "no emoji"
 * for the rest of the session, so they aren't asked for again.
 */
@Singleton
class CustomEmojiSource @Inject constructor(
    private val remote: RemoteObjects,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val cache = LruCache<String, List<CustomEmoji>>(300)

    // Lookups in flight, so a list of posts by one author doesn't fetch the author once per post.
    private val pending = HashMap<String, Deferred<List<CustomEmoji>>>()

    /** What [load] found for [id], or null while it hasn't been asked. */
    fun cached(id: String): List<CustomEmoji>? = cache.get(id)

    /** The emoji of the object with ActivityPub id [id]; empty when it has none or can't be read. */
    suspend fun load(id: String): List<CustomEmoji> {
        cache.get(id)?.let { return it }
        val request = synchronized(pending) {
            // Lazy, so the coroutine can't finish (and remove itself) before it is in the map.
            pending.getOrPut(id) { scope.async(start = CoroutineStart.LAZY) { fetch(id) } }
        }
        // The lookup belongs to the app, not to the caller: leaving the screen doesn't cancel it for the others.
        return request.await()
    }

    private suspend fun fetch(id: String): List<CustomEmoji> {
        try {
            val emojis = remote.fetch(id)?.emojis.orEmpty()
            cache.put(id, emojis)
            return emojis
        } finally {
            synchronized(pending) { pending.remove(id) }
        }
    }
}

/** Provided once signed in; null in previews, where shortcodes stay text. */
val LocalCustomEmojis = staticCompositionLocalOf<CustomEmojiSource?> { null }

private val shortcodePattern = Regex(":([A-Za-z0-9_]+):")

/** Whether [text] holds something that looks like a `:shortcode:`; text that doesn't never asks for emoji. */
fun hasShortcode(text: CharSequence): Boolean = shortcodePattern.containsMatchIn(text)

// The key Compose looks inline content up by. Foundation keeps it internal, so read it off its own builder.
private val inlineContentTag: String by lazy {
    buildAnnotatedString { appendInlineContent("") }.getStringAnnotations(0, 1).first().tag
}

/**
 * Marks each `:shortcode:` that has an entry in [emojis] as inline content, for [emojiInlineContent]
 * to draw. The text itself is untouched, so every link and style keeps its range, and the
 * shortcode is still what reads out or gets copied. Shortcodes without an emoji stay plain text.
 */
fun AnnotatedString.withCustomEmojis(emojis: List<CustomEmoji>): AnnotatedString {
    val found = emojiShortcodes(text, emojis)
    if (found.isEmpty()) return this
    return buildAnnotatedString {
        append(this@withCustomEmojis)
        for (emoji in found) {
            addStringAnnotation(inlineContentTag, emoji.groupValues[1], emoji.range.first, emoji.range.last + 1)
        }
    }
}

/** The `:shortcode:` matches in [text] that have an entry in [emojis]; group 1 is the shortcode. */
internal fun emojiShortcodes(text: String, emojis: List<CustomEmoji>): List<MatchResult> {
    if (emojis.isEmpty()) return emptyList()
    val known = emojis.mapTo(HashSet()) { it.shortcode }
    val found = ArrayList<MatchResult>()
    var match = shortcodePattern.find(text)
    while (match != null) {
        if (match.groupValues[1] in known) {
            found += match
            match = match.next()
        } else {
            // Its closing colon may open the next shortcode (":x:blobcat:").
            match = shortcodePattern.find(text, match.range.last)
        }
    }
    return found
}

/** Draws each of [emojis] as an image a little larger than the text around it. */
fun emojiInlineContent(emojis: List<CustomEmoji>): Map<String, InlineTextContent> =
    emojis.associate { emoji ->
        emoji.shortcode to InlineTextContent(
            Placeholder(1.2.em, 1.2.em, PlaceholderVerticalAlign.TextCenter),
        ) {
            AsyncImage(model = emoji.url, contentDescription = ":${emoji.shortcode}:", modifier = Modifier.fillMaxSize())
        }
    }

/**
 * The custom emoji of the object [sourceId] that [text] could use. Empty until they have loaded,
 * and for good when [text] has no shortcode, in which case nothing is fetched.
 */
@Composable
fun rememberCustomEmojis(sourceId: String?, text: CharSequence): List<CustomEmoji> {
    val source = LocalCustomEmojis.current
    val wanted = remember(text) { hasShortcode(text) }
    if (source == null || sourceId.isNullOrBlank() || !wanted) return emptyList()
    val emojis by produceState(source.cached(sourceId).orEmpty(), source, sourceId) {
        value = source.load(sourceId)
    }
    return emojis
}

/**
 * Where an author's name emoji can be looked up. Lists of accounts carry the ActivityPub id; posts
 * and notifications only the service's row number, so their profile page stands in, which
 * Mastodon and most others answer with the account too.
 */
val Author.emojiSourceId: String?
    get() = id.takeIf { it.startsWith("https://") } ?: url?.takeIf { it.startsWith("https://") }

/**
 * A [Text] for names and other short strings that may hold custom emoji. [emojiSourceId] is where
 * the string comes from, e.g. [Author.emojiSourceId] for a name.
 */
@Composable
fun EmojiText(
    text: String,
    emojiSourceId: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val emojis = rememberCustomEmojis(emojiSourceId, text)
    if (emojis.isEmpty()) {
        Text(text, modifier = modifier, color = color, style = style, maxLines = maxLines, overflow = overflow)
        return
    }
    val marked = remember(text, emojis) { AnnotatedString(text).withCustomEmojis(emojis) }
    val inline = remember(emojis) { emojiInlineContent(emojis) }
    Text(
        marked,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inline,
    )
}

/** [EmojiText] for text that already carries styles, such as a headline with a bold name. */
@Composable
fun EmojiText(
    text: AnnotatedString,
    emojiSourceId: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val emojis = rememberCustomEmojis(emojiSourceId, text.text)
    val marked = remember(text, emojis) { text.withCustomEmojis(emojis) }
    val inline = remember(emojis) { emojiInlineContent(emojis) }
    Text(
        marked,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inline,
    )
}
