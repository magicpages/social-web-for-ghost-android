package co.magicpages.socialweb.ui.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
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
