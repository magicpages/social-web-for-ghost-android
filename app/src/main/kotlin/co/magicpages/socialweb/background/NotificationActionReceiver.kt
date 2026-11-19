package co.magicpages.socialweb.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Receives the Like, Unlike and inline Reply buttons of item notifications and hands them to
 * [NotificationActionHandler]. The broadcast stays open until the notification shows the result.
 * Dependencies come through an entry point: a Kotlin receiver cannot make the `super.onReceive`
 * call that `@AndroidEntryPoint` injection needs, since the declared supertype's is abstract.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun notificationActionHandler(): NotificationActionHandler
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = NotificationAction.of(intent.action) ?: return
        val item = ItemNotification.from(intent) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationAction.ReplyKey)
            ?.toString()
            ?.trim()
            ?.ifEmpty { null }
        val handler = EntryPointAccessors.fromApplication<Dependencies>(context).notificationActionHandler()
        val pending = goAsync()
        handler.handle(action, item, reply, pending::finish)
    }
}
