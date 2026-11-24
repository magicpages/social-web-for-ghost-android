package co.magicpages.socialweb.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.MentionDirectory

/** Where composers find people to mention. Null in previews, where nothing is suggested. */
val LocalMentionDirectory = staticCompositionLocalOf<MentionDirectory?> { null }

/** The `@…` word the cursor is in: it spans [start] until [end], and [query] is what follows the `@`. */
data class MentionToken(val start: Int, val end: Int, val query: String)

private fun isHandleChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '-' || c == '@'

/**
 * The mention being typed at a collapsed cursor in [value]: a word that starts with `@` (so not the
 * middle of an email address) and holds at most one more `@`, for the domain.
 */
fun mentionAt(value: TextFieldValue): MentionToken? {
    if (!value.selection.collapsed) return null
    val text = value.text
    val caret = value.selection.start
    var start = caret
    while (start > 0 && isHandleChar(text[start - 1])) start--
    val word = text.substring(start, caret)
    if (!word.startsWith('@')) return null
    if (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] !in "([{\"'") return null
    val query = word.substring(1)
    if (query.count { it == '@' } > 1 || query.length > MaxQueryLength) return null
    var end = caret
    while (end < text.length && isHandleChar(text[end])) end++
    return MentionToken(start, end, query)
}

/**
 * [value] with [token] swapped for `@user@domain`, and the cursor after it: past a space that
 * follows, which is added at the end of the text; punctuation right after it stays put.
 */
fun insertMention(value: TextFieldValue, token: MentionToken, handle: String): TextFieldValue {
    val mention = "@" + handle.trim().removePrefix("@")
    val rest = value.text.substring(token.end)
    val next = rest.firstOrNull()
    val inserted = if (next == null) "$mention " else mention
    val caret = token.start + mention.length + if (next == null || next.isWhitespace()) 1 else 0
    return TextFieldValue(value.text.substring(0, token.start) + inserted + rest, TextRange(caret))
}

private const val MaxQueryLength = 64

/**
 * A composer's text with its cursor, which mentions need. The composer keeps the draft as plain
 * text; [sync] follows it when it changes from outside (a shared page added below, say).
 */
@Stable
class ComposerText(initial: TextFieldValue) {
    var value by mutableStateOf(initial)

    /** The mention being typed, if any. */
    val mention: MentionToken? by derivedStateOf { mentionAt(value) }

    fun sync(text: String) {
        if (value.text != text) value = startingValue(text)
    }

    /** Puts [author] in place of the mention being typed; returns the new text. */
    fun pick(author: Author): String {
        val token = mention ?: return value.text
        value = insertMention(value, token, author.handle)
        return value.text
    }

    companion object {
        val Saver: Saver<ComposerText, *> = Saver(
            save = { with(TextFieldValue.Saver) { save(it.value) } },
            restore = { saved -> TextFieldValue.Saver.restore(saved!!)?.let(::ComposerText) },
        )
    }
}

@Composable
fun rememberComposerText(text: String): ComposerText =
    rememberSaveable(saver = ComposerText.Saver) { ComposerText(startingValue(text)) }

/**
 * [text] the writer didn't type, with the cursor at its end; or at its start when it opens with an empty
 * line, which a note about a post or link leaves for the writer's own words above the link.
 */
internal fun startingValue(text: String): TextFieldValue =
    TextFieldValue(text, TextRange(if (text.startsWith("\n")) 0 else text.length))

