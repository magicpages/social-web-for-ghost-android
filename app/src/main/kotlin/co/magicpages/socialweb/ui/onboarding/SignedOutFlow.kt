package co.magicpages.socialweb.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.login.LoginScreen

/**
 * What nobody signed in sees: the welcome, then adding a site. Back from adding a site returns
 * to the welcome; the half-filled form stays in [LoginScreen]'s ViewModel.
 *
 * A [pendingLink] another app asked to open (a fedi.pub App Link, or one shared to the app) waits for
 * the sign-in and then opens. It's said once, with the choice to [onOpenLinkInBrowser] instead.
 */
@Composable
fun SignedOutFlow(pendingLink: String? = null, onOpenLinkInBrowser: (String) -> Unit = {}) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var noticed by rememberSaveable { mutableStateOf<String?>(null) }
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = adding,
        transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec()) },
        label = "signed out",
    ) { showForm ->
        if (showForm) {
            LoginScreen(onBack = { adding = false })
        } else {
            WelcomeScreen(onContinue = { adding = true })
        }
    }
    if (pendingLink != null && pendingLink != noticed) {
        AlertDialog(
            onDismissRequest = { noticed = pendingLink },
            title = { Text(stringResource(R.string.open_link_signed_out_title)) },
            text = { Text(stringResource(R.string.open_link_signed_out_text)) },
            confirmButton = {
                TextButton(onClick = {
                    noticed = pendingLink
                    adding = true
                }) { Text(stringResource(R.string.login_sign_in)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    noticed = pendingLink
                    onOpenLinkInBrowser(pendingLink)
                }) { Text(stringResource(R.string.open_in_browser)) }
            },
        )
    }
}
