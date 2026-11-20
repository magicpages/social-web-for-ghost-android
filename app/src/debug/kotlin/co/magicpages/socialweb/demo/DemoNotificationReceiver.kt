package co.magicpages.socialweb.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.magicpages.socialweb.background.NotificationAnnouncer
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.Notification
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug builds only: posts the demo account's reply and mention as system notifications, with their
 * Like and Reply buttons, without a background check or a second device. Needs demo mode to be on
 * in the running process and the notification permission:
 *
 * ```
 * adb shell pm grant co.magicpages.socialweb.debug android.permission.POST_NOTIFICATIONS
 * adb shell am start -n co.magicpages.socialweb.debug/co.magicpages.socialweb.MainActivity --ez demo true
 * adb shell input keyevent KEYCODE_HOME
 * adb shell am broadcast -n co.magicpages.socialweb.debug/co.magicpages.socialweb.demo.DemoNotificationReceiver
 * ```
 *
 * Does nothing outside demo mode, so it can never announce a real account's notifications.
 */
class DemoNotificationReceiver : BroadcastReceiver() {
    /** An entry point rather than `@AndroidEntryPoint`, as in [co.magicpages.socialweb.background.NotificationActionReceiver]. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun accounts(): AccountStore
        fun client(): ActivityPubClient
        fun announcer(): NotificationAnnouncer
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!DemoMode.enabled.value) return
        val deps = EntryPointAccessors.fromApplication<Dependencies>(context)
        val accounts = deps.accounts()
        val client = deps.client()
        val announcer = deps.announcer()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val account = accounts.account.first() ?: return@launch
                val page = client.notifications(account.site, account.credential)
                val withButtons = page.notifications.filter { it.type == Notification.Type.Reply || it.type == Notification.Type.Mention }
                announcer.announce(account, withButtons)
            } catch (_: GhostException) {
                // Debug helper: nothing to show without the demo data.
            } catch (_: SocialWebException) {
            } finally {
                pending.finish()
            }
        }
    }
}
