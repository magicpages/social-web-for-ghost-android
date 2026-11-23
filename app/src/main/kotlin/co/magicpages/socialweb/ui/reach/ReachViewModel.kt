package co.magicpages.socialweb.ui.reach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.NotificationPost
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.feed.htmlToPlainText
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.reach.ActivityHistory
import co.magicpages.socialweb.data.reach.FollowerGrowth
import co.magicpages.socialweb.data.reach.followerGrowth
import co.magicpages.socialweb.data.reach.DayMillis
import co.magicpages.socialweb.data.reach.PostActivity
import co.magicpages.socialweb.data.reach.activityByPost
import co.magicpages.socialweb.data.reach.timestampMillis
import co.magicpages.socialweb.data.activitypub.serverOf
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One of the site's own posts as a reach list shows it. */
data class ReachPost(
    val id: String,
    /** The article's title, or the note's text. */
    val text: String,
    val isArticle: Boolean,
    val publishedMillis: Long?,
    val reposts: Int,
    val replies: Int,
    val likes: Int,
)

/** The last [ReachViewModel.WindowDays] days of the site's notifications, added up. */
data class ReachSummary(
    val reposts: Int,
    val replies: Int,
    val likes: Int,
    val people: Int,
    val servers: Int,
    /** Whether the notifications ran out before the window did, on a very busy site. */
    val partial: Boolean,
)

sealed interface ReachUiState {
    data object Loading : ReachUiState
    data class Failed(val error: Throwable) : ReachUiState
    data class Loaded(
        val summary: ReachSummary,
        /** New followers over the same window, from the follow notifications. */
        val growth: FollowerGrowth,
        /** The site's follower count; null when the profile couldn't be read. */
        val followers: Int?,
        /** The posts that travelled furthest in the window, old ones included. */
        val top: List<ReachPost>,
        /** The site's newest posts, newest first, without its reposts of others. */
        val recent: List<ReachPost>,
    ) : ReachUiState
}

/** The Reach overview: how far the site's posts went lately, and its posts to look into one by one. */
@HiltViewModel
class ReachViewModel @Inject constructor(
    private val accounts: AccountStore,
    private val activityPub: ActivityPubClient,
    private val history: ActivityHistory,
) : ViewModel() {
    private val _state = MutableStateFlow<ReachUiState>(ReachUiState.Loading)
    val state: StateFlow<ReachUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        if (_state.value !is ReachUiState.Loaded) _state.value = ReachUiState.Loading
        loadJob = viewModelScope.launch {
            val result = try {
                val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
                val now = System.currentTimeMillis()
                val since = now - WindowDays * DayMillis
                coroutineScope {
                    val window = async { history.since(account, since) }
                    val posts = async {
                        val first = activityPub.postsBy(account.site, account.credential, "me")
                        val more = first.next?.let { activityPub.postsBy(account.site, account.credential, "me", it).posts }.orEmpty()
                        first.posts + more
                    }
                    // The count is a nice-to-have next to the new followers; the screen works without it.
                    val followers = async {
                        try {
                            activityPub.profile(account.site, account.credential, "me").followerCount
                        } catch (_: GhostException) {
                            null
                        } catch (_: SocialWebException) {
                            null
                        }
                    }
                    withContext(Dispatchers.Default) {
                        val notifications = window.await().notifications
                        val recentNotifications = notifications.filter { (timestampMillis(it.createdAt) ?: 0) >= since }
                        val activity = activityByPost(recentNotifications).values
                        ReachUiState.Loaded(
                            summary = activity.summary(partial = !window.await().covers(since)),
                            growth = followerGrowth(notifications, now),
                            followers = followers.await(),
                            top = activity.filter { it.score > 0 && it.post != null }
                                .sortedByDescending { it.score }
                                .take(TopCount)
                                .map { it.post!!.toReachPost() },
                            recent = posts.await()
                                .filter { it.authoredByMe && it.repostedBy == null && !it.isDeleted }
                                .distinctBy { it.id }
                                .map { it.toReachPost() },
                        )
                    }
                }
            } catch (e: GhostException) {
                ReachUiState.Failed(e)
            } catch (e: SocialWebException) {
                ReachUiState.Failed(e)
            }
            if (result is ReachUiState.Loaded || _state.value !is ReachUiState.Loaded) _state.value = result
        }
    }

    companion object {
        const val WindowDays = 30
        private const val TopCount = 3
    }
}

private fun Collection<PostActivity>.summary(partial: Boolean): ReachSummary {
    val everyone = flatMap { it.all }
    return ReachSummary(
        reposts = sumOf { it.reposts.size },
        replies = sumOf { it.replies.size },
        likes = sumOf { it.likes.size },
        people = everyone.mapTo(HashSet()) { it.author.handle }.size,
        servers = everyone.mapTo(HashSet()) { serverOf(it.author.handle) }.size,
        partial = partial,
    )
}

internal fun Post.toReachPost() = ReachPost(
    id = id,
    text = title.ifBlank { htmlToPlainText(content) },
    isArticle = isArticle,
    publishedMillis = timestampMillis(publishedAt),
    reposts = repostCount,
    replies = replyCount,
    likes = likeCount,
)

// Notifications don't carry the publishing date.
private fun NotificationPost.toReachPost() = ReachPost(
    id = id,
    text = title.ifBlank { htmlToPlainText(content) },
    isArticle = isArticle,
    publishedMillis = null,
    reposts = repostCount,
    replies = replyCount,
    likes = likeCount,
)
