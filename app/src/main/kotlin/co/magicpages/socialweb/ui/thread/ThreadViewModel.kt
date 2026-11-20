package co.magicpages.socialweb.ui.thread

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.Interactions
import co.magicpages.socialweb.data.activitypub.Hidden
import co.magicpages.socialweb.data.activitypub.Moderation
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.PostInteractions
import co.magicpages.socialweb.data.activitypub.ReplyChain
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.ghost.ghostSiteUrl
import co.magicpages.socialweb.data.member.FullArticle
import co.magicpages.socialweb.data.member.MemberArticles
import co.magicpages.socialweb.data.member.MemberSessions
import co.magicpages.socialweb.ui.article.ArticleAccess
import co.magicpages.socialweb.ui.article.ArticleDocument
import co.magicpages.socialweb.ui.article.ArticleParser
import co.magicpages.socialweb.ui.posts.FeedItem
import co.magicpages.socialweb.ui.posts.ReplyComposer
import co.magicpages.socialweb.ui.posts.postMessages
import co.magicpages.socialweb.ui.posts.toFeedItem
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class ReplyGroup(val reply: FeedItem, val followUps: List<FeedItem>, val hasMore: Boolean)

@Immutable
data class ThreadContent(
    val ancestors: List<FeedItem>,
    val hasEarlier: Boolean,
    val post: FeedItem,
    val replies: List<ReplyGroup>,
    val next: String?,
    val appending: Boolean = false,
    val appendFailed: Boolean = false,
    /** The post this one answers, when it lives where the app can only link to it. */
    val parentLink: ReplyParents.Parent.Elsewhere? = null,
    /** The body of [post] laid out for the reader, when it is an article. */
    val article: ArticleDocument? = null,
    /** Set while [article] stops at a members-only gate: the site to sign in to, and how far unlocking has got. */
    val articleAccess: ArticleAccess? = null,
) {
    fun with(overrides: Map<String, Interactions>) = copy(
        ancestors = ancestors.map { it.with(overrides) },
        post = post.with(overrides),
        replies = replies.map { group ->
            group.copy(reply = group.reply.with(overrides), followUps = group.followUps.map { it.with(overrides) })
        },
    )

    /** Without the ancestors, replies and follow-ups that were just deleted or whose authors were just blocked. */
    fun without(hidden: Hidden) = copy(
        ancestors = ancestors.filterNot { it.isHiddenBy(hidden) },
        replies = replies
            .filterNot { it.reply.isHiddenBy(hidden) }
            .map { group -> group.copy(followUps = group.followUps.filterNot { it.isHiddenBy(hidden) }) },
    )

    /** Whether the service gave no parent although the post may answer one. */
    val mayMissParent: Boolean get() = ancestors.isEmpty() && parentLink == null && !post.post.isArticle && !post.post.isDeleted

    fun withParent(parent: ReplyParents.Parent) = when (parent) {
        is ReplyParents.Parent.Found -> copy(ancestors = listOf(parent.item))
        is ReplyParents.Parent.Elsewhere -> copy(parentLink = parent)
        ReplyParents.Parent.None -> this
    }
}

sealed interface ThreadUiState {
    data object Loading : ThreadUiState
    data class Failed(val error: Throwable) : ThreadUiState
    data class Loaded(val content: ThreadContent, val refreshing: Boolean = false) : ThreadUiState
}

