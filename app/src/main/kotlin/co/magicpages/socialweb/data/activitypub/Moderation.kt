package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.di.ApplicationScope
import io.ktor.http.Url
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The server part of an `@user@host` handle, which is what a server block names. */
fun serverOf(handle: String): String = handle.substringAfterLast('@').lowercase()

/**
 * What the active account deleted or blocked in this session, so lists loaded before drop it at
 * once; the service leaves it out of whatever loads next. [accounts] are lower-case handles, as
 * the one name every list gives an author (feeds give a database number for its id), [servers]
 * lower-case hosts.
 */
data class Hidden(
    val posts: Set<String> = emptySet(),
    val accounts: Set<String> = emptySet(),
    val servers: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = posts.isEmpty() && accounts.isEmpty() && servers.isEmpty()

    /** Like the service, a server block covers an account seen under its handle's host or its actor's. */
    fun hides(author: Author): Boolean =
        author.handle.lowercase() in accounts || serverOf(author.handle) in servers || actorHost(author.id) in servers

    /** Whether [post] was deleted or its author is blocked. */
    fun hides(post: Post): Boolean = post.id in posts || hides(post.author)
}

/** The host of an author id that is an ActivityPub URL; null for the numbers some lists give instead. */
private fun actorHost(id: String): String? =
    if (id.startsWith("https://") || id.startsWith("http://")) runCatching { Url(id).host.lowercase() }.getOrNull() else null

/** Whether [id] is an ActivityPub id the service's block endpoints take, rather than a database number. */
private fun isActorId(id: String?): Boolean = id != null && actorHost(id) != null

/**
 * Deleting the site's own posts and blocking accounts and servers, as the active account. The
 * blocked lists, and lifting a block, also work as another signed-in account, for its settings. Each
 * call throws [GhostException] or [SocialWebException] when the service doesn't do it.
 */
@Singleton
class Moderation @Inject constructor(
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _hidden = MutableStateFlow(Hidden())
    val hidden: StateFlow<Hidden> = _hidden.asStateFlow()

    /** How the [launch]ed actions went, for the screen on top to tell. */
    enum class Outcome { Deleted, DeleteFailed, Blocked, BlockFailed, ServerBlocked, ServerBlockFailed }

    /**
     * An [outcome] waiting for a screen to tell it. [affects] is what the action hid, so a thread
     * that is about to close over it can leave telling to the screen it returns to; empty when it failed.
     */
    data class Report(val outcome: Outcome, val affects: Hidden = Hidden())

    // Held until a screen takes it: a deleted thread closes itself, and the screen it returns to tells.
    private val mailbox = MutableStateFlow<List<Report>>(emptyList())

    /**
     * What [launch]ed actions reported, each told by one collector only: the first whose [accepts]
     * takes it, even if that is one that starts later. Reports nobody accepts wait in the mailbox.
     */
    fun outcomes(accepts: (Report) -> Boolean = { true }): Flow<Outcome> = flow {
        // Taking changes the mailbox, so this runs again until nothing acceptable is left.
        mailbox.collect { take(accepts)?.let { emit(it) } }
    }

    private fun take(accepts: (Report) -> Boolean): Outcome? {
        var taken: Outcome? = null
        mailbox.update { box ->
            // The update may run again on a race: decide from scratch each time.
            val index = box.indexOfFirst(accepts)
            taken = box.getOrNull(index)?.outcome
            if (index < 0) box else box.filterIndexed { i, _ -> i != index }
        }
        return taken
    }

    /**
     * Runs a delete or block from a menu, outliving the post card that offered it (it vanishes
     * once it's done), and reports through [outcomes]. [affects] is what it hides when it works.
     */
    fun launch(action: suspend Moderation.() -> Unit, done: Outcome, failed: Outcome, affects: Hidden) {
        scope.launch {
            val report = try {
                action()
                Report(done, affects)
            } catch (_: SocialWebException) {
                Report(failed)
            } catch (_: GhostException) {
                Report(failed)
            }
            mailbox.update { it + report }
        }
    }

    init {
        // What one account blocked says nothing about another.
        accounts.activeId.onEach { _hidden.value = Hidden() }.launchIn(scope)
    }

    suspend fun delete(post: Post) = act(null, { client.deletePost(site, credential, post.id) }) {
        it.copy(posts = it.posts + post.id)
    }

    /**
     * Blocks the account [handle]. The service wants its ActivityPub id: [actorId] when it is one,
     * else it's looked up, as a feed's authors only come with a database number.
     */
    suspend fun block(handle: String, actorId: String? = null) =
        act(null, { client.block(site, credential, resolveActorId(handle, actorId)) }) {
            it.copy(accounts = it.accounts + handle.lowercase())
        }

    /** Lifts a block as the active account, or as the signed-in account [accountId]. */
    suspend fun unblock(handle: String, actorId: String? = null, accountId: String? = null) =
        act(accountId, { client.unblock(site, credential, resolveActorId(handle, actorId)) }) {
            it.copy(accounts = it.accounts - handle.lowercase())
        }

    private suspend fun StoredAccount.resolveActorId(handle: String, actorId: String?): String =
        actorId?.takeIf(::isActorId) ?: client.profile(site, credential, handle).apId

    suspend fun blockServer(host: String) = act(null, { client.blockDomain(site, credential, host) }) {
        it.copy(servers = it.servers + host.lowercase())
    }

    /** Lifts a server block as the active account, or as the signed-in account [accountId]. */
    suspend fun unblockServer(host: String, accountId: String? = null) = act(accountId, { client.unblockDomain(site, credential, host) }) {
        it.copy(servers = it.servers - host.lowercase())
    }

    suspend fun blockedAccounts(accountId: String): List<Author> = with(account(accountId)) { client.blockedAccounts(site, credential) }

    suspend fun blockedServers(accountId: String): List<String> = with(account(accountId)) { client.blockedDomains(site, credential) }

    /** The signed-in account [id], or the active one for null. */
    private suspend fun account(id: String?): StoredAccount =
        (if (id == null) accounts.account.first() else accounts.signedIn(id)) ?: throw GhostException.Unauthorized(null)

    private suspend fun act(accountId: String?, call: suspend StoredAccount.() -> Unit, change: (Hidden) -> Hidden) {
        val account = account(accountId)
        account.call()
        // Done as an account that isn't active (any more): none of its lists are on screen.
        if (accounts.activeId.first() == account.id) _hidden.update(change)
    }
}
