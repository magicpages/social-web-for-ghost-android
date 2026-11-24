package co.magicpages.socialweb.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.magicpages.socialweb.R
import co.magicpages.socialweb.background.NotificationAnnouncer
import co.magicpages.socialweb.data.activitypub.Notification
import co.magicpages.socialweb.data.settings.AppSettings
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.theme.Gutter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Each switchable kind of notification with its label, in the order the screen lists them. */
internal val AnnouncedTypeLabels: List<Pair<Notification.Type, Int>> = listOf(
    Notification.Type.Reply to R.string.settings_notify_replies,
    Notification.Type.Mention to R.string.settings_notify_mentions,
    Notification.Type.Like to R.string.settings_notify_likes,
    Notification.Type.Repost to R.string.settings_notify_reposts,
    Notification.Type.Follow to R.string.settings_notify_follows,
)

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val settings: AppSettings,
    private val announcer: NotificationAnnouncer,
) : ViewModel() {
    val announcedTypes: StateFlow<Set<Notification.Type>> = settings.announcedTypes

    private val _allowed = MutableStateFlow(announcer.canAnnounce())

    /** Whether Android would show a system notification right now. It changes outside the app, so see [refreshAllowed]. */
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    /** The permission and channel are changed in Android's settings: look again when the screen is shown. */
    fun refreshAllowed() {
        _allowed.value = announcer.canAnnounce()
    }

    fun setAnnounced(type: Notification.Type, enabled: Boolean) = settings.setAnnounced(type, enabled)
}

/** Which kinds of activity become system notifications, for every signed-in site. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit, viewModel: NotificationSettingsViewModel = hiltViewModel()) {
    val announced by viewModel.announcedTypes.collectAsStateWithLifecycle()
    val allowed by viewModel.allowed.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAllowed()
        onPauseOrDispose {}
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_notifications)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = MaxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Gutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!allowed) NotificationsBlocked()
                SegmentedGroup {
                    AnnouncedTypeLabels.forEachIndexed { index, (type, label) ->
                        SwitchRow(
                            title = stringResource(label),
                            checked = type in announced,
                            onCheckedChange = { viewModel.setAnnounced(type, it) },
                            index = index,
                            count = AnnouncedTypeLabels.size,
                        )
                    }
                }
                Explainer(stringResource(R.string.settings_notifications_explainer))
            }
        }
    }
}

/** Shown while Android won't display this app's notifications: the choices below can't take effect until that's fixed there. */
@Composable
private fun NotificationsBlocked() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.NotificationsOff, contentDescription = null)
                Text(stringResource(R.string.settings_notifications_blocked_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.settings_notifications_blocked_body), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            ) {
                Text(stringResource(R.string.settings_open_notification_settings))
            }
        }
    }
}
