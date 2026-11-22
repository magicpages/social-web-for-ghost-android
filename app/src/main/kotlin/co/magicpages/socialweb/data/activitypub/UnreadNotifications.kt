package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Unread notification counts: the active account's behind the navigation badge, and every other
 * signed-in account's for the account menu. Only reads; the counts are reset by opening the list.
 */
@Singleton
class UnreadNotifications @Inject constructor(
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
    @ApplicationScope scope: CoroutineScope,
) {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    private val _others = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** The other signed-in accounts' counts, by account id; an account is missing until it was asked. */
    val others: StateFlow<Map<String, Int>> = _others.asStateFlow()

    init {
        // Another account's count must never show for this one; its own last known count may.
        scope.launch {
            accounts.activeId.drop(1).collect { id ->
                _count.value = id?.let { _others.value[it] } ?: 0
                _others.update { if (id == null) emptyMap() else it - id }
            }
        }
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

    /** Asks the service for every other account's count at once; an account that can't be asked keeps its last one. */
    suspend fun refreshOthers() {
        val activeId = accounts.activeId.first()
        val others = accounts.accounts.first().filter { it.id != activeId }
        val counts = coroutineScope {
            others.map { account ->
                async {
                    val count = try {
                        client.unreadNotificationCount(account.site, account.credential)
                    } catch (_: GhostException) {
                        null
                    } catch (_: SocialWebException) {
                        null
                    }
                    account.id to count
                }
            }.awaitAll()
        }
        // The account that became active meanwhile shows its count on the badge instead.
        val current = accounts.activeId.first()
        _others.update { previous ->
            buildMap { for ((id, count) in counts) if (id != current) (count ?: previous[id])?.let { put(id, it) } }
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
