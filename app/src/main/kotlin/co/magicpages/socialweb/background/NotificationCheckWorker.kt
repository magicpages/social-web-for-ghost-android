package co.magicpages.socialweb.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.settings.AppSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Looks at the newest notifications of every signed-in account and announces the ones that came in
 * since the last check of that account. Only reads: the service's unread count is left alone, so
 * the in-app badge stays accurate.
 */
@HiltWorker
class NotificationCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
    private val prefs: NotificationPrefs,
    private val announcer: NotificationAnnouncer,
    private val settings: AppSettings,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var retry = false
        for (account in accounts.accounts.first()) {
            if (check(account) == Outcome.Retry) retry = true
        }
        // Accounts that were checked have moved their markers on, so a retry can't announce anything twice.
        return if (retry) Result.retry() else Result.success()
    }

    private enum class Outcome { Done, Retry }

    private suspend fun check(account: StoredAccount): Outcome {
        val page = try {
            client.notifications(account.site, account.credential)
        } catch (_: GhostException.Network) {
            return Outcome.Retry
        } catch (_: GhostException) {
            // A rejected session is for the app to report when it is opened; retrying won't fix it.
            return Outcome.Done
        } catch (e: SocialWebException) {
            return if (e.status >= 500) Outcome.Retry else Outcome.Done
        }

        // The filter in the settings is the same for every account.
        val outcome = prefs.check(account, page.notifications, settings.announcedTypes.value)
        // In the foreground the list in the app shows them; they must not be announced later either.
        if (outcome.announce.isNotEmpty() && !isAppInForeground()) announcer.announce(account, outcome.announce)
        return Outcome.Done
    }

    private suspend fun isAppInForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}
