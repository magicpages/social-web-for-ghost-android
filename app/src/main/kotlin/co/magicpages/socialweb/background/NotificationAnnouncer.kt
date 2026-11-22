package co.magicpages.socialweb.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import co.magicpages.socialweb.MainActivity
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.Notification
import co.magicpages.socialweb.ui.home.HomeTab
import co.magicpages.socialweb.ui.notifications.NotificationItem
import co.magicpages.socialweb.ui.notifications.headlineRes
import co.magicpages.socialweb.ui.notifications.toItem
import co.magicpages.socialweb.ui.posts.displayName
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Objects
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts Android system notifications for new social web notifications. Each account's are bundled
 * on their own, name the account's site, and open the app on that account.
 */
@Singleton
class NotificationAnnouncer @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /** Safe to call repeatedly. Android 13 only shows the permission dialog for apps that have a channel. */
    fun ensureChannel() {
        val channel = NotificationChannel(
            ChannelId,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    /** Removes everything this app has posted for [accountId], e.g. after signing that account out. */
    fun cancel(accountId: String) {
        val system = context.getSystemService(NotificationManager::class.java)
        for (active in system.activeNotifications) {
            val tag = active.tag ?: continue
            if (tag.startsWith(tagPrefix(accountId))) manager.cancel(tag, active.id)
        }
    }

    /**
     * One system notification per item for the newest [MaxIndividual], bundled under a summary
     * for the whole batch. [notifications] are [account]'s, newest first. Does nothing without permission.
     */
    fun announce(account: StoredAccount, notifications: List<Notification>) {
        if (notifications.isEmpty() || !canAnnounce()) return
        ensureChannel()
        val tap = openNotificationsTab(account.id)
        val items = notifications.take(MaxIndividual).map { it.toItem() }
        val site = account.site.title.ifBlank { account.profile.handle }
        val group = GroupKeyPrefix + account.id

        try {
            // Oldest first, so the newest ends up on top of the shade.
            for (item in items.asReversed()) {
                val posted = item.toItemNotification(account.id, site, group)
                manager.notify(posted.tag, posted.id, posted.build(outcome = null, quiet = false))
            }
            manager.notify(tagPrefix(account.id) + SummaryTag, SummaryId, summary(notifications.size, items, tap, site, group))
        } catch (_: SecurityException) {
            // The permission was withdrawn after canAnnounce(); the next check finds it gone.
        }
    }

    /**
     * Posts [item] again showing what its button did: the result, and Like turned into Unlike. It
     * replaces the notification the button was on, which also ends the inline reply's spinner.
     */
    internal fun update(item: ItemNotification, state: ItemState) {
        if (!canAnnounce()) return
        val posted = item.copy(liked = state.liked)
        try {
            manager.notify(posted.tag, posted.id, posted.build(state.outcome, quiet = true))
        } catch (_: SecurityException) {
            // The permission was withdrawn after canAnnounce().
        }
    }

    internal fun cancel(item: ItemNotification) {
        manager.cancel(item.tag, item.id)
    }

    private fun summary(count: Int, items: List<NotificationItem>, tap: PendingIntent, site: String, group: String) =
        NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notification_summary_title, count, count))
            .setSubText(site)
            .setStyle(
                NotificationCompat.InboxStyle().also { style -> items.forEach { style.addLine(it.title()) } },
            )
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(group)
            .setGroupSummary(true)
            // The items alert; the summary only bundles them.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            // The shade shows the summary's time on the bundle: the newest item's, not when the check ran.
            .also { builder -> items.firstOrNull()?.createdAtMillis?.let { builder.setWhen(it).setShowWhen(true) } }
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()

    /**
     * Whether a system notification would actually show: the permission is granted, the app's
     * notifications are on, and the user hasn't switched this app's channel off.
     */
    fun canAnnounce(): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channelOff = manager.getNotificationChannel(ChannelId)?.importance == NotificationManager.IMPORTANCE_NONE
        return permitted && manager.areNotificationsEnabled() && !channelOff
    }

    private fun NotificationItem.title(): String = context.getString(type.headlineRes, actor.displayName)

    private fun NotificationItem.body(): String? {
        val excerpt = post ?: return null
        return when {
            excerpt.sensitive -> excerpt.text.ifBlank { context.getString(R.string.post_sensitive) }
            else -> excerpt.text.ifBlank { null }
        }
    }

    private fun NotificationItem.toItemNotification(accountId: String, site: String, group: String) = ItemNotification(
        accountId = accountId,
        tag = tagPrefix(accountId) + ItemTag,
        id = id.toLongOrNull()?.toInt() ?: id.hashCode(),
        postId = if (type.hasActions) postId else null,
        title = title(),
        body = body(),
        site = site,
        group = group,
        whenMillis = createdAtMillis,
    )

    /**
     * [outcome] is what the last button did, if any: it replaces the text in the collapsed
     * notification and leads the expanded one, and a sent reply shows as the user's own line.
     */
    private fun ItemNotification.build(outcome: ActionOutcome?, quiet: Boolean): android.app.Notification {
        val banner = when (outcome) {
            null -> null
            is ActionOutcome.ReplySent -> context.getString(R.string.reply_posted)
            is ActionOutcome.ReplyFailed -> context.getString(R.string.reply_failed)
            ActionOutcome.LikeFailed -> context.getString(R.string.like_failed)
        }
        // A failed reply keeps its text on the notification, so nothing the user typed is lost.
        val unsent = (outcome as? ActionOutcome.ReplyFailed)?.text?.ifEmpty { null }
            ?.let { context.getString(R.string.notification_reply_text, it) }
        val status = listOfNotNull(banner, unsent).joinToString(" ").ifEmpty { null }
        val expanded = listOfNotNull(status, body).joinToString("\n\n").ifEmpty { null }
        return NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(status ?: body)
            .setSubText(site)
            .setStyle(expanded?.let { NotificationCompat.BigTextStyle().bigText(it) })
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(group)
            // Updates after a button must not make the phone ring again.
            .setOnlyAlertOnce(true)
            .setSilent(quiet)
            .also { builder -> whenMillis?.let { builder.setWhen(it).setShowWhen(true) } }
            .also { builder -> (outcome as? ActionOutcome.ReplySent)?.let { builder.setRemoteInputHistory(arrayOf(it.text)) } }
            .also { builder ->
                if (postId != null) {
                    builder.addAction(likeAction())
                    builder.addAction(replyAction())
                }
            }
            .setContentIntent(openNotificationsTab(accountId))
            .setAutoCancel(true)
            .build()
    }

    /** Like, or once it was used, Unlike: tapping it again takes the like back. */
    private fun ItemNotification.likeAction(): NotificationCompat.Action {
        val action = if (liked) NotificationAction.Unlike else NotificationAction.Like
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(if (liked) R.string.action_unlike else R.string.action_like),
            actionIntent(action, mutable = false),
        )
            .setSemanticAction(if (liked) NotificationCompat.Action.SEMANTIC_ACTION_NONE else NotificationCompat.Action.SEMANTIC_ACTION_THUMBS_UP)
            .setShowsUserInterface(false)
            .build()
    }

    private fun ItemNotification.replyAction(): NotificationCompat.Action {
        val input = RemoteInput.Builder(NotificationAction.ReplyKey)
            .setLabel(context.getString(R.string.action_reply))
            .build()
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.action_reply),
            // The system fills the typed text into this intent, so it can't be immutable.
            actionIntent(NotificationAction.Reply, mutable = true),
        )
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun ItemNotification.actionIntent(action: NotificationAction, mutable: Boolean): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action.intentAction)
            // Intents that differ only in their extras are the same PendingIntent; the data keeps each notification's and action's own.
            .setData(Uri.Builder().scheme("socialweb").authority("notification").appendPath(tag).appendPath(id.toString()).appendPath(action.name).build())
        putInto(intent)
        return checkNotNull(
            PendingIntentCompat.getBroadcast(context, Objects.hash(tag, id, action.name), intent, PendingIntent.FLAG_UPDATE_CURRENT, mutable),
        )
    }

    /**
     * Asks whether to share [reminder]'s article again; tapping it opens the app on [account] with a new
     * note about the article. Does nothing without permission. Its tag starts with the account id, so
     * signing the account out removes it with the rest.
     */
    fun announceShareAgain(account: StoredAccount, reminder: ShareAgainReminder) {
        if (!canAnnounce()) return
        ensureChannel()
        val tap = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // The data keeps each article's tap its own PendingIntent: intents that differ only in their extras are the same one.
            .setData(Uri.Builder().scheme("socialweb").authority("share-again").appendPath(account.id).appendPath(reminder.postId).build())
            .let(reminder::putInto)
        val open = PendingIntent.getActivity(
            context,
            Objects.hash(account.id, reminder.postId),
            tap,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.share_again_notification_title, reminder.title.ifBlank { reminder.url }))
            .setContentText(context.getString(R.string.share_again_notification_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.share_again_notification_text)))
            .setSubText(account.site.title.ifBlank { account.profile.handle })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(tagPrefix(account.id) + ShareAgainTag, reminder.postId.hashCode(), notification)
        } catch (_: SecurityException) {
            // The permission was withdrawn after canAnnounce().
        }
    }

    private fun openNotificationsTab(accountId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            // With MainActivity's singleTop this reuses a running instance instead of stacking a second one.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.ExtraOpenTab, HomeTab.Notifications.name)
            .putExtra(MainActivity.ExtraOpenAccount, accountId)
        // Intents that differ only in their extras are the same PendingIntent; the request code keeps each account's own.
        return PendingIntent.getActivity(
            context,
            accountId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val ChannelId = "notifications"
        const val GroupKeyPrefix = "co.magicpages.socialweb.NOTIFICATIONS:"
        const val ItemTag = "item"
        const val ShareAgainTag = "share-again"
        const val SummaryTag = "summary"
        const val SummaryId = 0
        const val MaxIndividual = 5

        /** Notification tags start with the account id and a slash, so one account's can be found and removed. */
        fun tagPrefix(accountId: String) = "$accountId/"
    }
}