@HiltViewModel(assistedFactory = ThreadViewModel.Factory::class)
class ThreadViewModel @AssistedInject constructor(
    @Assisted requestedId: String,
    @Assisted resolve: Boolean,
    private val accounts: AccountStore,
    private val activityPub: ActivityPubClient,
    private val interactions: PostInteractions,
    private val replyParents: ReplyParents,
    private val memberArticles: MemberArticles,
    private val memberSessions: MemberSessions,
    private val moderation: Moderation,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(postId: String, resolve: Boolean): ThreadViewModel
    }

    private val loaded = MutableStateFlow<ThreadUiState>(ThreadUiState.Loading)

    /** The post's ActivityPub id: the one asked for, or what the service resolved the web address asked for to. */
    private var postId = requestedId
    private var resolving = resolve

    private val _unresolved = MutableStateFlow(false)

    /** Set when the web address asked for isn't a post the service can show: the screen hands it to the browser. */
    val unresolved: StateFlow<Boolean> = _unresolved.asStateFlow()

    val state: StateFlow<ThreadUiState> = combine(loaded, interactions.overrides, moderation.hidden) { state, overrides, hidden ->
        if (state !is ThreadUiState.Loaded) return@combine state
        var content = state.content
        if (overrides.isNotEmpty()) content = content.with(overrides)
        if (!hidden.isEmpty) content = content.without(hidden)
        state.copy(content = content)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState.Loading)

    /** Whether the post the thread is about was just deleted, or its author blocked: nothing is left to show. */
    val postHidden: StateFlow<Boolean> = combine(loaded, moderation.hidden) { state, hidden ->
        state is ThreadUiState.Loaded && state.content.post.isHiddenBy(hidden)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val composer = ReplyComposer(viewModelScope, interactions)
    // A delete or block of the post this thread is about closes the thread: the screen it returns to tells.
    val messages: Flow<Int> = postMessages(interactions, composer, moderation) { report ->
        val focused = (loaded.value as? ThreadUiState.Loaded)?.content?.post?.post
        focused == null || !report.affects.hides(focused)
    }

    fun toggleLike(post: Post) = interactions.toggleLike(post)
    fun toggleRepost(post: Post) = interactions.toggleRepost(post)

    private var loadJob: Job? = null
    private var parentJob: Job? = null
    private var unlockJob: Job? = null

    /** The members-only article in full, once a member session has opened it. */
    private var unlocked: ArticleDocument? = null

    init {
        load()
        // Show a new reply in place: reload once the service has it.
        composer.posted.onEach { refresh() }.launchIn(viewModelScope)
        // Signing in to the article's site in the in-app browser may unlock it.
        memberSessions.changes.drop(1).onEach { unlockArticle() }.launchIn(viewModelScope)
    }

    fun retry() = load()

    fun refresh() {
        val current = loaded.value as? ThreadUiState.Loaded ?: return load()
        // The refresh cancels any page load in flight, so clear its footer state too.
        loaded.value = current.copy(refreshing = true, content = current.content.copy(appending = false, appendFailed = false))
        load(keepContent = true)
    }

    private fun load(keepContent: Boolean = false) {
        loadJob?.cancel()
        if (!keepContent) loaded.value = ThreadUiState.Loading
        loadJob = viewModelScope.launch {
            if (!resolveAddress()) return@launch
            loaded.value = try {
                ThreadUiState.Loaded(withUnlocked(withKnownParent(fetch(next = null).toContent(withArticle = true))))
            } catch (e: GhostException) {
                if (keepContent) (loaded.value as? ThreadUiState.Loaded)?.copy(refreshing = false) ?: ThreadUiState.Failed(e)
                else ThreadUiState.Failed(e)
            } catch (e: SocialWebException) {
                if (keepContent) (loaded.value as? ThreadUiState.Loaded)?.copy(refreshing = false) ?: ThreadUiState.Failed(e)
                else ThreadUiState.Failed(e)
            }
            (loaded.value as? ThreadUiState.Loaded)?.content?.takeIf { it.mayMissParent }?.let { findParent(it.post.post.id) }
            unlockArticle()
        }
    }

    /**
     * Looks a web address up before anything else: replies are only served under a post's own ActivityPub id,
     * which the lookup returns. False when there is nothing to load. That is [unresolved] when the service
     * has no post for the address, and the error screen for what a browser wouldn't help with (offline, signed out).
     */
    private suspend fun resolveAddress(): Boolean {
        if (!resolving) return true
        try {
            val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
            postId = activityPub.post(account.site, account.credential, postId).id
            resolving = false
            return true
        } catch (e: SocialWebException) {
            if (e.status == 401 || e.status == 403) loaded.value = ThreadUiState.Failed(e) else _unresolved.value = true
        } catch (e: GhostException) {
            loaded.value = ThreadUiState.Failed(e)
        }
        return false
    }

    /** Applies a parent found earlier this session, so a refresh doesn't drop and re-add it. */
    private fun withKnownParent(content: ThreadContent): ThreadContent =
        if (content.mayMissParent) replyParents.cached(content.post.post.id)?.let(content::withParent) ?: content else content

    /** Looks for the parent alongside the thread, so paging replies isn't held up by it. */
    private fun findParent(id: String) {
        if (replyParents.cached(id) != null || parentJob?.isActive == true) return
        parentJob = viewModelScope.launch {
            val parent = replyParents.of(id)
            loaded.update { state ->
                if (state is ThreadUiState.Loaded && state.content.mayMissParent) {
                    state.copy(content = state.content.withParent(parent))
                } else {
                    state
                }
            }
        }
    }

    /**
     * Opens a members-only article in full when the in-app browser holds a member session for its site. Runs again
     * when the browser closes, which is when a session may have appeared.
     */
    private fun unlockArticle() {
        unlockJob?.cancel()
        val content = (loaded.value as? ThreadUiState.Loaded)?.content ?: return
        val access = content.articleAccess ?: return
        if (content.article?.isLocked != true) return
        val post = content.post.post
        unlockJob = viewModelScope.launch {
            if (memberSessions.hasSession(post.url)) setArticleAccess(access.copy(status = ArticleAccess.Status.Loading))
            when (val result = memberArticles.load(post.url, post.content)) {
                is FullArticle.Html -> {
                    val document = withContext(Dispatchers.Default) { ArticleParser.parse(result.html, post.url.ifBlank { null }) }
                    unlocked = document
                    loaded.update { state ->
                        if (state is ThreadUiState.Loaded) {
                            state.copy(content = state.content.copy(article = document, articleAccess = null))
                        } else {
                            state
                        }
                    }
                }
                FullArticle.SignedOut -> setArticleAccess(access.copy(status = ArticleAccess.Status.SignedOut))
                FullArticle.Unavailable -> setArticleAccess(access.copy(status = ArticleAccess.Status.Unavailable))
            }
        }
    }

    private fun setArticleAccess(access: ArticleAccess) = loaded.update { state ->
        if (state is ThreadUiState.Loaded) state.copy(content = state.content.copy(articleAccess = access)) else state
    }

    /** A refresh keeps the full article the member already unlocked instead of locking it again. */
    private fun withUnlocked(content: ThreadContent): ThreadContent {
        val full = unlocked ?: return content
        return if (content.article?.isLocked == true) content.copy(article = full, articleAccess = null) else content
    }

    /** Loads the next page of replies. */
    fun loadMore() {
        val current = loaded.value as? ThreadUiState.Loaded ?: return
        val next = current.content.next ?: return
        if (current.content.appending || loadJob?.isActive == true) return
        loaded.value = current.copy(content = current.content.copy(appending = true, appendFailed = false))
        loadJob = viewModelScope.launch {
            val page = try {
                fetch(next).toContent(withArticle = false)
            } catch (_: GhostException) {
                null
            } catch (_: SocialWebException) {
                null
            }
            loaded.update { state ->
                if (state !is ThreadUiState.Loaded) return@update state
                val content = state.content
                if (page == null) {
                    state.copy(content = content.copy(appending = false, appendFailed = true))
                } else {
                    val known = content.replies.mapTo(HashSet()) { it.reply.post.id }
                    state.copy(
                        content = content.copy(
                            replies = content.replies + page.replies.filter { it.reply.post.id !in known },
                            next = page.next,
                            appending = false,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun fetch(next: String?): ReplyChain {
        val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
        return activityPub.replies(account.site, account.credential, postId, next).also { chain ->
            interactions.reconcile(chain.allPosts())
        }
    }

    private suspend fun ReplyChain.toContent(withArticle: Boolean) = withContext(Dispatchers.Default) {
        val article = if (withArticle && post.isArticle) ArticleParser.parse(post.content, post.url.ifBlank { null }) else null
        ThreadContent(
            ancestors = ancestors.chain.map { it.toFeedItem() },
            hasEarlier = ancestors.hasMore,
            post = post.toFeedItem(),
            replies = children.map { child ->
                ReplyGroup(child.post.toFeedItem(), child.chain.map { it.toFeedItem() }, child.hasMore)
            },
            next = next,
            article = article,
            articleAccess = article?.takeIf { it.isLocked }?.let {
                ArticleAccess(ghostSiteUrl(post.author.id) ?: post.author.url, ArticleAccess.Status.SignedOut)
            },
        )
    }
}

private fun ReplyChain.allPosts(): List<Post> =
    ancestors.chain + post + children.flatMap { listOf(it.post) + it.chain }
