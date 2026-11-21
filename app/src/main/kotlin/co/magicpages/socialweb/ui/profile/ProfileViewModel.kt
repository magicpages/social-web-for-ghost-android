package co.magicpages.socialweb.ui.profile

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.paging.filter
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.Hidden
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.PostInteractions
import co.magicpages.socialweb.data.activitypub.PostsPage
import co.magicpages.socialweb.data.activitypub.Profile
import co.magicpages.socialweb.data.activitypub.RemoteObjects
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.activitypub.Moderation
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.ghost.GhostSiteClient
import co.magicpages.socialweb.data.ghost.OwnRecommendations
import co.magicpages.socialweb.data.ghost.Recommendation
import co.magicpages.socialweb.data.ghost.ghostSiteUrl
import co.magicpages.socialweb.data.ghost.recommends
import co.magicpages.socialweb.data.member.MemberClient
import co.magicpages.socialweb.data.member.MemberSessions
import co.magicpages.socialweb.ui.components.bioHtml
import co.magicpages.socialweb.ui.components.parsePostHtml
import co.magicpages.socialweb.ui.posts.FeedItem
import co.magicpages.socialweb.ui.posts.LinkedPosts
import co.magicpages.socialweb.ui.posts.PostsPagingSource
import co.magicpages.socialweb.ui.posts.ReplyComposer
import co.magicpages.socialweb.ui.posts.postMessages
import co.magicpages.socialweb.ui.moderation.BlockTarget
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProfileTab { Posts, Likes, Replies, Recommendations }

@Immutable
data class ProfileContent(
    val profile: Profile,
    val bio: AnnotatedString,
    val fields: List<Pair<String, AnnotatedString>>,
    /** The site's own account: no follow button, and a Likes tab. */
    val isMe: Boolean,
    /** In display order; a single tab shows as a plain heading. */
    val tabs: List<ProfileTab>,
)

/** A Ghost profile's site, as far as it is known; [recommendations] fill in as each site's details arrive. */
@Immutable
data class ProfileSite(
    val url: String,
    val title: String? = null,
    /** Anyone can sign up to the site's newsletter. */
    val acceptsMembers: Boolean = false,
    /** The in-app browser is signed in to the site as a member. */
    val subscribed: Boolean = false,
    /** The signed-in site recommends this one; null while that isn't known. */
    val recommended: Boolean? = null,
    val recommendBusy: Boolean = false,
    val recommendations: List<SiteRecommendation> = emptyList(),
    val recommendationsStatus: RecommendationsStatus = RecommendationsStatus.Loading,
)

/** One of a site's recommendations; [details] is null until the site has been looked at. */
@Immutable
data class SiteRecommendation(val url: String, val details: Recommendation?)

enum class RecommendationsStatus { Loading, Loaded, Failed }

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class Failed(val error: Throwable) : ProfileUiState
    data class Loaded(val content: ProfileContent) : ProfileUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ProfileViewModel.Factory::class)
