package co.magicpages.socialweb.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler

/**
 * Opens an address in the browser, whatever it looks like. `LocalUriHandler` opens fediverse post links
 * in the app, so a button that means "open in browser" (or a link the app already failed to open) must
 * not go through it. Null in previews, where [browserUriHandler] falls back to `LocalUriHandler`.
 */
val LocalBrowserUriHandler = staticCompositionLocalOf<UriHandler?> { null }

@Composable
fun browserUriHandler(): UriHandler = LocalBrowserUriHandler.current ?: LocalUriHandler.current
