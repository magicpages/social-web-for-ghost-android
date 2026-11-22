package co.magicpages.socialweb.ui.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import co.magicpages.socialweb.background.NotificationPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NotificationPermissionViewModel @Inject constructor(
    private val prefs: NotificationPrefs,
) : ViewModel() {
    /** True the first time it is asked, false ever after: the dialog is shown once, not nagged. */
    fun takeRequest(): Boolean {
        if (prefs.permissionRequested) return false
        prefs.permissionRequested = true
        return true
    }

    /** Records that the dialog was shown, so the notifications tab doesn't show it again on top of an answer just given. */
    fun markRequested() {
        prefs.permissionRequested = true
    }
}

/**
 * Shows the system dialog for the notification permission the first time the user opens the
 * notifications tab. Before Android 13 there is no permission to ask for.
 */
@Composable
fun RequestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val viewModel = hiltViewModel<NotificationPermissionViewModel>()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(viewModel) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && viewModel.takeRequest()) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * Asks for the notification permission when something the user just set up needs it, such as a reminder,
 * and then tells whether notifications will show. Calling the returned function with `onResult` shows the
 * system dialog when the permission is missing (Android 13 and later), and runs `onResult` straight away
 * otherwise. Android stops showing the dialog after it was turned down twice; the answer is then simply
 * "off". Unlike [RequestNotificationPermissionOnce], this asks every time, as the user just made a choice
 * that depends on it.
 */
@Composable
fun rememberNotificationPermissionRequest(): (onResult: (notificationsOn: Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    val viewModel = hiltViewModel<NotificationPermissionViewModel>()
    val waiting = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        waiting[0]?.invoke(NotificationManagerCompat.from(context).areNotificationsEnabled())
        waiting[0] = null
    }
    return remember(context, viewModel, launcher) {
        { onResult ->
            val missing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (missing) {
                waiting[0] = onResult
                viewModel.markRequested()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onResult(NotificationManagerCompat.from(context).areNotificationsEnabled())
            }
        }
    }
}
