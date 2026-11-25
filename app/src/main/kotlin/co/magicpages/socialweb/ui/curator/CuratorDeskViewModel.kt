package co.magicpages.socialweb.ui.curator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.PostInteractions
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.activitypub.Topic
import co.magicpages.socialweb.data.curator.CuratorDesk
import co.magicpages.socialweb.data.curator.CuratorRepostsBeforeThought
import co.magicpages.socialweb.data.curator.CuratorState
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.feed.NoteComposer
import co.magicpages.socialweb.ui.quote.NoteSeed
import co.magicpages.socialweb.ui.quote.QuoteCardPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the curator desk card shows. */
sealed interface CuratorUi {
    /** Nothing: not an owner or administrator, nothing to show today, sent away, or still loading. */
    data object Hidden : CuratorUi

    /**
     * The choice of topics, one to three of [topics]. [selected] is what to start with: nothing the first time, the
     * current topics when [editing] them.
     */
    data class Choose(val topics: List<Topic>, val selected: Set<String>, val editing: Boolean) : CuratorUi

    /**
     * The post at [index] of [total] (counting from 0). [topicName] is the one topic the writer picked, or null for
     * several; [fellBack] says the post is from the default topic because theirs had nothing new. [thoughtFirst] is
     * the anti-spam guard: enough reposts today, so a thought is what the card asks for.
     */
    data class Review(
        val post: Post,
        val index: Int,
        val total: Int,
        val topicName: String?,
        val fellBack: Boolean,
        val thoughtFirst: Boolean,
    ) : CuratorUi

    /** The day is through. */
    data object Finished : CuratorUi
}

