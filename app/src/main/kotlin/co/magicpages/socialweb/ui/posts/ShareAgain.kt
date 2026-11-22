package co.magicpages.socialweb.ui.posts

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.background.ShareAgainDelay
import co.magicpages.socialweb.background.ShareAgainReminder
import co.magicpages.socialweb.background.ShareAgainReminders
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.ui.components.ActionSheet
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.SheetAction
import co.magicpages.socialweb.ui.notifications.rememberNotificationPermissionRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class ShareAgainViewModel @Inject constructor(
    private val accounts: AccountStore,
    private val reminders: ShareAgainReminders,
) : ViewModel() {
    /**
     * Sets the reminder to share [article] again [delay] from now, for the active account, and passes
     * when it goes off to [onScheduled]; null when it couldn't be set (no account, or no web page to link to).
     * Runs in this ViewModel's scope, so the sheet it was set from can be gone by then.
     */
    fun schedule(article: Post, delay: ShareAgainDelay, onScheduled: (ZonedDateTime?) -> Unit) {
        viewModelScope.launch {
            val accountId = accounts.activeId.first()
            val reminder = accountId?.let { ShareAgainReminder.of(it, article) }
            onScheduled(reminder?.let { reminders.schedule(it, delay) })
        }
    }
}

/** Whether the post's menu offers "Remind me to share again": the writer's own articles, which have a page to link to. */
internal val Post.canRemindToShare: Boolean get() = authoredByMe && isArticle && webUrl != null

/**
 * The sheet of "Remind me to share again": tomorrow, in 3 days or in a week, each with the day and time it
 * goes off. The choice asks for the notification permission when it is missing, sets the reminder and
 * confirms with its date. Fediverse timelines are chronological, so a second post a few days later
 * reaches the followers who missed the first.
 *
 * Stays in the composition until that is done, as the permission dialog's answer comes back to it; calls
 * [onFinished] then, or as soon as the sheet closes without a choice.
 */
@Composable
internal fun ShareAgainSheet(article: Post, onFinished: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel = hiltViewModel<ShareAgainViewModel>()
    val requestPermission = rememberNotificationPermissionRequest()
    val finished by rememberUpdatedState(onFinished)
    var open by remember { mutableStateOf(true) }
    var chosen by remember { mutableStateOf<ShareAgainDelay?>(null) }
    // The sheet reports it is gone before the tapped choice runs, so a close without a choice can only be told apart a moment later.
    LaunchedEffect(open, chosen) {
        if (!open && chosen == null) finished()
    }
    val now = remember { ZonedDateTime.now() }
    if (open) {
        ActionSheet(onDismiss = { open = false }) {
            Text(
                stringResource(R.string.action_remind_share_again),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            for (delay in ShareAgainDelay.entries) {
                SheetAction(
                    stringResource(delay.label),
                    AppIcons.Notifications,
                    supportingText = formatReminderTime(context, delay.triggerAt(now)),
                ) {
                    chosen = delay
                    requestPermission { notificationsOn ->
                        viewModel.schedule(article, delay) { at ->
                            if (at != null) {
                                val message = if (notificationsOn) R.string.share_again_set else R.string.share_again_set_muted
                                Toast.makeText(context, resources.getString(message, formatReminderTime(context, at)), Toast.LENGTH_LONG).show()
                            }
                            finished()
                        }
                    }
                }
            }
        }
    }
}

private val ShareAgainDelay.label: Int
    get() = when (this) {
        ShareAgainDelay.Tomorrow -> R.string.share_again_tomorrow
        ShareAgainDelay.InThreeDays -> R.string.share_again_three_days
        ShareAgainDelay.InAWeek -> R.string.share_again_week
    }

/** The day and time of [at] as the phone's language writes them: "Tue, Nov 24, 9:00 AM". */
private fun formatReminderTime(context: Context, at: ZonedDateTime): String =
    DateUtils.formatDateTime(
        context,
        at.toInstant().toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
    )
