package co.magicpages.socialweb.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the periodic notification check scheduled exactly while at least one account is signed
 * in, and cleans up after each account that is signed out.
 */
@Singleton
class NotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accounts: AccountStore,
    private val prefs: NotificationPrefs,
    private val announcer: NotificationAnnouncer,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Call once from `Application.onCreate`; follows sign-in and sign-out from then on. */
    fun start() {
        scope.launch {
            var known = emptyList<StoredAccount>()
            var scheduled: Boolean? = null
            accounts.accounts.collect { current ->
                val ids = current.mapTo(HashSet()) { it.id }
                for (gone in known) {
                    if (gone.id in ids) continue
                    // What was seen and announced for an account that left has nothing to open.
                    prefs.clearLastSeenId(gone)
                    announcer.cancel(gone.id)
                }
                known = current

                val any = current.isNotEmpty()
                if (any != scheduled) {
                    scheduled = any
                    if (any) schedule() else cancel()
                }
            }
        }
    }

    private fun schedule() {
        announcer.ensureChannel()
        val request = PeriodicWorkRequestBuilder<NotificationCheckWorker>(RepeatMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // KEEP: restarting the app must not push the next check further out.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WorkName, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WorkName)
    }

    private companion object {
        const val WorkName = "notification_check"
        const val RepeatMinutes = 15L
    }
}
