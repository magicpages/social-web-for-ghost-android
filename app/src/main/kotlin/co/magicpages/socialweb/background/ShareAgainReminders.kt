package co.magicpages.socialweb.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.magicpages.socialweb.data.account.AccountStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the reminder to share one of the user's own articles again. WorkManager keeps it across
 * restarts and reboots; it needs no network, as the reminder carries what it shows.
 */
@Singleton
class ShareAgainReminders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val announcer: NotificationAnnouncer,
) {
    /**
     * Sets the reminder for [reminder]'s article to go off [delay] from [now], replacing one set
     * before for the same article. Returns when it goes off.
     */
    fun schedule(reminder: ShareAgainReminder, delay: ShareAgainDelay, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val at = delay.triggerAt(now)
        // Without a channel the notification has nowhere to go, and Android 13 only asks for the permission once there is one.
        announcer.ensureChannel()
        val request = OneTimeWorkRequestBuilder<ShareAgainWorker>()
            .setInitialDelay(Duration.between(now, at))
            .setInputData(reminder.toData())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(reminder.accountId, reminder.postId), ExistingWorkPolicy.REPLACE, request)
        return at
    }

    companion object {
        /** The unique work name of one article's reminder; `adb shell dumpsys jobscheduler` shows it among WorkManager's jobs. */
        fun workName(accountId: String, postId: String) = "share_again:$accountId:$postId"
    }
}

/** Posts the notification that asks the user whether to share an article again; tapping it opens a note about it. */
@HiltWorker
class ShareAgainWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accounts: AccountStore,
    private val announcer: NotificationAnnouncer,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val reminder = ShareAgainReminder.fromData(inputData) ?: return Result.failure()
        // An account that was signed out meanwhile has nothing to share from.
        val account = accounts.signedIn(reminder.accountId) ?: return Result.success()
        announcer.announceShareAgain(account, reminder)
        return Result.success()
    }
}