class ProfileViewModel @AssistedInject constructor(
    /** `@user@domain`, or `me`. */
    @Assisted private val handle: String,
    private val accounts: AccountStore,
    private val activityPub: ActivityPubClient,
    private val interactions: PostInteractions,
    private val remote: RemoteObjects,
    private val linked: LinkedPosts,
    private val ghostSites: GhostSiteClient,
    private val ownRecommendations: OwnRecommendations,
    private val members: MemberClient,
    private val memberSessions: MemberSessions,
    private val moderation: Moderation,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(handle: String): ProfileViewModel
    }

    private val _state = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private val _tab = MutableStateFlow(ProfileTab.Posts)
    val tab: StateFlow<ProfileTab> = _tab.asStateFlow()

    /**
     * Who the service is asked about: [handle], until the signed-in account has been edited. Its handle may
     * have changed then, so what [handle] spells no longer finds it, and `me` always does.
     */
    private var target = handle

    // Another server's outbox comes through the service with like counts fixed at 0, so it must not
    // overwrite what the rest of the app knows about those posts.
    private val postsPages = pages(authoritative = handle == "me") { account, next ->
        activityPub.postsBy(account.site, account.credential, target, next)
    }
    private val likesPages = pages { account, next -> activityPub.likedPosts(account.site, account.credential, next) }
    private val repliesPages = Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
        RepliesPagingSource(
            remote = remote,
            activityPub = activityPub,
            linked = linked,
            account = { accounts.account.first() ?: throw GhostException.Unauthorized(null) },
            actorId = { _state.filterIsInstance<ProfileUiState.Loaded>().first().content.profile.apId },
        )
    }.flow.cachedIn(viewModelScope)

    /** The profile's handle as the service writes it, which a link's spelling needn't match until it has loaded. */
    private val ownerHandle: Flow<String> = _state
        .map { (it as? ProfileUiState.Loaded)?.content?.profile?.handle ?: handle }
        .distinctUntilChanged()

    /** The posts of the selected tab. */
    val posts: Flow<PagingData<FeedItem>> = combine(
        _tab.flatMapLatest {
            when (it) {
                ProfileTab.Posts -> postsPages
                ProfileTab.Likes -> likesPages
                ProfileTab.Replies -> repliesPages
                ProfileTab.Recommendations -> flowOf(PagingData.empty<FeedItem>())
            }
        },
        interactions.overrides,
        moderation.hidden,
        ownerHandle,
    ) { data, overrides, hidden, owner ->
        val withOverrides = if (overrides.isEmpty()) data else data.map { it.with(overrides) }
        if (hidden.isEmpty) withOverrides else withOverrides.filter { !it.isHiddenOnProfile(hidden, owner) }
    }

    val composer = ReplyComposer(viewModelScope, interactions)
    private val followFailed = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private val siteMessages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val messages: Flow<Int> = merge(postMessages(interactions, composer, moderation), followFailed, siteMessages)

    private val _site = MutableStateFlow<ProfileSite?>(null)
    private val recommendBusy = MutableStateFlow(false)

    /** The profile's Ghost site: its members, recommendations and the signed-in site's recommendation of it. Null for other profiles. */
    val site: StateFlow<ProfileSite?> = combine(_site, ownRecommendations.list, recommendBusy) { site, own, busy ->
        site?.copy(recommended = own?.recommends(site.url), recommendBusy = busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var loadJob: Job? = null
    private var followJob: Job? = null
    private var blockJob: Job? = null
    private var siteJob: Job? = null
    private var subscribedJob: Job? = null

    init {
        load()
        // Closing the in-app browser is when a membership may have started or ended.
        memberSessions.changes.drop(1).onEach {
            val siteUrl = _site.value?.url ?: return@onEach
            subscribedJob?.cancel()
            subscribedJob = viewModelScope.launch { updateSubscribed(siteUrl) }
        }.launchIn(viewModelScope)
    }

    fun select(tab: ProfileTab) {
        _tab.value = tab
    }

    /** The site's own profile was edited: shows what it says now. The post list refreshes through the UI. */
    fun onEdited() {
        target = "me"
        load()
    }

    fun toggleLike(post: Post) = interactions.toggleLike(post)
    fun toggleRepost(post: Post) = interactions.toggleRepost(post)

    /** Reloads the profile; the post list refreshes itself through the UI. */
    fun load() {
        loadJob?.cancel()
        if (_state.value !is ProfileUiState.Loaded) _state.value = ProfileUiState.Loading
        loadJob = viewModelScope.launch {
            val result = try {
                val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
                val profile = activityPub.profile(account.site, account.credential, target)
                val isMe = target == "me" || profile.handle == account.profile.handle
                ProfileUiState.Loaded(profile.toContent(isMe))
            } catch (e: GhostException) {
                ProfileUiState.Failed(e)
            } catch (e: SocialWebException) {
                ProfileUiState.Failed(e)
            }
            // A failed refresh keeps what's already on screen.
            if (result is ProfileUiState.Loaded || _state.value !is ProfileUiState.Loaded) _state.value = result
            (result as? ProfileUiState.Loaded)?.let { loadSite(it.content) }
        }
    }

    /** Follows or unfollows at once, rolling back if the server refuses. */
    fun toggleFollow() {
        val loaded = _state.value as? ProfileUiState.Loaded ?: return
        if (followJob?.isActive == true || loaded.content.isMe) return
        val before = loaded.content.profile
        val following = !before.followedByMe
        setProfile(
            before.copy(
                followedByMe = following,
                followerCount = (before.followerCount + if (following) 1 else -1).coerceAtLeast(0),
            ),
        )
        followJob = viewModelScope.launch {
            val ok = try {
                val account = accounts.account.first() ?: return@launch
                if (following) activityPub.follow(account.site, account.credential, before.handle)
                else activityPub.unfollow(account.site, account.credential, before.handle)
                true
            } catch (e: SocialWebException) {
                e.status == 409 // already in the requested state on the server
            } catch (_: GhostException) {
                false
            }
            if (!ok) {
                setProfile(before)
                followFailed.emit(R.string.follow_failed)
            }
        }
    }

    /**
     * Blocks or unblocks the profile's account, or with a [BlockTarget.Server] target its whole
     * server, showing the new state once the service did it. A block ends follows both ways.
     */
    fun setBlocked(target: BlockTarget, blocked: Boolean) {
        val loaded = _state.value as? ProfileUiState.Loaded ?: return
        if (blockJob?.isActive == true || loaded.content.isMe) return
        val profile = loaded.content.profile
        val actorId = profile.apId.ifBlank { profile.id }
        blockJob = viewModelScope.launch {
            val ok = try {
                when (target) {
                    is BlockTarget.Account ->
                        if (blocked) moderation.block(profile.handle, actorId) else moderation.unblock(profile.handle, actorId)
                    is BlockTarget.Server -> if (blocked) moderation.blockServer(target.host) else moderation.unblockServer(target.host)
                }
                true
            } catch (_: SocialWebException) {
                false
            } catch (_: GhostException) {
                false
            }
            if (!ok) {
                followFailed.emit(
                    when {
                        !blocked -> R.string.unblock_failed
                        target is BlockTarget.Server -> R.string.server_block_failed
                        else -> R.string.block_failed
                    },
                )
                return@launch
            }
            val current = (_state.value as? ProfileUiState.Loaded)?.content?.profile ?: return@launch
            val following = current.followedByMe && !blocked
            setProfile(
                when (target) {
                    is BlockTarget.Account -> current.copy(blockedByMe = blocked)
                    is BlockTarget.Server -> current.copy(domainBlockedByMe = blocked)
                }.copy(
                    followedByMe = following,
                    followsMe = current.followsMe && !blocked,
                    followerCount = if (current.followedByMe && !following) (current.followerCount - 1).coerceAtLeast(0) else current.followerCount,
                ),
            )
            followFailed.emit(
                when {
                    !blocked -> R.string.account_unblocked
                    target is BlockTarget.Server -> R.string.server_blocked
                    else -> R.string.account_blocked
                },
            )
        }
    }

    private fun setProfile(profile: Profile) = _state.update { state ->
        if (state is ProfileUiState.Loaded) state.copy(content = state.content.copy(profile = profile)) else state
    }

    private fun recommendationsTab(siteUrl: String?): List<ProfileTab> =
        if (siteUrl != null && _site.value?.takeIf { it.url == siteUrl }?.recommendations?.isNotEmpty() == true) {
            listOf(ProfileTab.Recommendations)
        } else {
            emptyList()
        }

    /** Shows the Recommendations tab only while the site has recommendations to list. */
    private fun showRecommendationsTab(show: Boolean) {
        if (!show && _tab.value == ProfileTab.Recommendations) _tab.value = ProfileTab.Posts
        _state.update { state ->
            if (state !is ProfileUiState.Loaded) return@update state
            val tabs = state.content.tabs
            val has = ProfileTab.Recommendations in tabs
            when {
                show && !has -> state.copy(content = state.content.copy(tabs = tabs + ProfileTab.Recommendations))
                !show && has -> state.copy(content = state.content.copy(tabs = tabs - ProfileTab.Recommendations))
                else -> state
            }
        }
    }

    /** What a Ghost profile's site publishes (its recommendations, whether it takes members) and where the reader stands with it. */
    private fun loadSite(content: ProfileContent) {
        siteJob?.cancel()
        val siteUrl = ghostSiteUrl(content.profile.apId)
        if (siteUrl == null) {
            _site.value = null
            return
        }
        if (_site.value?.url != siteUrl) _site.value = ProfileSite(siteUrl)
        siteJob = viewModelScope.launch {
            launch {
                val info = ghostSites.siteInfo(siteUrl)
                updateSite(siteUrl) { it.copy(title = info?.title, acceptsMembers = info?.allowsSubscribing == true) }
            }
            launch { updateSubscribed(siteUrl) }
            launch { loadRecommendations(siteUrl) }
            if (!content.isMe) {
                launch {
                    try {
                        ownRecommendations.refresh()
                    } catch (_: GhostException) {
                        // Without the list the Recommend button stays hidden.
                    }
                }
            }
        }
    }

    private suspend fun loadRecommendations(siteUrl: String) {
        updateSite(siteUrl) { it.copy(recommendationsStatus = RecommendationsStatus.Loading) }
        val urls = try {
            ghostSites.recommendedUrls(siteUrl)
        } catch (_: IOException) {
            updateSite(siteUrl) { it.copy(recommendationsStatus = RecommendationsStatus.Failed) }
            return
        }
        updateSite(siteUrl) { site ->
            // A refresh keeps what it already knew about each site while it looks again.
            val known = site.recommendations.associate { it.url to it.details }
            site.copy(
                recommendations = urls.map { SiteRecommendation(it, known[it]) },
                recommendationsStatus = RecommendationsStatus.Loaded,
            )
        }
        showRecommendationsTab(urls.isNotEmpty())
        ghostSites.details(urls).collect { (index, details) ->
            updateSite(siteUrl) { site ->
                site.copy(
                    recommendations = site.recommendations.mapIndexed { i, item -> if (i == index) item.copy(details = details) else item },
                )
            }
        }
    }

    private suspend fun updateSubscribed(siteUrl: String) {
        val subscribed = members.isSubscribed(siteUrl)
        updateSite(siteUrl) { it.copy(subscribed = subscribed) }
    }

    private fun updateSite(siteUrl: String, change: (ProfileSite) -> ProfileSite) =
        _site.update { if (it?.url == siteUrl) change(it) else it }

    /** Adds this profile's site to the signed-in site's recommendations. */
    fun recommend() = changeRecommendation(add = true)

    /** Removes this profile's site from the signed-in site's recommendations. */
    fun unrecommend() = changeRecommendation(add = false)

    private fun changeRecommendation(add: Boolean) {
        val siteUrl = _site.value?.url ?: return
        if (!recommendBusy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                if (add) ownRecommendations.recommend(siteUrl) else ownRecommendations.remove(siteUrl)
            } catch (e: GhostException) {
                val denied = e is GhostException.Unauthorized || e is GhostException.Rejected
                siteMessages.emit(if (denied) R.string.recommend_not_allowed else R.string.recommend_failed)
            } finally {
                recommendBusy.value = false
            }
        }
    }

    private fun pages(authoritative: Boolean = true, load: suspend (StoredAccount, String?) -> PostsPage): Flow<PagingData<FeedItem>> =
        Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
            PostsPagingSource(interactions, authoritative) { next ->
                val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
                load(account, next)
            }
        }.flow.cachedIn(viewModelScope)

    private suspend fun Profile.toContent(isMe: Boolean) = withContext(Dispatchers.Default) {
        ProfileContent(
            profile = this@toContent,
            bio = if (bio.isBlank()) AnnotatedString("") else parsePostHtml(bioHtml(bio)),
            fields = customFields.mapNotNull { (label, value) ->
                value?.takeIf { it.isNotBlank() }?.let { label to parsePostHtml(it) }
            },
            isMe = isMe,
            tabs = profileTabs(isMe, apId) + recommendationsTab(ghostSiteUrl(apId)),
        )
    }
}

/**
 * Whether [hidden] takes this item out of the profile of [owner]: its post was deleted, or it is
 * someone else's post (liked, replied to, reposted by the owner) whose author is blocked. The owner's
 * own posts stay when the owner or their server is blocked, as they do for a block made earlier.
 */
private fun FeedItem.isHiddenOnProfile(hidden: Hidden, owner: String): Boolean =
    post.id in hidden.posts ||
        (!post.authoredByMe && !post.author.handle.equals(owner, ignoreCase = true) && hidden.hides(post.author))

/**
 * Likes are only listed for the site's own account. Replies come from the account's outbox on its
 * own server, which Ghost sites keep to posts, so only accounts elsewhere get that tab.
 */
internal fun profileTabs(isMe: Boolean, apId: String): List<ProfileTab> = when {
    isMe -> listOf(ProfileTab.Posts, ProfileTab.Likes)
    apId.startsWith("https://") && "/.ghost/activitypub/" !in apId -> listOf(ProfileTab.Posts, ProfileTab.Replies)
    else -> listOf(ProfileTab.Posts)
}
