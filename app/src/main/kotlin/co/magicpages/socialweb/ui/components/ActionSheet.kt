package co.magicpages.socialweb.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch

/** What an [ActionSheet]'s actions can do: close the sheet, then act. */
class ActionSheetScope internal constructor(private val close: (then: () -> Unit) -> Unit) {
    /**
     * Slides the sheet away, then runs [then]. Only the first call in a sheet's lifetime does
     * anything: a second tap while it slides away must not run another action as well.
     */
    fun closeThen(then: () -> Unit) {
        if (closed) return
        closed = true
        close(then)
    }

    private var closed = false
}

/**
 * A modal bottom sheet of actions, the phone's way to offer what's behind a "more" button or a
 * long list of choices: it rises from the bottom, within thumb reach, and closes on a swipe down,
 * a tap outside or Back. [onDismiss] runs once it's gone, however it closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(onDismiss: () -> Unit, content: @Composable ActionSheetScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    val sheet = remember(state, scope) {
        ActionSheetScope { then ->
            scope.launch { state.hide() }.invokeOnCompletion {
                dismiss()
                then()
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        // Inset, so a highlighted row (the active account) is a floating pill rather than an edge-to-edge bar.
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp)) { sheet.content() }
    }
}

/** A row in an [ActionSheet]: [icon], [text] and an optional [supportingText] line; tapping it closes the sheet, then runs [onClick]. */
@Composable
fun ActionSheetScope.SheetAction(
    text: String,
    icon: ImageVector,
    supportingText: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = SheetAction(text, leadingContent = { Icon(icon, contentDescription = null) }, supportingText, enabled, onClick)

/** A row in an [ActionSheet]: [leadingContent], such as an avatar, [text] and an optional [supportingText] line; tapping it closes the sheet, then runs [onClick]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActionSheetScope.SheetAction(
    text: String,
    leadingContent: @Composable () -> Unit,
    supportingText: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        onClick = { closeThen(onClick) },
        enabled = enabled,
        leadingContent = leadingContent,
        supportingContent = supportingText?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
