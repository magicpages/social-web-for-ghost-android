package co.magicpages.socialweb.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import co.magicpages.socialweb.ui.login.LoginScreen

/**
 * What nobody signed in sees: the welcome, then adding a site. Back from adding a site returns
 * to the welcome; the half-filled form stays in [LoginScreen]'s ViewModel.
 */
@Composable
fun SignedOutFlow() {
    var adding by rememberSaveable { mutableStateOf(false) }
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
}
