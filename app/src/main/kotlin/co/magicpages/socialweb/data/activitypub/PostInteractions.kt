package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.Credential
import co.magicpages.socialweb.data.account.GhostSite
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.di.ApplicationScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The like/repost/reply state of a post. */
data class Interactions(
    val likedByMe: Boolean,
    val likeCount: Int,
    val repostedByMe: Boolean,
    val repostCount: Int,
    val replyCount: Int,
) {
    fun applyTo(post: Post) = post.copy(
        likedByMe = likedByMe,
        likeCount = likeCount,
        repostedByMe = repostedByMe,
        repostCount = repostCount,
        replyCount = replyCount,
    )

    companion object {
        fun of(post: Post) = Interactions(post.likedByMe, post.likeCount, post.repostedByMe, post.repostCount, post.replyCount)
    }
}

/**
 * The one place likes, reposts and replies happen, so every screen showing a post agrees on its
 * state. Changes show at once and roll back if the server refuses; requests outlive the screen.
 */
@Singleton
class PostInteractions @Inject constructor(
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
    private val uploads: ImageUploads,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _overrides = MutableStateFlow<Map<String, Interactions>>(emptyMap())

    /** The latest known state of every post acted on in this session, by post id. */
    val overrides: StateFlow<Map<String, Interactions>> = _overrides.asStateFlow()

    /** "like:<id>" / "repost:<id>" while the request runs; repeat taps are ignored until it settles. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _failures = MutableSharedFlow<Failure>(extraBufferCapacity = 8)
    val failures: SharedFlow<Failure> = _failures.asSharedFlow()

    enum class Failure { Like, Repost }

    init {
        // Whether the active account liked or reposted a post says nothing about another account.
        accounts.activeId
            .onEach { _overrides.value = emptyMap() }
            .launchIn(scope)
    }

    fun toggleLike(post: Post) {
        val liked = !post.likedByMe
        perform("like", post, Failure.Like,
            change = { it.copy(likedByMe = liked, likeCount = (it.likeCount + if (liked) 1 else -1).coerceAtLeast(0)) },
            revert = { it.copy(likedByMe = post.likedByMe, likeCount = post.likeCount) },
        ) { site, credential ->
            if (liked) client.like(site, credential, post.id) else client.unlike(site, credential, post.id)
        }
    }

    fun toggleRepost(post: Post) {
        val reposted = !post.repostedByMe
        perform("repost", post, Failure.Repost,
            change = { it.copy(repostedByMe = reposted, repostCount = (it.repostCount + if (reposted) 1 else -1).coerceAtLeast(0)) },
            revert = { it.copy(repostedByMe = post.repostedByMe, repostCount = post.repostCount) },
        ) { site, credential ->
            if (reposted) client.repost(site, credential, post.id) else client.derepost(site, credential, post.id)
        }
    }

    /**
     * Throws [GhostException] or [SocialWebException] when the reply isn't posted, and
     * [ImageRejectedException] when its [image] can't go with it.
     */
    suspend fun reply(post: Post, text: String, image: PickedImage?) {
        val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
        // Uploaded with the same account that replies, even if another becomes active meanwhile.
        val attached = image?.let { uploads.upload(account.site, account.credential, it) }
        client.reply(account.site, account.credential, post.id, text.trim(), attached)
        if (accounts.activeId.first() != account.id) return
        _overrides.update {
            val base = it[post.id] ?: Interactions.of(post)
            it + (post.id to base.copy(replyCount = base.replyCount + 1))
        }
    }

    /** Freshly loaded posts carry the server's state; it wins over ours unless a request is still running. */
    fun reconcile(posts: Iterable<Post>) {
        _overrides.update { overrides ->
            var updated = overrides
            for (post in posts) {
                if (post.id in overrides && inFlight.none { it.endsWith(":${post.id}") }) {
                    updated = updated + (post.id to Interactions.of(post))
                }
            }
            updated
        }
    }

    private fun perform(
        kind: String,
        post: Post,
        failure: Failure,
        change: (Interactions) -> Interactions,
        revert: (Interactions) -> Interactions,
        call: suspend (GhostSite, Credential) -> Unit,
    ) {
        val key = "$kind:${post.id}"
        if (!inFlight.add(key)) return
        _overrides.update { it + (post.id to change(it[post.id] ?: Interactions.of(post))) }
        scope.launch {
            var owner: String? = null
            val ok = try {
                val account = accounts.account.first() ?: return@launch
                owner = account.id
                call(account.site, account.credential)
                true
            } catch (e: SocialWebException) {
                e.status == 409 // already in the requested state on the server
            } catch (_: GhostException) {
                false
            } finally {
                inFlight.remove(key)
            }
            // A failure of the account that was active when it was tapped is of no interest to another one.
            if (!ok && accounts.activeId.first() == owner) {
                _overrides.update { it + (post.id to revert(it[post.id] ?: Interactions.of(post))) }
                _failures.emit(failure)
            }
        }
    }
}
