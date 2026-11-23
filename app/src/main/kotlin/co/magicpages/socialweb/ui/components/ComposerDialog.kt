package co.magicpages.socialweb.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.Profile

/** The signed-in account's profile, for the writer's avatar in composers. Null in previews. */
val LocalOwnProfile = staticCompositionLocalOf<Profile?> { null }

/**
 * The frame every composer shares: a full-screen dialog, as Material asks for tasks that need the
 * keyboard, with Close and the send button in the top bar. Closing with a [hasDraft] asks first;
 * nothing closes while [sending]. [bottomBar] rides above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComposerDialog(
    title: String,
    sendLabel: String,
    discardTitle: String,
    canSend: Boolean,
    sending: Boolean,
    hasDraft: Boolean,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!sending) {
            if (hasDraft) confirmDiscard = true else onDiscard()
        }
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogSystemBars()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = close, enabled = !sending) {
                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.composer_close))
                        }
                    },
                    actions = {
                        Button(onClick = onSend, enabled = canSend && !sending, modifier = Modifier.padding(end = 8.dp)) {
                            if (sending) LoadingIndicator(Modifier.size(ButtonDefaults.IconSize)) else Text(sendLabel)
                        }
                    },
                )
            },
            bottomBar = bottomBar,
            // For the top and sides; the bottom bar always clears the navigation bar and keyboard.
            contentWindowInsets = WindowInsets.safeDrawing,
            content = content,
        )

        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text(discardTitle) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDiscard = false
                        onDiscard()
                    }) { Text(stringResource(R.string.composer_discard)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.composer_keep_editing)) }
                },
            )
        }
    }
}

/**
 * A borderless field that takes the keyboard as soon as the composer opens. [text] carries the
 * cursor along with [value], for [ComposerBottomBar] to see which mention is being typed.
 */
@Composable
fun ComposerField(
    text: ComposerText,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    SideEffect { text.sync(value) }
    TextField(
        value = if (text.value.text == value) text.value else startingValue(value),
        onValueChange = {
            text.value = it
            if (it.text != value) onValueChange(it.text)
        },
        placeholder = { Text(placeholder) },
        enabled = enabled,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth().focusRequester(focus),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Why the last send failed, above the draft. */
@Composable
fun ComposerFailure(message: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

/** A dialog window draws its own system bars: give them icons that show on this surface. */
@Composable
internal fun DialogSystemBars() {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}
