package co.magicpages.socialweb.ui.moderation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Reporting the account with ActivityPub id [accountId] (`@user@host` [handle], display [name]),
 * starting from its post [postId] (web [postUrl]) when the report is about one.
 */
@Serializable
data class ReportKey(
    val accountId: String,
    val handle: String,
    val name: String,
    val postId: String? = null,
    val postUrl: String? = null,
) : NavKey

/** Opens the report flow from wherever a post or profile offers "Report"; null outside the signed-in screens. */
val LocalOpenReport = staticCompositionLocalOf<((ReportKey) -> Unit)?> { null }
