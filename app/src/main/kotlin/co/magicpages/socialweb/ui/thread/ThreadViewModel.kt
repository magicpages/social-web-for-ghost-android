package co.magicpages.socialweb.ui.thread

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.Interactions
import co.magicpages.socialweb.data.activitypub.Hidden
import co.magicpages.socialweb.data.activitypub.Moderation
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.PostInteractions
import co.magicpages.socialweb.data.activitypub.PostMetadata
import co.magicpages.socialweb.data.activitypub.ReplyChain
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.article.ArticleWriters
import co.magicpages.socialweb.data.fediverse.ExploredPosts
import co.magicpages.socialweb.data.fediverse.OriginThreads
import co.magicpages.socialweb.data.ghost.FediPubLink
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.ghost.GhostSiteClient
import co.magicpages.socialweb.data.ghost.ghostNoteId
import co.magicpages.socialweb.data.ghost.ghostSiteUrl
import co.magicpages.socialweb.data.ghost.parseFediPubLink
import co.magicpages.socialweb.data.member.FullArticle
import co.magicpages.socialweb.data.member.MemberArticles
import co.magicpages.socialweb.data.member.MemberSessions
import co.magicpages.socialweb.ui.article.ArticleAccess
import co.magicpages.socialweb.ui.article.ArticleDocument
import co.magicpages.socialweb.ui.article.ArticleParser
import co.magicpages.socialweb.ui.posts.FeedItem
import co.magicpages.socialweb.ui.posts.ReplyComposer
import co.magicpages.socialweb.ui.posts.displayName
import co.magicpages.socialweb.ui.posts.postMessages
import co.magicpages.socialweb.ui.posts.toFeedItem
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
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
    /** Set while the posts above the topmost ancestor load. */
    val loadingEarlier: Boolean = false,
    /** The ids of the direct replies in [replies] that only the post's home server reported; they come after the site's own. */
    val fromOrigin: Set<String> = emptySet(),
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

    /**
     * The content with [chain], the posts above the topmost ancestor, put in front of the ancestors.
     * Posts already shown are skipped, in case the responses overlap.
     */
    fun withEarlier(chain: List<FeedItem>, hasMore: Boolean): ThreadContent {
        val known = HashSet<String>()
        ancestors.mapTo(known) { it.post.id }
        known += post.post.id
        val added = chain.filter { known.add(it.post.id) }
        // Nothing new would leave the button doing nothing on every tap, so that ends the walk up.
        return copy(ancestors = added + ancestors, hasEarlier = hasMore && added.isNotEmpty(), loadingEarlier = false)
    }

    /**
     * This content, freshly loaded, with the earlier posts [previous] had loaded above its ancestors, so a
     * refresh doesn't fold a thread the reader walked up back to its last ten posts.
     */
    fun keepingEarlier(previous: ThreadContent): ThreadContent {
        val top = ancestors.firstOrNull()?.post?.id ?: return this
        val kept = previous.ancestors.takeWhile { it.post.id != top }
        // The old chain has to run into the new one: if it doesn't, the thread changed and those posts may not belong.
        if (kept.isEmpty() || kept.size == previous.ancestors.size) return this
        return copy(ancestors = kept + ancestors, hasEarlier = previous.hasEarlier)
    }

    /** The content with the reply, like and repost counts [source] reports where they're higher than the ones here. */
    fun withCounts(source: Post): ThreadContent {
        val counted = post.post.withCountsOf(source)
        return if (counted == post.post) this else copy(post = post.copy(post = counted))
    }

    /**
     * The content with the replies [origin] (the post's home server) reported put after the ones the user's site
     * has, replacing whatever it added before. The site's win: a reply it already has, in its replies or
     * anywhere in their follow-ups, isn't added again, and the follow-ups it doesn't have of a reply it does are left out.
     */
    fun withOrigin(origin: List<ReplyGroup>): ThreadContent {
        val own = replies.filter { it.reply.post.id !in fromOrigin }
        val known = HashSet<String>()
        known += post.post.id
        ancestors.mapTo(known) { it.post.id }
        for (group in own) {
            known += group.reply.post.id
            group.followUps.mapTo(known) { it.post.id }
        }
        val added = origin
            .filter { it.reply.post.id !in known }
            .map { group -> group.copy(followUps = group.followUps.filter { it.post.id !in known }) }
        return copy(replies = own + added, fromOrigin = added.mapTo(HashSet()) { it.reply.post.id })
    }

    /**
     * The content with the page of replies [page], which the user's site sent, after the ones it has sent so far
     * and in front of the home server's. Those need [withOrigin] again, which also takes out the ones the page now has.
     */
    fun withPage(page: ThreadContent): ThreadContent {
        val own = replies.filter { it.reply.post.id !in fromOrigin }
        val known = own.mapTo(HashSet()) { it.reply.post.id }
        return copy(
            replies = own + page.replies.filter { it.reply.post.id !in known },
            fromOrigin = emptySet(),
            next = page.next,
            appending = false,
        )
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
    private val articleWriters: ArticleWriters,
    private val memberSessions: MemberSessions,
    private val moderation: Moderation,
    private val explored: ExploredPosts,
    private val originThreads: OriginThreads,
    private val sites: GhostSiteClient,
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
    private val earlierFailed = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    // A delete or block of the post this thread is about closes the thread: the screen it returns to tells.
    val messages: Flow<Int> = merge(
        postMessages(interactions, composer, moderation) { report ->
            val focused = (loaded.value as? ThreadUiState.Loaded)?.content?.post?.post
            focused == null || !report.affects.hides(focused)
        },
        earlierFailed,
    )

    fun toggleLike(post: Post) = interactions.toggleLike(post)
    fun toggleRepost(post: Post) = interactions.toggleRepost(post)

    private var loadJob: Job? = null
    private var parentJob: Job? = null
    private var earlierJob: Job? = null
    private var unlockJob: Job? = null
    private var originJob: Job? = null

    /** What the post's home server last said about the thread, kept so a refresh and the next pages show it before it answers again. */
    private var origin: Origin? = null

    private class Origin(val post: Post?, val replies: List<ReplyGroup>)

    /** The writers of the article, once looked up ([loadWriters]). */
    private var writers: PostMetadata? = null
    private var writersLookedUp = false

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
        loaded.value = current.copy(
            refreshing = true,
            content = current.content.copy(appending = false, appendFailed = false, loadingEarlier = false),
        )
        load(keepContent = true)
    }

    private fun load(keepContent: Boolean = false) {
        loadJob?.cancel()
        earlierJob?.cancel()
        originJob?.cancel()
        if (!keepContent) loaded.value = ThreadUiState.Loading
        loadJob = viewModelScope.launch {
            if (!resolveAddress()) return@launch
            // Only a post Explore listed may be asked about on its own server; the user opted into that, not into the rest.
            val originId = postId.takeIf { explored[it] != null }
            loaded.value = try {
                val fresh = fetch(postId, next = null).toContent(withArticle = true)
                // A refresh keeps the earlier posts the reader loaded; the fresh response alone holds only the last ten.
                val previous = if (keepContent) (loaded.value as? ThreadUiState.Loaded)?.content else null
                val content = withWriters(withUnlocked(withKnownParent(if (previous != null) fresh.keepingEarlier(previous) else fresh)))
                ThreadUiState.Loaded(withKnownOrigin(content))
            } catch (e: GhostException) {
                if (keepContent) (loaded.value as? ThreadUiState.Loaded)?.copy(refreshing = false) ?: ThreadUiState.Failed(e)
                else ThreadUiState.Failed(e)
            } catch (e: SocialWebException) {
                if (keepContent) (loaded.value as? ThreadUiState.Loaded)?.copy(refreshing = false) ?: ThreadUiState.Failed(e)
                else ThreadUiState.Failed(e)
            }
            (loaded.value as? ThreadUiState.Loaded)?.content?.takeIf { it.mayMissParent }?.let { findParent(it.post.post.id) }
            if (originId != null && loaded.value is ThreadUiState.Loaded) findOrigin(originId)
            unlockArticle()
            loadWriters()
        }
    }

    /**
     * Looks a web address up before anything else: replies are only served under a post's own ActivityPub id,
     * which the lookup returns. False when there is nothing to load. That is [unresolved] when the service
     * has no post for the address, and the error screen for what a browser wouldn't help with (offline, signed out).
     * A fedi.pub note link always ends on the error screen, which offers the link in the browser: its page there
     * is the note, so the screen says why the app couldn't show it rather than leaving for the browser unasked.
     */
    private suspend fun resolveAddress(): Boolean {
        if (!resolving) return true
        val fediPubNote = parseFediPubLink(postId) as? FediPubLink.Note
        try {
            val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
            val address = if (fediPubNote != null) fediPubNoteId(fediPubNote) else postId
            postId = activityPub.post(account.site, account.credential, address).id
            resolving = false
            return true
        } catch (e: SocialWebException) {
            if (fediPubNote != null || e.status == 401 || e.status == 403) loaded.value = ThreadUiState.Failed(e) else _unresolved.value = true
        } catch (e: GhostException) {
            loaded.value = ThreadUiState.Failed(e)
        } catch (e: IOException) {
            loaded.value = ThreadUiState.Failed(GhostException.Network(e))
        }
        return false
    }

    /**
     * The ActivityPub ID of the note a fedi.pub link names: the author's handle is looked up with WebFinger,
     * as the handle's domain needn't be where the site's ActivityPub lives, and the note's ID is built on the
     * actor's site. A handle nobody answers for is a note that can't be found.
     */
    private suspend fun fediPubNoteId(note: FediPubLink.Note): String {
        val actor = sites.actorId(note.handle) ?: throw SocialWebException(404, "No account for ${note.handle}")
        return ghostNoteId(actor, note.noteId) ?: throw SocialWebException(404, "No note ID for $actor")
    }

    /** Applies a parent found earlier this session, so a refresh doesn't drop and re-add it. */
    private fun withKnownParent(content: ThreadContent): ThreadContent =
        if (content.mayMissParent) replyParents.cached(content.post.post.id)?.let(content::withParent) ?: content else content

    /** Applies what the post's home server said earlier this session, so a refresh doesn't drop its replies until it answers again. */
    private fun withKnownOrigin(content: ThreadContent): ThreadContent {
        val known = origin ?: return content
        return (known.post?.let(content::withCounts) ?: content).withOrigin(known.replies)
    }

    /** Asks the post's home server for its counts and replies alongside the thread, so showing the thread isn't held up by it. */
    private fun findOrigin(id: String) {
        originJob?.cancel()
        originJob = viewModelScope.launch {
            val found = originThreads.load(id) ?: return@launch
            val replies = withContext(Dispatchers.Default) {
                found.replies.map { child ->
                    ReplyGroup(child.post.toFeedItem(), child.chain.map { it.toFeedItem() }, child.hasMore)
                }
            }
            origin = Origin(found.post, replies)
            loaded.update { state ->
                if (state is ThreadUiState.Loaded) state.copy(content = withKnownOrigin(state.content)) else state
            }
        }
    }

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

    /**
     * Looks up who wrote the article. The service sends the writers only with a single post, not with a thread, so
     * ask for the post on its own. It has none for an article from a Ghost site on another instance, which comes with
     * the site's account only: then they're read from the article's page. Looked up once per screen.
     */
    private fun loadWriters() {
        val post = (loaded.value as? ThreadUiState.Loaded)?.content?.post?.post ?: return
        if (!post.isArticle || post.ghostAuthors.isNotEmpty() || writersLookedUp) return
        writersLookedUp = true
        viewModelScope.launch {
            val fromService = try {
                val account = accounts.account.first() ?: return@launch
                activityPub.post(account.site, account.credential, post.id).metadata?.ghostAuthors.orEmpty()
            } catch (e: GhostException) {
                emptyList()
            } catch (e: SocialWebException) {
                emptyList()
            }
            val found = fromService.ifEmpty {
                // Not the publication's own name again.
                articleWriters.load(post.url).filterNot { it.name.equals(post.author.displayName.trim(), ignoreCase = true) }
            }
            if (found.isEmpty()) return@launch
            writers = PostMetadata(ghostAuthors = found)
            loaded.update { state -> if (state is ThreadUiState.Loaded) state.copy(content = withWriters(state.content)) else state }
        }
    }

    /** A refresh keeps the writers already looked up: the thread response doesn't carry them. */
    private fun withWriters(content: ThreadContent): ThreadContent {
        val metadata = writers ?: return content
        val item = content.post
        return if (item.post.ghostAuthors.isEmpty()) content.copy(post = item.copy(post = item.post.copy(metadata = metadata))) else content
    }

    /** Loads the next page of replies. */
    fun loadMore() {
        val current = loaded.value as? ThreadUiState.Loaded ?: return
        val next = current.content.next ?: return
        if (current.content.appending || loadJob?.isActive == true) return
        loaded.value = current.copy(content = current.content.copy(appending = true, appendFailed = false))
        loadJob = viewModelScope.launch {
            val page = try {
                fetch(postId, next).toContent(withArticle = false)
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
                    state.copy(content = withKnownOrigin(content.withPage(page)))
                }
            }
        }
    }

    /**
     * Loads the posts above the topmost ancestor shown, up to ten at a time, and puts them in front of it.
     * The service only answers for a post it has stored, so each step asks about the ancestor the last one ended at.
     */
    fun loadEarlier() {
        val current = loaded.value as? ThreadUiState.Loaded ?: return
        val content = current.content
        val top = content.ancestors.firstOrNull()?.post?.id ?: return
        if (!content.hasEarlier || content.loadingEarlier || current.refreshing) return
        loaded.value = current.copy(content = content.copy(loadingEarlier = true))
        earlierJob = viewModelScope.launch {
            val earlier = try {
                val ancestors = fetch(top, next = null).ancestors
                withContext(Dispatchers.Default) { ancestors.chain.map { it.toFeedItem() } } to ancestors.hasMore
            } catch (_: GhostException) {
                null
            } catch (_: SocialWebException) {
                null
            }
            loaded.update { state ->
                if (state !is ThreadUiState.Loaded) return@update state
                val updated = if (earlier == null) {
                    state.content.copy(loadingEarlier = false)
                } else {
                    state.content.withEarlier(earlier.first, earlier.second)
                }
                state.copy(content = updated)
            }
            if (earlier == null) earlierFailed.tryEmit(R.string.thread_earlier_failed)
        }
    }

    private suspend fun fetch(id: String, next: String?): ReplyChain {
        val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
        val chain = try {
            activityPub.replies(account.site, account.credential, id, next)
        } catch (e: SocialWebException) {
            // Threads are only served for posts the service has stored. One from elsewhere (Fediverse explore)
            // is stored by looking it up; a post that's gone fails that too and stays "no longer available".
            if (e.status != 404 || next != null) throw e
            val stored = try {
                activityPub.post(account.site, account.credential, id)
            } catch (_: SocialWebException) {
                throw e
            }
            postId = stored.id
            activityPub.replies(account.site, account.credential, stored.id, next = null)
        }
        // Explore's own count of the post, so the thread doesn't open on zeros where the list showed numbers.
        val counted = (explored[id] ?: explored[chain.post.id])?.let { chain.copy(post = chain.post.withCountsOf(it)) } ?: chain
        return counted.also { interactions.reconcile(it.allPosts()) }
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

/**
 * The post with the counts of [source] where those are higher, for a post the user's site knows by its likes and
 * reposts of its own: another server's count includes them. The flags of whether the user liked or reposted stay.
 */
private fun Post.withCountsOf(source: Post) = copy(
    likeCount = maxOf(likeCount, source.likeCount),
    replyCount = maxOf(replyCount, source.replyCount),
    repostCount = maxOf(repostCount, source.repostCount),
)
