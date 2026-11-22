package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Each signed-in account's "show sensitive media" preference, which Ghost Admin shares. Read from
 * the service when the app first sees the account and kept in memory while it runs; a post's
 * sensitive media is shown without a tap only when the service has said so, so it fails closed.
 */
@Singleton
class SensitiveMediaPreference @Inject constructor(
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
    @ApplicationScope scope: CoroutineScope,
) {
    /** What each account's service answered, by account id. A failed read isn't remembered. */
    private val known = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Saves go one at a time, so the last tap is the one that stays. */
    private val writeLock = Mutex()

    /** The active account's preference; null until the service has answered. */
    val preference: StateFlow<Boolean?> = combine(accounts.account, known) { account, values ->
        account?.let { values[it.id] }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // The session starts, or another account becomes active: ask for what isn't known yet.
        scope.launch {
            accounts.activeId.filterNotNull().collectLatest {
                val account = accounts.account.first() ?: return@collectLatest
                if (account.id !in known.value) load(account)
            }
        }
    }

    /** The preference of the account [accountId]; null until its service has answered. */
    fun preferenceOf(accountId: String): Flow<Boolean?> = known.map { it[accountId] }.distinctUntilChanged()

    /** Asks the service again for the preference of [accountId]; the value in use stays if that fails. */
    suspend fun refresh(accountId: String) {
        load(accounts.signedIn(accountId) ?: return)
    }

    private suspend fun load(account: StoredAccount) {
        val show = try {
            client.preferences(account.site, account.credential).showSensitiveMedia
        } catch (_: GhostException) {
            return
        } catch (_: SocialWebException) {
            return
        }
        // A save under way knows better than a read that started before it.
        if (!writeLock.isLocked) known.update { it + (account.id to show) }
    }

    /**
     * Changes the preference of [accountId] at once and saves it; when that fails the
     * previous value comes back and the failure is thrown ([GhostException] or [SocialWebException]).
     */
    suspend fun set(accountId: String, show: Boolean) {
        val account = accounts.signedIn(accountId) ?: throw GhostException.Unauthorized(null)
        val before = known.value[account.id]
        known.update { it + (account.id to show) }
        // Leaving the screen mid-save must not drop the change.
        withContext(NonCancellable) {
            try {
                val stored = writeLock.withLock { client.setShowSensitiveMedia(account.site, account.credential, show) }
                known.update { it + (account.id to stored.showSensitiveMedia) }
            } catch (e: Exception) {
                // Only undo our own change: a later tap has replaced it already.
                known.update { values ->
                    if (values[account.id] != show) {
                        values
                    } else if (before != null) {
                        values + (account.id to before)
                    } else {
                        values - account.id
                    }
                }
                throw e
            }
        }
    }
}

/**
 * Bluesky sharing for a signed-in account, through Bridgy Fed. Every call throws [GhostException] or
 * [SocialWebException] when the service doesn't do it. Only Owners and Administrators may use it.
 */
@Singleton
class BlueskySharing @Inject constructor(
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
) {
    /** What the site's account says about Bluesky now, with what enabling needs to know of it. */
    data class State(val status: BlueskyStatus, val name: String, val handle: String, val avatarUrl: String?)

    suspend fun state(accountId: String): State = with(account(accountId)) {
        val account = client.currentAccount(site, credential)
        State(
            BlueskyStatus(
                enabled = account.blueskyEnabled,
                handleConfirmed = account.blueskyHandleConfirmed,
                handle = account.blueskyHandle,
            ),
            account.name.ifBlank { account.handle },
            account.handle,
            account.avatarUrl,
        )
    }

    suspend fun enable(accountId: String): BlueskyStatus = with(account(accountId)) { client.enableBluesky(site, credential) }

    /** Whether Bridgy Fed has made the Bluesky account yet; the handle comes with the first yes. */
    suspend fun confirmHandle(accountId: String): BlueskyStatus = with(account(accountId)) { client.confirmBlueskyHandle(site, credential) }

    suspend fun disable(accountId: String) = with(account(accountId)) { client.disableBluesky(site, credential) }

    private suspend fun account(id: String): StoredAccount = accounts.signedIn(id) ?: throw GhostException.Unauthorized(null)
}