/**
 * Behind the curator desk card at the top of the Reader: loads what the desk needs for today, and turns its state
 * into [ui]. Whatever goes wrong while loading (no network, a role that has no desk) leaves the card out; it never
 * shows an error, since it is an offer and not something the writer asked for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CuratorDeskViewModel @Inject constructor(
    private val accounts: AccountStore,
    private val desk: CuratorDesk,
    private val interactions: PostInteractions,
    private val noteComposer: NoteComposer,
) : ViewModel() {
    private val today = MutableStateFlow(LocalDate.now())
    private val editing = MutableStateFlow(false)

    /** The topics the active account can choose from, with its id; null until loaded. */
    private val choices = MutableStateFlow<Pair<String, List<Topic>>?>(null)

    /** The accounts the service refused (403): the card stays away for them. */
    private val forbidden = MutableStateFlow<Set<String>>(emptySet())

    private var loading: Job? = null

    /** The account and day whose unfinished posts this session has shown, so only the day finished here is told so. */
    private var sawOpenDay: String? = null

    val ui: StateFlow<CuratorUi> = accounts.account
        .filterNotNull()
        .distinctUntilChangedBy { it.id }
        .flatMapLatest { account ->
            combine(desk.state(account.id), today, editing, choices, forbidden) { state, date, editing, choices, forbidden ->
                if (account.id in forbidden) {
                    CuratorUi.Hidden
                } else {
                    compute(account, state, date, editing, choices?.takeIf { it.first == account.id }?.second.orEmpty())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CuratorUi.Hidden)

    init {
        accounts.account.filterNotNull().distinctUntilChangedBy { it.id }.onEach {
            editing.value = false
            load(force = true)
        }.launchIn(viewModelScope)
    }

    private fun compute(account: StoredAccount, state: CuratorState, date: LocalDate, editing: Boolean, choices: List<Topic>): CuratorUi {
        val today = date.toString()
        val choosing = state.topics.isEmpty() || (editing && choices.isNotEmpty())
        if (choosing) {
            if (choices.isEmpty() || (!editing && state.hiddenOn == today)) return CuratorUi.Hidden
            return CuratorUi.Choose(choices, state.topics.mapTo(linkedSetOf()) { it.slug }, editing = state.topics.isNotEmpty())
        }
        if (state.hiddenOn == today) return CuratorUi.Hidden
        val day = state.day?.takeIf { it.date == today } ?: return CuratorUi.Hidden
        val picks = day.picks?.takeIf { it.isNotEmpty() } ?: return CuratorUi.Hidden
        val key = "${account.id}/$today"
        if (day.finished) return if (sawOpenDay == key) CuratorUi.Finished else CuratorUi.Hidden
        sawOpenDay = key
        return CuratorUi.Review(
            post = picks[day.handled],
            index = day.handled,
            total = picks.size,
            topicName = state.topics.singleOrNull()?.name,
            fellBack = day.fellBack,
            thoughtFirst = day.reposts >= CuratorRepostsBeforeThought,
        )
    }

    /** Called when the Reader comes to the front: a new day gets its posts, and what failed before is tried again. */
    fun refresh() {
        today.value = LocalDate.now()
        load(force = false)
    }

    private fun load(force: Boolean) {
        if (!force && loading?.isActive == true) return
        loading?.cancel()
        loading = viewModelScope.launch {
            val account = accounts.account.first() ?: return@launch
            if (account.id in forbidden.value) return@launch
            guarded(account) {
                if (desk.state(account.id).first().topics.isEmpty()) loadChoices(account) else desk.prepare(account, today.value)
            }
        }
    }

    private suspend fun loadChoices(account: StoredAccount) {
        if (choices.value?.first == account.id) return
        choices.value = account.id to desk.availableTopics(account)
    }

    /** Runs [block], leaving the card out for an account the service refuses and ignoring any other failure. */
    private suspend fun guarded(account: StoredAccount, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: SocialWebException) {
        if (e.status == 403) forbidden.update { it + account.id }
        false
    } catch (_: GhostException) {
        false
    }

    /** Opens the choice of topics again, from the card's menu. */
    fun editTopics() {
        editing.value = true
        viewModelScope.launch {
            val account = accounts.account.first() ?: return@launch
            // The card stays as it is until the choices are here; if they can't come, it stays that way.
            if (!guarded(account) { loadChoices(account) }) editing.value = false
        }
    }

    fun cancelEditing() {
        editing.value = false
    }

    /** Keeps [topics] as what the writer writes about, and has today's posts picked from them. */
    fun saveTopics(topics: List<Topic>) {
        viewModelScope.launch {
            val account = accounts.account.first() ?: return@launch
            desk.chooseTopics(account.id, topics)
            editing.value = false
            load(force = true)
        }
    }

    /** Sends the desk away without a choice, until tomorrow. */
    fun notNow() = act { desk.hide(it, today.value) }

    /** Reposts [post], the one at [index], and moves on. A post already reposted elsewhere is only moved past. */
    fun repost(post: Post, index: Int) = act { id ->
        val already = interactions.overrides.value[post.id]?.repostedByMe ?: post.repostedByMe
        if (!already) interactions.toggleRepost(post)
        desk.advance(id, index, reposted = !already)
    }

    /** Opens a note to answer [post], the one at [index]: the writer's words first, then its author and link. */
    fun addThought(post: Post, index: Int, palette: QuoteCardPalette) {
        noteComposer.startWith(NoteSeed(text = thoughtText(post), card = null), palette)
        act { desk.advance(it, index, reposted = false) }
    }

    fun skip(index: Int) = act { desk.advance(it, index, reposted = false) }

    fun skipAll() = act { desk.skipAll(it) }

    /** Moves past the post at [index] and keeps its site off the desk from now on. */
    fun hideSite(index: Int) = act { desk.hideSite(it, index) }

    /** Sends the finished desk away until tomorrow. */
    fun hide() = act { desk.hide(it, today.value) }

    /** The day's end has been on show: the desk won't come back with it today. */
    fun acknowledgeFinished() = act { desk.acknowledgeFinished(it, today.value) }

    private fun act(block: suspend (accountId: String) -> Unit) {
        viewModelScope.launch {
            val id = accounts.account.first()?.id ?: return@launch
            block(id)
        }
    }
}

/**
 * What a note about [post] starts with: an empty first line for the writer's own words (which the composer puts the
 * caret on), then the author as a mention and the link.
 */
internal fun thoughtText(post: Post): String {
    val mention = post.author.handle.trim().removePrefix("@").takeIf { it.isNotEmpty() }?.let { "@$it" }
    val link = post.shareUrl?.trim()?.takeIf { it.isNotEmpty() }
    return "\n\n" + listOfNotNull(mention, link).joinToString(" ")
}
