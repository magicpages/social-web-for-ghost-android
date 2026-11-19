package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The newest avatar address known per account. The service keeps the avatars of timeline authors
 * for as long as it likes (it misses Update activities), while a profile lookup always answers
 * with current data, so every profile that [ActivityPubClient] loads is written down here.
 *
 * Accounts are known by the service's id, the ActivityPub id and the handle, whichever a post or
 * notification happens to carry.
 */
@Singleton
class FreshAvatars @Inject constructor() {
    private val lock = Any()
    private val fresh = HashMap<String, String>()

    // Timeline avatars by the address the service sent, so an image that fails to load can be
    // traced back to its account. Old entries drop out first.
    private val owners = object : LinkedHashMap<String, Author>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Author>) = size > MAX_TRACKED
    }

    fun record(profile: Profile) = put(profile.avatarUrl, profile.id, profile.apId, profile.handle)

    fun record(account: Account) = put(account.avatarUrl, account.id, null, account.handle)

    /** The current avatar address of [author], or null when no profile of it has been loaded. */
    fun freshUrl(author: Author): String? = synchronized(lock) {
        fresh[idKey(author.id)] ?: fresh[handleKey(author.handle)]
    }

    /** Remembers whose avatar each of [posts] (and their reposters) show, for [replacementFor]. */
    fun track(posts: Collection<Post>) {
        synchronized(lock) {
            for (post in posts) {
                track(post.author)
                post.repostedBy?.let { track(it) }
            }
        }
    }

    /** The current avatar of whoever owns [url], when it differs from [url]. */
    fun replacementFor(url: String): String? = synchronized(lock) {
        val owner = owners[url] ?: return null
        freshUrl(owner)?.takeIf { it != url }
    }

    /** Who showed [url] as their avatar in something [track]ed. */
    fun ownerOf(url: String): Author? = synchronized(lock) { owners[url] }

    // Callers hold the lock.
    private fun track(author: Author) {
        val url = author.avatarUrl
        if (!url.isNullOrBlank()) owners[url] = author
    }

    private fun put(avatarUrl: String?, id: String, apId: String?, handle: String) {
        // A profile without a picture says nothing about the one the timeline shows.
        val url = avatarUrl?.takeIf { it.isNotBlank() } ?: return
        synchronized(lock) {
            fresh[idKey(id)] = url
            if (!apId.isNullOrBlank()) fresh[idKey(apId)] = url
            if (handle.isNotBlank()) fresh[handleKey(handle)] = url
        }
    }

    private fun idKey(id: String) = "id:$id"

    private fun handleKey(handle: String) = "handle:${handle.trim().lowercase()}"

    private companion object {
        const val MAX_TRACKED = 2_000
    }
}

/**
 * Finds the current avatar of an account whose timeline avatar won't load. Each account is looked
 * up at most once per session however many images ask, and lookups run one after another with
 * [spacingMillis] between them so a screen of stale avatars doesn't hit the service all at once.
 */
@Singleton
class AvatarRefresher internal constructor(
    private val avatars: FreshAvatars,
    private val scope: CoroutineScope,
    private val spacingMillis: Long,
    /** Loads the profile of a handle; [ActivityPubClient.profile] records what it finds in [avatars]. */
    private val fetchProfile: suspend (handle: String) -> Unit,
) {
    @Inject
    constructor(
        avatars: FreshAvatars,
        client: ActivityPubClient,
        accounts: AccountStore,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        avatars,
        scope,
        LOOKUP_SPACING_MILLIS,
        { handle -> accounts.account.first()?.let { client.profile(it.site, it.credential, handle) } },
    )

    private val lock = Any()
    private val attempted = HashSet<String>()
    private val pending = HashMap<String, Deferred<Unit>>()
    private val gate = Mutex()

    /**
     * The current avatar for [failedUrl], which didn't load: known already, or found by looking
     * the owner's profile up. Null when nothing newer is known, including for addresses that
     * weren't [FreshAvatars.track]ed and for accounts already looked up this session.
     */
    suspend fun replacementFor(failedUrl: String): String? {
        avatars.replacementFor(failedUrl)?.let { return it }
        val owner = avatars.ownerOf(failedUrl) ?: return null
        if (owner.handle.isBlank()) return null
        val key = owner.handle.trim().lowercase()
        val lookup = synchronized(lock) {
            pending[key]
                // Lazy, so the lookup can't finish (and remove itself) before it is in the map.
                ?: if (attempted.add(key)) {
                    scope.async(start = CoroutineStart.LAZY) { lookUp(key, owner.handle) }.also { pending[key] = it }
                } else {
                    null
                }
        }
        // The lookup belongs to the app: an image that scrolled away doesn't cancel it for the others.
        lookup?.await()
        return avatars.replacementFor(failedUrl)
    }

    private suspend fun lookUp(key: String, handle: String) {
        try {
            gate.withLock {
                try {
                    fetchProfile(handle)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SocialWebException) {
                    // Gone or unreachable: the initial stays, and the account isn't asked for again.
                } catch (_: GhostException) {
                }
                delay(spacingMillis)
            }
        } finally {
            synchronized(lock) { pending.remove(key) }
        }
    }

    private companion object {
        const val LOOKUP_SPACING_MILLIS = 1_000L
    }
}
