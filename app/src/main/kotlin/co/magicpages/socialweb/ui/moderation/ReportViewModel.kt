package co.magicpages.socialweb.ui.moderation

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.Moderation
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.activitypub.serverOf
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.moderation.ServerContacts
import co.magicpages.socialweb.data.moderation.ServerRule
import co.magicpages.socialweb.data.moderation.ServerRules
import co.magicpages.socialweb.ui.quote.ellipsize
import co.magicpages.socialweb.ui.quote.htmlToPlainText
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.http.Url
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What is wrong, as Mastodon's report flow asks it; the strings are what the reader picks and what the email says. */
enum class ReportReason(@StringRes val title: Int, @StringRes val summary: Int) {
    /** Not a report: the reader only wants less of this account, which blocking and unfollowing do. */
    Dislike(R.string.report_reason_dislike, R.string.report_reason_dislike_summary),
    Spam(R.string.report_reason_spam, R.string.report_reason_spam_summary),
    Violation(R.string.report_reason_violation, R.string.report_reason_violation_summary),
    Illegal(R.string.report_reason_illegal, R.string.report_reason_illegal_summary),
    Other(R.string.report_reason_other, R.string.report_reason_other_summary),
}

/** The screens of the flow; which of them a report passes depends on the reason, see [ReportUiState.path]. */
enum class ReportStep { Reason, Personal, Rules, Posts, Comment, Done }

/** What the reader can do about the account besides reporting it. */
enum class PersonalAction { Unfollow, Block, BlockServer }

/** One of the account's posts to back the report up. [excerpt] is empty for posts without text. */
@Immutable
data class ReportPost(val id: String, val url: String, val excerpt: String, val millis: Long?)

sealed interface PostsStatus {
    data object Loading : PostsStatus
    data object Loaded : PostsStatus
    data class Failed(val error: Throwable) : PostsStatus
}

/** The reported server's moderator contact: [Pending] until it has been looked for. */
sealed interface ContactState {
    data object Pending : ContactState
    data object None : ContactState
    data class Found(val email: String) : ContactState
}

/** What is known of the reader's relation to the account; blocks and unfollows change it at once. */
data class Standing(
    val followed: Boolean = false,
    val blocked: Boolean = false,
    val serverBlocked: Boolean = false,
)

@Immutable
data class ReportUiState(
    val step: ReportStep = ReportStep.Reason,
    val reason: ReportReason? = null,
    /** Null until the server's rules have been asked for; empty when it publishes none. */
    val rules: List<ServerRule>? = null,
    val selectedRules: Set<String> = emptySet(),
    /** The reported post first, if the report is about one; then the account's other own posts. */
    val posts: List<ReportPost> = emptyList(),
    val postsStatus: PostsStatus = PostsStatus.Loading,
    val selectedPosts: Set<String> = emptySet(),
    val comment: String = "",
    /** Whether to copy the report to the server's moderators; only counts while [contact] is found. */
    val forward: Boolean = true,
    val contact: ContactState = ContactState.Pending,
    val standing: Standing = Standing(),
    val profileUrl: String? = null,
    val reporter: String? = null,
    val busy: PersonalAction? = null,
    /** Waiting for the server's rules before the flow can tell whether to ask about them. */
    val advancing: Boolean = false,
) {
    private val hasRules: Boolean get() = reason == ReportReason.Violation && !rules.isNullOrEmpty()

    /** The screens the reader passes with the current answers, in order. Dislike ends at [ReportStep.Personal]. */
    val path: List<ReportStep>
        get() = when {
            reason == ReportReason.Dislike -> listOf(ReportStep.Reason, ReportStep.Personal)
            hasRules -> listOf(ReportStep.Reason, ReportStep.Rules, ReportStep.Posts, ReportStep.Comment, ReportStep.Done)
            else -> listOf(ReportStep.Reason, ReportStep.Posts, ReportStep.Comment, ReportStep.Done)
        }
}

/**
 * The report flow for one account (or one of its posts). It keeps every answer, so the screens only
 * show them and a rotation loses nothing. Looking things up on the reported server (rules, moderator
 * contact) waits until the reader picks a reason that sends a report: "I don't like it" contacts nobody.
 */
