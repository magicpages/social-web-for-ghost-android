package co.magicpages.socialweb.ui.explore

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.filter
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.FreshAvatars
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.PostInteractions
import co.magicpages.socialweb.data.activitypub.PostsPage
import co.magicpages.socialweb.ui.posts.FeedItem
import co.magicpages.socialweb.ui.posts.PostsPagingSource
import co.magicpages.socialweb.ui.posts.ReplyComposer
import co.magicpages.socialweb.ui.posts.postMessages
import androidx.paging.map
import co.magicpages.socialweb.data.fediverse.FediverseClient
import co.magicpages.socialweb.data.fediverse.FediverseExplore
import co.magicpages.socialweb.data.fediverse.PublicServerException
import co.magicpages.socialweb.data.fediverse.FediverseTrends
import co.magicpages.socialweb.data.settings.AppSettings
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Moderation
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.activitypub.SuggestedAccount
import co.magicpages.socialweb.data.activitypub.SuggestedAccountsPage
import co.magicpages.socialweb.data.activitypub.Topic
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.activitypub.NeighbourFacts
import co.magicpages.socialweb.data.activitypub.PublishingRhythm
import co.magicpages.socialweb.data.ghost.AdminRecommendation
import co.magicpages.socialweb.data.ghost.OwnRecommendations
import co.magicpages.socialweb.data.ghost.ghostSiteUrl
import co.magicpages.socialweb.data.ghost.recommends
import co.magicpages.socialweb.data.ghost.parseWebUri
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the search shows for what has been typed. */
@Immutable
sealed interface SearchUiState {
    /** Nothing typed yet that the service can search for. */
    data object Idle : SearchUiState

    data object Loading : SearchUiState

    /** [post] is the post a pasted web address points to, when it points to one the service can show. */
    data class Found(val accounts: List<Author>, val post: Post? = null) : SearchUiState

    data class Failed(val error: Throwable) : SearchUiState
}

/** What a selected topic lists. */
enum class TopicView { Posts, People }

/** An account on the Neighbours tab. [siteUrl] is its Ghost site, which only Ghost accounts have. */
@Immutable
data class Neighbour(val account: SuggestedAccount) {
    val siteUrl: String? get() = ghostSiteUrl(account.id)
}

/** What the Neighbours tab shows. */
@Immutable
sealed interface NeighboursUiState {
    data object Loading : NeighboursUiState

    /** At most the 20 accounts the service suggested, in its order; they stay put while the tab is open. */
    data class Loaded(val neighbours: List<Neighbour>) : NeighboursUiState

    data class Failed(val error: Throwable) : NeighboursUiState

    /** The tab isn't offered: the service refuses the list to this role, or has nobody to suggest. */
    data object Unavailable : NeighboursUiState
}

/** What was found out about a neighbour, each part as it arrives; missing parts show nothing. */
@Immutable
data class NeighbourDetails(
    val latestArticleTitle: String? = null,
    val rhythm: PublishingRhythm? = null,
    /** Their site's own `recommendations.json` lists this site. */
    val recommendsYou: Boolean = false,
)

/** Where the Recommend button of a neighbour stands. */
enum class RecommendState {
    /** Nothing to offer: no Ghost site, already recommended, or this site's own list couldn't be read. */
    Hidden,
    Available,
    Busy,

    /** Recommended just now, from here. */
    Done,
}

/** The signed-in site's recommendations as they bear on the neighbours' Recommend buttons. */
@Immutable
class RecommendStates(
    private val own: List<AdminRecommendation>?,
    private val busy: Set<String>,
    private val done: Set<String>,
) {
    fun of(siteUrl: String?): RecommendState = when {
        siteUrl == null -> RecommendState.Hidden
        siteUrl in done -> RecommendState.Done
        // Busy before the own list: it already holds the site a moment before the button settles on Done.
        siteUrl in busy -> RecommendState.Busy
        own == null || own.recommends(siteUrl) -> RecommendState.Hidden
        else -> RecommendState.Available
    }
}

