package co.magicpages.socialweb.ui.reach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.reach.ActivityHistory
import co.magicpages.socialweb.data.reach.DayMillis
import co.magicpages.socialweb.data.reach.PostActivity
import co.magicpages.socialweb.data.reach.activityByPost
import co.magicpages.socialweb.data.reach.dailyCounts
import co.magicpages.socialweb.data.reach.firstDayPercent
import co.magicpages.socialweb.data.reach.potentialReach
import co.magicpages.socialweb.data.reach.timestampMillis
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** How one post travelled. */
data class PostReach(
    val post: Post,
    val activity: PostActivity,
    /** Interactions per day from the day it came out; empty without a publishing date. */
    val days: IntArray,
    /** The share of interactions in its first day, in percent. */
    val firstDayPercent: Int?,
    /** The site's own follower count, once known. */
    val ownFollowers: Int? = null,
    /** Follower counts of the accounts that reposted it, by handle, as they come in. */
    val reposterFollowers: Map<String, Int> = emptyMap(),
    /** When the notifications the lists come from begin, if they don't reach back to the post. */
    val listedSince: Long?,
) {
    /** Up to how many people it could have reached; null until the site's followers are known. */
    val potentialReach: Long? get() = ownFollowers?.let { potentialReach(it, reposterFollowers.values) }

    // IntArray compares by identity; the state flow would then emit every update twice over.
    override fun equals(other: Any?): Boolean = other is PostReach && post == other.post && activity == other.activity &&
        days.contentEquals(other.days) && firstDayPercent == other.firstDayPercent && ownFollowers == other.ownFollowers &&
        reposterFollowers == other.reposterFollowers && listedSince == other.listedSince

    override fun hashCode(): Int = post.hashCode() * 31 + reposterFollowers.hashCode()
}

sealed interface PostReachUiState {
    data object Loading : PostReachUiState
    data class Failed(val error: Throwable) : PostReachUiState
    data class Loaded(val reach: PostReach) : PostReachUiState
}

/** "How your post travelled" for the site's post [postId]. */
@HiltViewModel(assistedFactory = PostReachViewModel.Factory::class)
class PostReachViewModel @AssistedInject constructor(
    @Assisted private val postId: String,
    private val accounts: AccountStore,
    private val activityPub: ActivityPubClient,
    private val history: ActivityHistory,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(postId: String): PostReachViewModel
    }

    private val _state = MutableStateFlow<PostReachUiState>(PostReachUiState.Loading)
    val state: StateFlow<PostReachUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        if (_state.value !is PostReachUiState.Loaded) _state.value = PostReachUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
                val post = activityPub.post(account.site, account.credential, postId)
                val now = System.currentTimeMillis()
                val published = timestampMillis(post.publishedAt)
                // Without a date, a month of notifications is as far as it's worth going.
                val window = history.since(account, published ?: (now - DefaultDays * DayMillis))
                val activity = activityByPost(window.notifications)[post.id] ?: PostActivity.empty(post.id)
                val times = activity.all.mapNotNull { it.atMillis }
                val reach = PostReach(
                    post = post,
                    activity = activity,
                    days = published?.let {
                        val elapsed = ((now - it) / DayMillis).toInt() + 1
                        dailyCounts(times, it, elapsed.coerceIn(1, TimelineDays))
                    } ?: IntArray(0),
                    firstDayPercent = published?.let { firstDayPercent(times, it) },
                    listedSince = if (published != null && window.covers(published)) null else window.oldestMillis,
                )
                _state.value = PostReachUiState.Loaded(reach)
                loadFollowers(account, activity)
            } catch (e: GhostException) {
                if (_state.value !is PostReachUiState.Loaded) _state.value = PostReachUiState.Failed(e)
            } catch (e: SocialWebException) {
                if (_state.value !is PostReachUiState.Loaded) _state.value = PostReachUiState.Failed(e)
            }
        }
    }

    /**
     * Follower counts for the reach estimate: the site's own, then each reposter's, a few at a time.
     * One that won't load is left out of the estimate rather than failing the screen.
     */
    private suspend fun loadFollowers(account: StoredAccount, activity: PostActivity) = coroutineScope {
        launch {
            followersOf(account, "me")?.let { followers -> edit { it.copy(ownFollowers = followers) } }
        }
        val permits = Semaphore(ProfileConcurrency)
        activity.reposts.take(MaxReposterLookups).map { repost ->
            async {
                val handle = repost.author.handle
                permits.withPermit { followersOf(account, handle) }
                    ?.let { followers -> edit { it.copy(reposterFollowers = it.reposterFollowers + (handle to followers)) } }
            }
        }.awaitAll()
    }

    private suspend fun followersOf(account: StoredAccount, handle: String): Int? = try {
        activityPub.profile(account.site, account.credential, handle).followerCount
    } catch (e: GhostException) {
        null
    } catch (e: SocialWebException) {
        null
    }

    private fun edit(change: (PostReach) -> PostReach) {
        _state.update { state -> if (state is PostReachUiState.Loaded) PostReachUiState.Loaded(change(state.reach)) else state }
    }

    private companion object {
        const val DefaultDays = 30
        const val TimelineDays = 14
        const val ProfileConcurrency = 4
        const val MaxReposterLookups = 40
    }
}