@HiltViewModel(assistedFactory = ReportViewModel.Factory::class)
class ReportViewModel @AssistedInject constructor(
    @Assisted private val key: ReportKey,
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
    private val moderation: Moderation,
    private val contacts: ServerContacts,
    private val serverRules: ServerRules,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(key: ReportKey): ReportViewModel
    }

    /** The reported account's server, named after its handle like the post menu's server block. */
    val host: String = serverOf(key.handle)

    /** The reported post as far as the navigation knows it, so it stays listed and chosen even if its page can't load. */
    private val reportedStub: ReportPost? = key.postId?.let { id ->
        ReportPost(id, key.postUrl?.takeIf { it.isNotBlank() } ?: id, excerpt = "", millis = null)
    }

    private val _state = MutableStateFlow(
        ReportUiState(posts = listOfNotNull(reportedStub), selectedPosts = setOfNotNull(key.postId)),
    )
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** Snackbar texts (string resources) for actions the service refused. */
    val messages: Flow<Int> = _messages

    private var lookupsStarted = false
    private var rulesJob: Job? = null
    private var postsJob: Job? = null

    init {
        loadStanding()
        viewModelScope.launch {
            val handle = accounts.account.first()?.profile?.handle
            _state.update { it.copy(reporter = handle) }
        }
    }

    fun selectReason(reason: ReportReason) {
        _state.update { it.copy(reason = reason) }
        if (reason != ReportReason.Dislike) startLookups()
        if (reason == ReportReason.Violation) loadRules()
    }

    fun toggleRule(id: String) = _state.update { it.copy(selectedRules = it.selectedRules.toggled(id)) }

    fun togglePost(id: String) = _state.update { it.copy(selectedPosts = it.selectedPosts.toggled(id)) }

    fun setComment(text: String) = _state.update { it.copy(comment = text) }

    fun setForward(forward: Boolean) = _state.update { it.copy(forward = forward) }

    /** Moves on from the reason, rules or posts screen. The comment screen ends in sending, see [markSent]. */
    fun next() {
        val current = _state.value
        if (current.advancing) return
        if (current.step == ReportStep.Reason && current.reason == ReportReason.Violation && current.rules == null) {
            // Whether there is a rules screen depends on what the server answers.
            _state.update { it.copy(advancing = true) }
            viewModelScope.launch {
                rulesJob?.join()
                _state.update { it.copy(advancing = false) }
                advance()
            }
            return
        }
        advance()
    }

    /** Goes back one screen; false when there is none to go back to, so the caller leaves the flow. */
    fun back(): Boolean {
        var moved = false
        _state.update { current ->
            val path = current.path
            val index = path.indexOf(current.step)
            if (current.step == ReportStep.Done || index <= 0) {
                current
            } else {
                moved = true
                current.copy(step = path[index - 1])
            }
        }
        return moved
    }

    /** The report has been handed to a mail app (or copied): on to the thanks and what else can be done. */
    fun markSent() = _state.update { it.copy(step = ReportStep.Done) }

    fun retryPosts() = loadPosts()

    /** Everything the email needs, or null while no reason is chosen. */
    fun draft(): ReportDraft? {
        val current = _state.value
        val reason = current.reason ?: return null
        val forwardTo = (current.contact as? ContactState.Found)?.email?.takeIf { current.forward }
        return ReportDraft(
            handle = key.handle,
            profileUrl = current.profileUrl ?: key.accountId.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: key.handle,
            aboutPost = key.postId != null,
            reason = reason,
            rules = if (reason == ReportReason.Violation) {
                current.rules.orEmpty().filter { it.id in current.selectedRules }.map { it.text }
            } else {
                emptyList()
            },
            postUrls = current.posts.filter { it.id in current.selectedPosts }.map { it.url },
            comment = current.comment.trim(),
            reporter = current.reporter,
            forwardTo = forwardTo,
            forwardHost = host,
        )
    }

    fun unfollow() = act(PersonalAction.Unfollow, R.string.follow_failed, {
        val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
        client.unfollow(account.site, account.credential, key.handle)
    }) { it.copy(followed = false) }

    fun block() = act(PersonalAction.Block, R.string.block_failed, { moderation.block(key.handle, key.accountId) }) {
        // The service ends follows both ways with a block.
        it.copy(blocked = true, followed = false)
    }

    fun blockServer() = act(PersonalAction.BlockServer, R.string.server_block_failed, { moderation.blockServer(host) }) {
        it.copy(serverBlocked = true, followed = false)
    }

    private fun advance() {
        _state.update { current ->
            val path = current.path
            val index = path.indexOf(current.step)
            if (index < 0 || index == path.lastIndex) current else current.copy(step = path[index + 1])
        }
    }

    private fun act(action: PersonalAction, @StringRes failure: Int, call: suspend () -> Unit, change: (Standing) -> Standing) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = action) }
        viewModelScope.launch {
            try {
                call()
                _state.update { it.copy(standing = change(it.standing)) }
            } catch (_: GhostException) {
                _messages.emit(failure)
            } catch (_: SocialWebException) {
                _messages.emit(failure)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /** The reader's relation to the account; without it (offline, not found) blocking is still offered. */
    private fun loadStanding() {
        viewModelScope.launch {
            try {
                val account = accounts.account.first() ?: return@launch
                val profile = client.profile(account.site, account.credential, key.handle)
                _state.update {
                    it.copy(
                        standing = Standing(
                            followed = profile.followedByMe,
                            blocked = profile.blockedByMe || it.standing.blocked,
                            serverBlocked = profile.domainBlockedByMe || it.standing.serverBlocked,
                        ),
                        profileUrl = profile.url?.takeIf { url -> url.isNotBlank() },
                    )
                }
            } catch (_: GhostException) {
                // The actions below still work without knowing whether the account is followed.
            } catch (_: SocialWebException) {
            }
        }
    }

    /** What a report that gets sent needs from the account's server and from the service, once. */
    private fun startLookups() {
        if (lookupsStarted) return
        lookupsStarted = true
        viewModelScope.launch {
            val email = contacts.moderatorEmail(host)
            _state.update { it.copy(contact = if (email != null) ContactState.Found(email) else ContactState.None) }
        }
        loadPosts()
    }

    private fun loadRules() {
        if (rulesJob != null) return
        rulesJob = viewModelScope.launch {
            val rules = serverRules.rules(host)
            _state.update { it.copy(rules = rules) }
        }
    }

    /** The account's latest own posts (not what it reposted); the reported post leads the list. */
    private fun loadPosts() {
        postsJob?.cancel()
        _state.update { it.copy(postsStatus = PostsStatus.Loading) }
        postsJob = viewModelScope.launch {
            try {
                val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
                val own = client.postsBy(account.site, account.credential, key.handle).posts
                    .filter { it.repostedBy == null && !it.isDeleted }
                val reported = key.postId?.let { id ->
                    own.firstOrNull { it.id == id } ?: try {
                        client.post(account.site, account.credential, id)
                    } catch (_: GhostException) {
                        null
                    } catch (_: SocialWebException) {
                        null
                    }
                }
                val items = withContext(Dispatchers.Default) {
                    val others = own.filter { it.id != key.postId }.map { it.toReportPost() }
                    listOfNotNull(reported?.toReportPost() ?: reportedStub) + others
                }
                _state.update { it.copy(posts = items, postsStatus = PostsStatus.Loaded) }
            } catch (e: GhostException) {
                _state.update { it.copy(postsStatus = PostsStatus.Failed(e)) }
            } catch (e: SocialWebException) {
                _state.update { it.copy(postsStatus = PostsStatus.Failed(e)) }
            }
        }
    }
}

private fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id

/** A post as a line to pick: its words (an article's title), a link to it, and when it was posted. */
private fun Post.toReportPost(): ReportPost {
    val text = if (isArticle) title.ifBlank { excerpt } else htmlToPlainText(content)
    return ReportPost(
        id = id,
        url = url.ifBlank { id },
        excerpt = ellipsize(text.replace(Regex("\\s+"), " ").trim(), 160),
        millis = publishedAt?.let {
            try {
                Instant.parse(it).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        },
    )
}