/**
 * The bar above the keyboard in a composer: [actions] (adding an image, say), or, while an `@…`
 * is typed in [text], a row of people to mention in their place. Those the writer knows (in
 * [thread], mutuals, follows, followers, recent replies) come first, then accounts the search
 * finds. Picking one writes their full handle and hands the new text to [onPick].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComposerBottomBar(
    text: ComposerText,
    thread: List<Author>,
    onPick: (String) -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val directory = LocalMentionDirectory.current
    LaunchedEffect(directory) { directory?.warmUp() }
    val query = text.mention?.query
    val suggestions by produceState(emptyList<Author>(), directory, query, thread) {
        if (directory == null || query == null) {
            value = emptyList()
        } else {
            directory.suggestions(query, thread).collect { value = it }
        }
    }
    FlexibleBottomAppBar(
        windowInsets = WindowInsets.navigationBars.union(WindowInsets.ime),
        // The people run to the bar's edges as they scroll; the actions keep the bar's own padding.
        contentPadding = PaddingValues(0.dp),
    ) {
        AnimatedContent(
            targetState = suggestions.isNotEmpty(),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.fillMaxWidth(),
            label = "composer bar",
        ) { mentioning ->
            if (mentioning) {
                MentionChips(suggestions, query, onPick = { onPick(text.pick(it)) })
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(BottomAppBarDefaults.FlexibleContentPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions()
                    Spacer(Modifier.weight(1f))
                    CharacterCounter(text.value.text)
                }
            }
        }
    }
}

/**
 * What is left of [SoftCharacterLimit] for [text]. Ghost doesn't cap a note, but servers that show
 * it (Mastodon's default is 500) may cut it short, so this only informs: it never stops a send.
 * Past the limit the count goes negative, in the error colour.
 */
@Composable
private fun CharacterCounter(text: String, modifier: Modifier = Modifier) {
    val left = remember(text) { SoftCharacterLimit - mastodonLength(text) }
    val description = if (left >= 0) {
        pluralStringResource(R.plurals.composer_characters_left, left, left)
    } else {
        pluralStringResource(R.plurals.composer_characters_over, -left, -left)
    }
    Text(
        left.toString(),
        style = MaterialTheme.typography.labelLarge,
        color = if (left < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { contentDescription = description },
    )
}

/** The length the counter counts down from: Mastodon's default, the most common limit on the fediverse. */
private const val SoftCharacterLimit = 500

/**
 * The length of [text] as Mastodon counts it: trimmed, in code points, with every http(s) link as
 * [LinkLength] and a remote mention `@user@domain` as `@user`.
 */
internal fun mastodonLength(text: String): Int {
    val counted = text.trim()
        .replace(LinkPattern, "x".repeat(LinkLength))
        .replace(RemoteMentionPattern, "$1")
    return counted.codePointCount(0, counted.length)
}

private const val LinkLength = 23
private val LinkPattern = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val RemoteMentionPattern = Regex("""(?<![\w@])(@[\w.-]+)@[\w.-]+""")

@Composable
private fun MentionChips(people: List<Author>, query: String?, onPick: (Author) -> Unit) {
    val list = rememberLazyListState()
    // A new letter can bring a better fit; it shows at the start, not off to the side.
    LaunchedEffect(query) { list.scrollToItem(0) }
    LazyRow(
        state = list,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(people, key = { it.handle }) { author ->
            MentionChip(author, onClick = { onPick(author) }, modifier = Modifier.animateItem())
        }
    }
}

/**
 * A suggestion chip with the person's avatar, name and handle, as wide as they need up to a
 * limit; past it, the handle gives way.
 */
@Composable
private fun MentionChip(author: Author, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val name = author.name.ifBlank { author.handle }
    SuggestionChip(
        onClick = onClick,
        // An input chip's avatar size: a face needs more room than a symbol.
        icon = { Avatar(author.avatarUrl, name, InputChipDefaults.AvatarSize) },
        label = {
            // No weights: a weighted child makes a row take all the width it may, which left room
            // to spare at the end while the handle was cut short. Measured in turn, the handle gets
            // what the name leaves.
            Row(Modifier.widthIn(max = MaxChipLabelWidth), verticalAlignment = Alignment.CenterVertically) {
                EmojiText(
                    name,
                    author.emojiSourceId,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = MaxChipNameWidth),
                )
                if (author.name.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        author.handle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        },
        modifier = modifier,
    )
}

private val MaxChipLabelWidth = 280.dp
private val MaxChipNameWidth = 160.dp
