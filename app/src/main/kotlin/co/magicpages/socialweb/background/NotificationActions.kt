package co.magicpages.socialweb.background

import android.content.Intent
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.Notification
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Only a reply or a mention is itself a post the user can like and answer; for the rest the post is their own or there is none. */
internal val Notification.Type.hasActions: Boolean
    get() = this == Notification.Type.Reply || this == Notification.Type.Mention

/** The buttons on an item notification, each delivered to [NotificationActionReceiver] under its [intentAction]. */
internal enum class NotificationAction(val intentAction: String) {
    Like("co.magicpages.socialweb.notification.LIKE"),
    Unlike("co.magicpages.socialweb.notification.UNLIKE"),
    Reply("co.magicpages.socialweb.notification.REPLY"),
    ;

    companion object {
        /** The key of the inline reply's text in the remote input results. */
        const val ReplyKey = "reply"

        fun of(intentAction: String?): NotificationAction? = entries.firstOrNull { it.intentAction == intentAction }
    }
}

/**
 * An item notification as posted. Its action intents carry all of it, so the receiver can post the
 * notification again with an outcome without keeping any state, even in a fresh process.
 *
 * @property accountId the account the notification belongs to; actions run as this one, not as the active one.
 * @property postId the ActivityPub id of the post the actions apply to; null for notifications without actions.
 */
internal data class ItemNotification(
    val accountId: String,
    val tag: String,
    val id: Int,
    val postId: String?,
    val title: String,
    val body: String?,
    val site: String,
    val group: String,
    val whenMillis: Long?,
    /** Whether the like button has been used: the button then offers to undo it. */
    val liked: Boolean = false,
) {
    fun putInto(intent: Intent): Intent = intent
        .putExtra(ExtraAccount, accountId)
        .putExtra(ExtraTag, tag)
        .putExtra(ExtraId, id)
        .putExtra(ExtraPost, postId)
        .putExtra(ExtraTitle, title)
        .putExtra(ExtraBody, body)
        .putExtra(ExtraSite, site)
        .putExtra(ExtraGroup, group)
        .putExtra(ExtraLiked, liked)
        .also { if (whenMillis != null) it.putExtra(ExtraWhen, whenMillis) }

    companion object {
        private const val ExtraAccount = "account"
        private const val ExtraTag = "tag"
        private const val ExtraId = "id"
        private const val ExtraPost = "post"
        private const val ExtraTitle = "title"
        private const val ExtraBody = "body"
        private const val ExtraSite = "site"
        private const val ExtraGroup = "group"
        private const val ExtraWhen = "when"
        private const val ExtraLiked = "liked"

        /** Null for an intent that isn't one of ours. */
        fun from(intent: Intent): ItemNotification? {
            val accountId = intent.getStringExtra(ExtraAccount) ?: return null
            val tag = intent.getStringExtra(ExtraTag) ?: return null
            if (!intent.hasExtra(ExtraId)) return null
            return ItemNotification(
                accountId = accountId,
                tag = tag,
                id = intent.getIntExtra(ExtraId, 0),
                postId = intent.getStringExtra(ExtraPost),
                title = intent.getStringExtra(ExtraTitle).orEmpty(),
                body = intent.getStringExtra(ExtraBody),
                site = intent.getStringExtra(ExtraSite).orEmpty(),
                group = intent.getStringExtra(ExtraGroup).orEmpty(),
                whenMillis = if (intent.hasExtra(ExtraWhen)) intent.getLongExtra(ExtraWhen, 0) else null,
                liked = intent.getBooleanExtra(ExtraLiked, false),
            )
        }
    }
}

/** What an action did, shown on the notification itself. */
internal sealed interface ActionOutcome {
    data class ReplySent(val text: String) : ActionOutcome

    /** Carries the text so it isn't lost: the notification shows it with the error. */
    data class ReplyFailed(val text: String) : ActionOutcome

    data object LikeFailed : ActionOutcome
}

/** What an item notification shows beyond its content: whether it was liked from the notification, and the last action's outcome. */
internal data class ItemState(val liked: Boolean = false, val outcome: ActionOutcome? = null)

/**
 * Runs the notification buttons: likes and replies as the account the notification belongs to, then
 * posts the notification again with the result, which also stops the inline reply's spinner.
 */
@Singleton
class NotificationActionHandler @Inject constructor(
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
    private val announcer: NotificationAnnouncer,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Runs [action] and calls [finished] once the notification shows the result, whatever it was. */
    internal fun handle(action: NotificationAction, item: ItemNotification, replyText: String?, finished: () -> Unit) {
        scope.launch {
            try {
                // A broadcast that was taken over with goAsync() has about ten seconds; a slow request is a failure, not an ANR.
                val state = try {
                    withTimeout(RequestTimeoutMillis) { perform(action, item, replyText) }
                } catch (_: TimeoutCancellationException) {
                    failed(action, item, replyText)
                }
                if (state != null) announcer.update(item, state) else announcer.cancel(item)
            } finally {
                finished()
            }
        }
    }

    /** Null when the account is gone: its notifications go with it. */
    private suspend fun perform(action: NotificationAction, item: ItemNotification, replyText: String?): ItemState? {
        // Nothing to like or send: the notification is only posted again, which stops the inline reply's spinner.
        val postId = item.postId ?: return ItemState(item.liked)
        if (action == NotificationAction.Reply && replyText == null) return ItemState(item.liked)
        // The notification's own account: another one may be active by now.
        val account = accounts.accounts.first().firstOrNull { it.id == item.accountId } ?: return null
        return try {
            when (action) {
                NotificationAction.Like -> client.like(account.site, account.credential, postId)
                NotificationAction.Unlike -> client.unlike(account.site, account.credential, postId)
                NotificationAction.Reply -> client.reply(account.site, account.credential, postId, replyText.orEmpty())
            }
            when (action) {
                NotificationAction.Like -> ItemState(liked = true)
                NotificationAction.Unlike -> ItemState(liked = false)
                NotificationAction.Reply -> ItemState(item.liked, ActionOutcome.ReplySent(replyText.orEmpty()))
            }
        } catch (e: SocialWebException) {
            when {
                // Already in the requested state on the server.
                e.status == 409 && action == NotificationAction.Like -> ItemState(liked = true)
                e.status == 409 && action == NotificationAction.Unlike -> ItemState(liked = false)
                else -> failed(action, item, replyText)
            }
        } catch (_: GhostException) {
            failed(action, item, replyText)
        }
    }

    /** A failed like or unlike leaves the button as it was, so it can be tried again. */
    private fun failed(action: NotificationAction, item: ItemNotification, replyText: String?) = ItemState(
        liked = item.liked,
        outcome = when (action) {
            NotificationAction.Like, NotificationAction.Unlike -> ActionOutcome.LikeFailed
            NotificationAction.Reply -> ActionOutcome.ReplyFailed(replyText.orEmpty())
        },
    )

    private companion object {
        const val RequestTimeoutMillis = 8_000L
    }
}
