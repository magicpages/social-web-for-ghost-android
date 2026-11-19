package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The unread notification count of the active account, behind the navigation badge. */
@Singleton
class UnreadNotifications @Inject constructor(
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
    @ApplicationScope scope: CoroutineScope,
) {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    init {
        // Another account's count must never show for this one.
        scope.launch { accounts.activeId.drop(1).collect { _count.value = 0 } }
    }

    /** Asks the service again; keeps the last count when it can't. */
    suspend fun refresh() {
        val account = accounts.account.first() ?: return
        try {
            val count = client.unreadNotificationCount(account.site, account.credential)
            // The answer is for the account asked; it is dropped when another one became active meanwhile.
            if (accounts.activeId.first() == account.id) _count.value = count
        } catch (_: GhostException) {
        } catch (_: SocialWebException) {
        }
    }

    /** Everything up to now has been seen. The badge clears at once; a failed reset just shows it again later. */
    suspend fun markRead() {
        _count.value = 0
        val account = accounts.account.first() ?: return
        try {
            client.markNotificationsRead(account.site, account.credential)
        } catch (_: GhostException) {
        } catch (_: SocialWebException) {
        }
    }
}
