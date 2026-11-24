package co.magicpages.socialweb.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * An `@user@server` handle on one line. When it doesn't fit, the user part gives way first: the
 * server is what tells two accounts of the same name apart, so it stays whole unless it's too long
 * on its own, and then it's shortened in its middle. TalkBack reads the handle in one piece.
 */
@Composable
fun HandleText(
    handle: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val split = handle.lastIndexOf('@').takeIf { it > 0 }
    if (split == null) {
        Text(handle, modifier, color = color, style = style, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        return
    }
    val user = handle.substring(0, split)
    val server = handle.substring(split)
    Layout(
        content = {
            Text(user, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(server, color = color, style = style, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        },
        modifier = modifier.clearAndSetSemantics { text = AnnotatedString(handle) },
    ) { (userPart, serverPart), constraints ->
        val loose = constraints.copy(minWidth = 0)
        // The user part keeps room for a letter and its ellipsis, so the handle never reads as just a server.
        val userMin = minOf(MinUserPart.roundToPx(), userPart.maxIntrinsicWidth(constraints.maxHeight))
        val serverPlaced = serverPart.measure(loose.copy(maxWidth = (constraints.maxWidth - userMin).coerceAtLeast(0)))
        val userPlaced = userPart.measure(loose.copy(maxWidth = (constraints.maxWidth - serverPlaced.width).coerceAtLeast(0)))
        val width = (userPlaced.width + serverPlaced.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = maxOf(userPlaced.height, serverPlaced.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            // A handle reads left to right in any layout, so the parts aren't mirrored.
            userPlaced.place(0, 0)
            serverPlaced.place(userPlaced.width, 0)
        }
    }
}

/** The least of the user part a squeezed [HandleText] keeps: about "@k…". */
private val MinUserPart = 32.dp