/**
 * The Explore tab: searching for accounts, and the accounts the service suggests, either for the
 * site ("For you") or under one of its topics. Follow changes made here are kept in [following] so
 * every list that shows the account agrees, whichever page of it was loaded first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val accounts: AccountStore,
    private val activityPub: ActivityPubClient,
    private val interactions: PostInteractions,
    private val freshAvatars: FreshAvatars,
    private val moderation: Moderation,
    private val fediverseClient: FediverseClient,
    private val settings: AppSettings,
    private val ownRecommendations: OwnRecommendations,
    private val neighbourFacts: NeighbourFacts,
) : ViewModel() {
    // The same account with a replaced credential keeps its lists; a switch starts them anew.
    private val active: Flow<StoredAccount> = accounts.account
        .filterNotNull()
        .distinctUntilChangedBy { it.site to it.credential }

    private val query = MutableStateFlow("")
    private val searchRetries = MutableStateFlow(0)
    private val topicRefreshes = MutableStateFlow(0)

    private val _selected = MutableStateFlow<String?>(null)

    /** The topic slug whose accounts are listed; null is "For you". */
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _topicView = MutableStateFlow(TopicView.Posts)

    /** Whether a selected topic shows its posts or its people; "For you" only has people. */
    val topicView: StateFlow<TopicView> = _topicView.asStateFlow()

    /** The experimental Fediverse source, from Settings: whether Explore offers it, and from which server. */
    val fediverse: StateFlow<FediverseExplore> = settings.fediverseExplore

    private val _fediverseSelected = MutableStateFlow(false)

    /** Whether the Fediverse chip is the one selected; never while the source is switched off. */
    val fediverseSelected: StateFlow<Boolean> = combine(_fediverseSelected, fediverse) { selected, source -> selected && source.enabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Set the first time the chip is picked: nothing is asked of the server before then, and its lists
    // are kept from then on, so coming back from another chip doesn't load them again.
    private val fediverseVisited = MutableStateFlow(false)

    private val _fediverseTag = MutableStateFlow<String?>(null)

    /** The hashtag whose posts the Fediverse source lists instead of its trending posts. */
    val fediverseTag: StateFlow<String?> = _fediverseTag.asStateFlow()

    private val trendRefreshes = MutableStateFlow(0)

    private val _following = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Follow state the user changed here, by lower-case handle; it wins over what a list was loaded with. */
    val following: StateFlow<Map<String, Boolean>> = _following.asStateFlow()

    // Counts account switches, so a follow that fails after one doesn't touch the new account's state.
    private var generation = 0
    private val inFlight = HashSet<String>()

    private val followFailures = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private val pinChanges = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private val recommendFailures = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** Reply editor state for the posts of a topic. */
    val composer = ReplyComposer(viewModelScope, interactions)

    /** String resources to show in a snackbar. */
    val messages: Flow<Int> = merge(followFailures, pinChanges, recommendFailures, postMessages(interactions, composer, moderation))

    private val activeId: StateFlow<String?> = accounts.activeId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The topic slugs the active account pinned to the Reader. */
    val pinned: StateFlow<Set<String>> = combine(activeId, settings.pinnedTopics) { id, pins -> id?.let { pins[it] }.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Pins the topic [slug] to the active account's Reader, or takes it off, and says where it went. */
    fun setPinned(slug: String, pinned: Boolean) {
        val id = activeId.value ?: return
        settings.setTopicPinned(id, slug, pinned)
        pinChanges.tryEmit(if (pinned) R.string.explore_topic_pinned else R.string.explore_topic_unpinned)
    }

    private val _neighboursSelected = MutableStateFlow(true)
    private val neighbourRefreshes = MutableStateFlow(0)
    private val _neighbours = MutableStateFlow<NeighboursUiState>(NeighboursUiState.Loading)
    private val _neighboursRefreshing = MutableStateFlow(false)
    private val _neighbourDetails = MutableStateFlow<Map<String, NeighbourDetails>>(emptyMap())
    private val activityAsked = HashSet<String>()
    private val recommendsAsked = HashSet<String>()
    private val recommending = MutableStateFlow<Set<String>>(emptySet())
    private val recommendedHere = MutableStateFlow<Set<String>>(emptySet())

    // The account (site and credential) the Neighbours were loaded for: a reload for the same one keeps them
    // on screen while it runs, another one starts from loading.
    private var neighboursOwner: Any? = null

    /**
     * The Neighbours tab: accounts in the site's own topics that it doesn't follow yet. Loaded once and then left
     * alone, so a follow doesn't reshuffle the list under the thumb; a pull to refresh reads it again. The tab is
     * [NeighboursUiState.Unavailable] for roles the service refuses, and when nobody is left to show.
     */
    val neighbours: StateFlow<NeighboursUiState> = combine(_neighbours, moderation.hidden) { state, hidden ->
        if (state is NeighboursUiState.Loaded && !hidden.isEmpty) {
            val shown = state.neighbours.filterNot { hidden.hides(it.account.toAuthor()) }
            if (shown.isEmpty()) NeighboursUiState.Unavailable else state.copy(neighbours = shown)
        } else {
            state
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NeighboursUiState.Loading)

    /** Whether the Neighbours chip is the one selected, as it is until another is picked; never while the tab is unavailable. */
    val neighboursSelected: StateFlow<Boolean> = combine(_neighboursSelected, neighbours) { selected, state ->
        selected && state !is NeighboursUiState.Unavailable
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Whether the Neighbours are being read again while the old ones are still shown. */
    val neighboursRefreshing: StateFlow<Boolean> = _neighboursRefreshing.asStateFlow()

    /** What is known about each neighbour so far, by lower-case handle. */
    val neighbourDetails: StateFlow<Map<String, NeighbourDetails>> = _neighbourDetails.asStateFlow()

    /** How the neighbours' Recommend buttons stand. */
    val recommendStates: StateFlow<RecommendStates> = combine(ownRecommendations.list, recommending, recommendedHere) { own, busy, done ->
        RecommendStates(own, busy, done)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RecommendStates(null, emptySet(), emptySet()))

    init {
        // Who an account follows, and which topic was open, say nothing about another account.
        accounts.activeId
            .onEach {
                generation++
                _selected.value = null
                _topicView.value = TopicView.Posts
                _following.value = emptyMap()
                _fediverseSelected.value = false
                _fediverseTag.value = null
                _neighboursSelected.value = true
                _neighbourDetails.value = emptyMap()
                activityAsked.clear()
                recommendsAsked.clear()
                recommending.value = emptySet()
                recommendedHere.value = emptySet()
            }
            .launchIn(viewModelScope)
        // A hashtag belongs to the server it was opened on; switching the source off leaves no chip to come back to.
        fediverse
            .map { it.server }
            .distinctUntilChanged()
            .drop(1)
            .onEach { _fediverseTag.value = null }
            .launchIn(viewModelScope)
        fediverse
            .filter { !it.enabled }
            .onEach {
                _fediverseSelected.value = false
                _fediverseTag.value = null
            }
            .launchIn(viewModelScope)
    }

    init {
        combine(active, neighbourRefreshes) { account, _ -> account }
            .mapLatest { account ->
                val owner = account.site to account.credential
                if (owner != neighboursOwner) {
                    neighboursOwner = owner
                    _neighbours.value = NeighboursUiState.Loading
                }
                _neighboursRefreshing.value = _neighbours.value is NeighboursUiState.Loaded
                try {
                    val result = loadNeighbours(account)
                    // A reload that fails leaves the accounts being read where they are.
                    if (result !is NeighboursUiState.Failed || _neighbours.value !is NeighboursUiState.Loaded) _neighbours.value = result
                    if (result is NeighboursUiState.Loaded) {
                        // Which Recommend buttons to offer; without the list none is.
                        viewModelScope.launch {
                            try {
                                ownRecommendations.refresh()
                            } catch (_: GhostException) {
                            }
                        }
                    }
                } finally {
                    _neighboursRefreshing.value = false
                }
            }
            .launchIn(viewModelScope)
    }

    private suspend fun loadNeighbours(account: StoredAccount): NeighboursUiState = try {
        val people = activityPub.recommendations(account.site, account.credential, NEIGHBOURS_LIMIT)
            .filterNot { account.isMe(it.handle) }
            .distinctBy { it.handle.lowercase() }
            .take(NEIGHBOURS_LIMIT)
        if (people.isEmpty()) NeighboursUiState.Unavailable else NeighboursUiState.Loaded(people.map(::Neighbour))
    } catch (e: SocialWebException) {
        // Editors get a 403: the tab simply isn't theirs, which is no error to show.
        if (e.status == 403) NeighboursUiState.Unavailable else NeighboursUiState.Failed(e)
    } catch (e: GhostException) {
        NeighboursUiState.Failed(e)
    }

    val search: StateFlow<SearchUiState> = combine(
        combine(active, query, searchRetries) { account, text, _ -> account to text }
            .flatMapLatest { (account, text) ->
                flow {
                    if (text.length < MIN_QUERY_LENGTH) {
                        emit(SearchUiState.Idle)
                        return@flow
                    }
                    // Typing restarts this flow, so only a pause in typing gets as far as the service.
                    delay(DEBOUNCE_MILLIS)
                    emit(SearchUiState.Loading)
                    emit(
                        try {
                            find(account, text)
                        } catch (e: SocialWebException) {
                            SearchUiState.Failed(e)
                        } catch (e: GhostException) {
                            SearchUiState.Failed(e)
                        },
                    )
                }
            },
        moderation.hidden,
    ) { state, hidden ->
        if (state is SearchUiState.Found) {
            state.copy(
                accounts = state.accounts.filterNot { hidden.hides(it) },
                post = state.post?.takeUnless { hidden.hides(it) },
            )
        } else {
            state
        }
    // Kept running while a profile opened from the results is on top, so they're there, not loading, on the way back.
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState.Idle)

    /**
     * The service's topics; empty until the first load, or when it fails, which leaves just "For you".
     * A failed reload keeps the topics already shown, but never those of another account.
     */
    val topics: StateFlow<List<Topic>> = combine(active, topicRefreshes) { account, _ -> account }
        .mapLatest { account ->
            val loaded = try {
                activityPub.topics(account.site, account.credential)
            } catch (_: SocialWebException) {
                null
            } catch (_: GhostException) {
                null
            }
            // A topic the service no longer lists can't be left selected, with no chip to show it.
            if (loaded != null && _selected.value != null && loaded.none { it.slug == _selected.value }) {
                _selected.value = null
            }
            (account.site to account.credential) to loaded
        }
        .runningFold(null as Pair<Any, List<Topic>>?) { shown, (owner, loaded) ->
            when {
                loaded != null -> owner to loaded
                shown?.first == owner -> shown
                else -> owner to emptyList()
            }
        }
        .map { it?.second.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Each topic or account starts with an empty list that is loading: paging keeps the previous
    // generation's items until the new one's first refresh arrives, which would leave the old topic
    // under the new chip and hide a failed load from the error state (it needs no items). A pull to
    // refresh of the same topic goes through the pager's own refresh and so never passes this.
    val suggestions: Flow<PagingData<SuggestedAccount>> = combine(active, _selected) { account, slug -> account to slug }
        .flatMapLatest { (account, slug) ->
            Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
                SuggestionsPagingSource { next ->
                    val page = if (slug == null) {
                        // One list, with no further pages.
                        SuggestedAccountsPage(activityPub.recommendations(account.site, account.credential, PAGE_SIZE))
                    } else {
                        activityPub.explore(account.site, account.credential, slug, next)
                    }
                    page.copy(accounts = page.accounts.filterNot { account.isMe(it.handle) })
                }
            }.flow.onStart { emit(PagingData.empty(LOADING_STATES)) }
        }
        .cachedIn(viewModelScope)
        .combine(moderation.hidden) { data, hidden ->
            if (hidden.isEmpty) data else data.filter { !hidden.hides(it.toAuthor()) }
        }

    // Like the suggestions, a topic or account starts with an empty list that is loading. The posts of
    // a topic are the same posts the Notes list shows, so likes and reposts made there agree with these.
    val topicPosts: Flow<PagingData<FeedItem>> = combine(active, _selected) { account, slug -> account to slug }
        .flatMapLatest { (account, slug) ->
            if (slug == null) {
                // "For you" has no posts of its own.
                flowOf(PagingData.empty<FeedItem>())
            } else {
                Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
                    PostsPagingSource(interactions) { next ->
                        activityPub.discoverFeed(account.site, account.credential, slug, next).also { freshAvatars.track(it.posts) }
                    }
                }.flow.onStart { emit(PagingData.empty(LOADING_STATES)) }
            }
        }
        .cachedIn(viewModelScope)
        .combine(interactions.overrides) { data, overrides ->
            if (overrides.isEmpty()) data else data.map { it.with(overrides) }
        }
        // What was deleted or blocked just now leaves at once; the service leaves it out of the next load.
        .combine(moderation.hidden) { data, hidden ->
            if (hidden.isEmpty) data else data.filter { !it.isHiddenBy(hidden) }
        }

    // Posts from a public server, as its own API gives them: its members' best recent posts, or its trending
    // posts when it keeps its members' posts to signed-in users. Whether the user liked or reposted one isn't
    // known there, so its pages leave the site's overrides alone; a like made here still shows through them.
    val fediversePosts: Flow<PagingData<FeedItem>> = combine(active, fediverseVisited, fediverse, _fediverseTag) { _, visited, source, tag ->
        Triple(visited && source.enabled, source.server, tag)
    }
        .distinctUntilChanged()
        .flatMapLatest { (visited, server, tag) ->
            if (!visited) {
                flowOf(PagingData.empty<FeedItem>())
            } else {
                Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
                    PostsPagingSource(interactions, authoritative = false) { next ->
                        if (tag == null) {
                            val offset = next?.toIntOrNull() ?: 0
                            val highlights = fediverseClient.highlights(server)
                            if (highlights == null) fediverseClient.trendingPosts(server, offset) else highlights.posts.page(offset)
                        } else {
                            fediverseClient.tagPosts(server, tag, next, membersOnly = fediverseClient.showsMembers(server))
                        }
                    }
                }.flow.onStart { emit(PagingData.empty(LOADING_STATES)) }
            }
        }
        .cachedIn(viewModelScope)
        .combine(interactions.overrides) { data, overrides ->
            if (overrides.isEmpty()) data else data.map { it.with(overrides) }
        }
        .combine(moderation.hidden) { data, hidden ->
            if (hidden.isEmpty) data else data.filter { !it.isHiddenBy(hidden) }
        }

    /**
     * The hashtags and stories shown above the server's posts, from the same source as those posts; null
     * while they load. Each list on its own is a nice-to-have: one the server won't give is left empty,
     * and only the posts say why when the server can't be read at all.
     */
    val fediverseTrends: StateFlow<FediverseTrends?> = combine(fediverseVisited, fediverse, trendRefreshes) { visited, source, _ ->
        source.server.takeIf { visited && source.enabled }
    }
        .flatMapLatest { server ->
            flow {
                if (server == null) return@flow
                emit(null)
                emit(trendsOf(server))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun trendsOf(server: String): FediverseTrends {
        val highlights = try {
            fediverseClient.highlights(server)
        } catch (_: GhostException) {
            return FediverseTrends(emptyList(), emptyList(), fromMembers = false)
        } catch (_: PublicServerException) {
            return FediverseTrends(emptyList(), emptyList(), fromMembers = false)
        }
        if (highlights != null) return FediverseTrends(highlights.tags, highlights.links, fromMembers = true)
        return coroutineScope {
            val tags = async { readOrEmpty { fediverseClient.trendingTags(server) } }
            val links = async { readOrEmpty { fediverseClient.trendingLinks(server) } }
            FediverseTrends(tags.await(), links.await(), fromMembers = false)
        }
    }

    private suspend fun <T> readOrEmpty(read: suspend () -> List<T>): List<T> = try {
        read()
    } catch (_: GhostException) {
        emptyList()
    } catch (_: PublicServerException) {
        emptyList()
    }

    private fun List<Post>.page(offset: Int): PostsPage {
        val end = minOf(offset + PAGE_SIZE, size)
        return PostsPage(if (offset < end) subList(offset, end) else emptyList(), next = if (end < size) "$end" else null)
    }

    /** Sets what the search field holds; the service is asked once typing pauses. */
    fun setQuery(text: String) {
        query.value = text.trim()
    }

    fun retrySearch() = searchRetries.update { it + 1 }

    /**
     * The accounts for [text] and, when it is a web address, the post it points to: the service resolves the
     * address as a post or as an account, and only the account search says so for an address that is neither.
     */
    private suspend fun find(account: StoredAccount, text: String): SearchUiState.Found = coroutineScope {
        val post = if (isHttpsAddress(text)) async { postAt(account, text) } else null
        val people = try {
            activityPub.searchAccounts(account.site, account.credential, text)
                .filterNot { account.isMe(it.handle) }
                .distinctBy { it.handle }
        } catch (e: SocialWebException) {
            // The account search may refuse a post's address; that is no failure when the post was found.
            if (post?.await() == null) throw e
            emptyList()
        }
        SearchUiState.Found(people, post?.await())
    }

    /** The post at [url], or null when the service has none to show (not a post, deleted, its server unreachable). */
    private suspend fun postAt(account: StoredAccount, url: String): Post? = try {
        activityPub.post(account.site, account.credential, url).takeUnless { it.isDeleted }
    } catch (_: SocialWebException) {
        null
    } catch (_: GhostException) {
        null
    }

    private fun isHttpsAddress(text: String) = parseWebUri(text)?.scheme.equals("https", ignoreCase = true)

    /** Picks the topic to list, or "For you" for null. */
    fun select(slug: String?) {
        _neighboursSelected.value = false
        _fediverseSelected.value = false
        _fediverseTag.value = null
        if (_selected.value == slug) return
        // The list that loads next is the service's current word on who is followed.
        _following.value = emptyMap()
        _selected.value = slug
    }

    /** Picks the Fediverse source, on its trending posts. */
    fun selectFediverse() {
        _neighboursSelected.value = false
        _fediverseSelected.value = true
        fediverseVisited.value = true
    }

    /** Picks the Neighbours tab. */
    fun selectNeighbours() {
        _fediverseSelected.value = false
        _fediverseTag.value = null
        _neighboursSelected.value = true
    }

    /** Reads the Neighbours again, which may bring other accounts; only asked for by the user. */
    fun refreshNeighbours() {
        neighbourRefreshes.update { it + 1 }
    }

    /**
     * Looks up what a card shows about [neighbour]: its newest article and how often it publishes, and whether its
     * site recommends this one. Asked for as the card comes into view, once; what can't be read is asked again the
     * next time it does.
     */
    fun loadNeighbourDetails(neighbour: Neighbour) {
        val handle = neighbour.account.handle
        val key = handle.lowercase()
        val siteUrl = neighbour.siteUrl
        val askActivity = activityAsked.add(key)
        val askRecommends = siteUrl != null && recommendsAsked.add(key)
        if (!askActivity && !askRecommends) return
        val startedIn = generation
        viewModelScope.launch {
            val account = accounts.account.first() ?: return@launch
            if (askActivity) {
                launch {
                    val activity = neighbourFacts.activity(account, handle)
                    if (startedIn != generation) return@launch
                    if (activity == null) {
                        activityAsked.remove(key)
                    } else {
                        putDetails(key) { copy(latestArticleTitle = activity.latestArticleTitle, rhythm = activity.rhythm) }
                    }
                }
            }
            if (askRecommends && siteUrl != null) {
                launch {
                    val recommends = neighbourFacts.recommends(siteUrl, account.site.siteUrl)
                    if (startedIn != generation) return@launch
                    if (recommends == null) recommendsAsked.remove(key) else putDetails(key) { copy(recommendsYou = recommends) }
                }
            }
        }
    }

    private fun putDetails(key: String, change: NeighbourDetails.() -> NeighbourDetails) {
        _neighbourDetails.update { it + (key to (it[key] ?: NeighbourDetails()).change()) }
    }

    /** Adds [neighbour]'s site to the site's recommendations; the button says how it went. */
    fun recommend(neighbour: Neighbour) {
        val siteUrl = neighbour.siteUrl ?: return
        if (siteUrl in recommending.value) return
        recommending.update { it + siteUrl }
        val startedIn = generation
        viewModelScope.launch {
            try {
                ownRecommendations.recommend(siteUrl)
                if (startedIn == generation) recommendedHere.update { it + siteUrl }
            } catch (e: GhostException) {
                val denied = e is GhostException.Unauthorized || e is GhostException.Rejected
                recommendFailures.emit(if (denied) R.string.recommend_not_allowed else R.string.recommend_failed)
            } finally {
                recommending.update { it - siteUrl }
            }
        }
    }

    /** Lists the posts under #[tag], or the trending posts again for null. */
    fun openTag(tag: String?) {
        _fediverseTag.value = tag?.removePrefix("#")
    }

    /** Reads the server again: the hashtags and stories here, and its posts once the list refreshes after this. */
    fun refreshFediverse() {
        fediverseClient.forgetHighlights(fediverse.value.server)
        trendRefreshes.update { it + 1 }
    }

    /** Switches a selected topic between its posts and its people. */
    fun setTopicView(view: TopicView) {
        _topicView.value = view
    }

    fun toggleLike(post: Post) = interactions.toggleLike(post)
    fun toggleRepost(post: Post) = interactions.toggleRepost(post)

    /**
     * Asks for the topics again and drops the follow changes made here, since a follow made
     * elsewhere (a profile) would otherwise stay masked; the list of accounts reloads through
     * its own refresh.
     */
    fun refresh() {
        _following.value = emptyMap()
        topicRefreshes.update { it + 1 }
    }

    /** Follows or unfollows [author] at once, rolling back with a message if the service refuses. */
    fun toggleFollow(author: Author) {
        val key = author.handle.lowercase()
        if (!inFlight.add(key)) return
        val before = _following.value[key] ?: author.followedByMe
        val after = !before
        val startedIn = generation
        setFollowing(key, after)
        viewModelScope.launch {
            try {
                val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
                if (after) {
                    activityPub.follow(account.site, account.credential, author.handle)
                } else {
                    activityPub.unfollow(account.site, account.credential, author.handle)
                }
            } catch (e: SocialWebException) {
                // 409: already in the requested state on the service.
                if (e.status != 409) rollBack(key, before, startedIn)
            } catch (_: GhostException) {
                rollBack(key, before, startedIn)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private suspend fun rollBack(key: String, before: Boolean, startedIn: Int) {
        if (startedIn != generation) return
        setFollowing(key, before)
        followFailures.emit(R.string.follow_failed)
    }

    /** Records the follow state of [key] for every list, the Neighbours too, which keep their accounts as loaded. */
    private fun setFollowing(key: String, value: Boolean) {
        _following.update { it + (key to value) }
        _neighbours.update { state ->
            if (state !is NeighboursUiState.Loaded) return@update state
            state.copy(
                neighbours = state.neighbours.map {
                    if (it.account.handle.lowercase() == key) Neighbour(it.account.copy(followedByMe = value)) else it
                },
            )
        }
    }

    private fun StoredAccount.isMe(handle: String) = handle.equals(profile.handle, ignoreCase = true)

    private companion object {
        /** The service searches from two characters on. */
        const val MIN_QUERY_LENGTH = 2
        const val DEBOUNCE_MILLIS = 300L
        const val PAGE_SIZE = 20
        const val NEIGHBOURS_LIMIT = 20

        /** A first load in flight, with nothing yet to show. */
        val LOADING_STATES = LoadStates(
            refresh = LoadState.Loading,
            prepend = LoadState.NotLoading(endOfPaginationReached = false),
            append = LoadState.NotLoading(endOfPaginationReached = false),
        )
    }
}

/** Pages through the accounts of a topic with the service's offset cursor, each account once. */
private class SuggestionsPagingSource(
    private val loadPage: suspend (next: String?) -> SuggestedAccountsPage,
) : PagingSource<String, SuggestedAccount>() {
    // Lists can shift between pages; LazyColumn keys must stay unique.
    private val seen = HashSet<String>()

    override suspend fun load(params: LoadParams<String>): LoadResult<String, SuggestedAccount> = try {
        val page = loadPage(params.key)
        LoadResult.Page(data = page.accounts.filter { seen.add(it.handle) }, prevKey = null, nextKey = page.next)
    } catch (e: GhostException) {
        LoadResult.Error(e)
    } catch (e: SocialWebException) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<String, SuggestedAccount>): String? = null
}
