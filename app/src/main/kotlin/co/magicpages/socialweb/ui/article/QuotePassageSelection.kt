package co.magicpages.socialweb.ui.article

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import co.magicpages.socialweb.R

/**
 * Makes the text in [content] selectable, with "Post this quote" beside Copy in the selection's menu, which
 * hands the selected words to [onQuote]. Without [onQuote] it shows [content] as it is: only the writer's own
 * articles offer this, and everyone else's keep the reader as it was.
 *
 * The menu's items can't see the selection, only trigger the Copy next to them. So the item runs that Copy
 * and takes the words from where it puts them: the [Clipboard] in [content]'s composition, which keeps
 * them away from the system's clipboard when it was the item that asked for them.
 */
@Composable
internal fun QuotePassageSelection(onQuote: ((String) -> Unit)?, content: @Composable () -> Unit) {
    if (onQuote == null) {
        content()
        return
    }
    val system = LocalClipboard.current
    val quote by rememberUpdatedState(onQuote)
    val clipboard = remember(system) { PassageClipboard(system) }
    // The menu is built when it opens; this keeps hold of the Copy item it was given.
    val copy = remember { arrayOfNulls<TextContextMenuItem>(1) }
    val label = stringResource(R.string.action_post_quote)
    // Provided around the container, not inside it: the container reads the clipboard it copies to where it is composed.
    CompositionLocalProvider(LocalClipboard provides clipboard) {
        SelectionContainer(
            Modifier
                .appendTextContextMenuComponents {
                    item(key = PostQuoteKey, label = label) {
                        val copyItem = copy[0]
                        if (copyItem == null) {
                            close()
                        } else {
                            clipboard.onPassage = { words -> quote(words) }
                            // Copy closes the menu itself.
                            copyItem.onClick(this)
                        }
                    }
                }
                .filterTextContextMenuComponents { component ->
                    if (component is TextContextMenuItem && component.key == TextContextMenuKeys.CopyKey) copy[0] = component
                    true
                },
            content = content,
        )
    }
}

private const val PostQuoteKey = "co.magicpages.socialweb.POST_QUOTE"

/** Passes everything to the [system] clipboard, except the one copy that [onPassage] is waiting for. */
private class PassageClipboard(private val system: Clipboard) : Clipboard {
    var onPassage: ((String) -> Unit)? = null

    override val nativeClipboard get() = system.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? = system.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val waiting = onPassage
        if (waiting == null) {
            system.setClipEntry(clipEntry)
            return
        }
        onPassage = null
        val data = clipEntry?.clipData
        val words = if (data != null && data.itemCount > 0) data.getItemAt(0).text?.toString()?.trim() else null
        if (!words.isNullOrEmpty()) waiting(words)
    }
}
