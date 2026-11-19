package co.magicpages.socialweb.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Moderation
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One list of the screen, loaded on its own so a failure in one doesn't hide the other. */
sealed interface BlockedList<out T> {
    data object Loading : BlockedList<Nothing>

    data class Failed(val error: Throwable) : BlockedList<Nothing>

    data class Loaded<T>(val items: List<T>) : BlockedList<T>
}

/** What can be unblocked; also what a failed unblock offers to retry. */
sealed interface UnblockTarget {
    /** The key that tells busy rows apart. */
    val key: String

    data class Account(val author: Author) : UnblockTarget {
        override val key: String get() = author.id
    }

    data class Server(val host: String) : UnblockTarget {
        override val key: String get() = host
    }
}

/** A one-off result for the snackbar. */
sealed interface BlockedNotice {
    data class Unblocked(val label: String) : BlockedNotice

    data class Failed(val error: Throwable, val target: UnblockTarget) : BlockedNotice
}

data class BlockedUi(
    val accounts: BlockedList<Author> = BlockedList.Loading,
    val servers: BlockedList<String> = BlockedList.Loading,
    /** Keys of the rows whose unblock is under way. */
    val busy: Set<String> = emptySet(),
    val notice: BlockedNotice? = null,
)

/**
 * The active account's blocked accounts and servers. The screen belongs to one account session
 * (the navigation entries are keyed per session), so another account gets a fresh instance
 * rather than this one reloading.
 */
@HiltViewModel
class BlockedViewModel @Inject constructor(
    private val moderation: Moderation,
) : ViewModel() {
    private val _ui = MutableStateFlow(BlockedUi())
    val ui: StateFlow<BlockedUi> = _ui.asStateFlow()

    private var accountsJob: Job? = null
    private var serversJob: Job? = null

    /**
     * Loads both lists, and again each time the screen resumes: a profile opened from here may have
     * lifted a block. A list already shown stays while it reloads, and stays if the reload fails.
     */
    fun refresh() {
        loadAccounts()
        loadServers()
    }

    fun loadAccounts() {
        accountsJob?.cancel()
        _ui.update { if (it.accounts is BlockedList.Loaded) it else it.copy(accounts = BlockedList.Loading) }
        accountsJob = viewModelScope.launch {
            val result: BlockedList<Author> = try {
                BlockedList.Loaded(moderation.blockedAccounts())
            } catch (e: GhostException) {
                BlockedList.Failed(e)
            } catch (e: SocialWebException) {
                BlockedList.Failed(e)
            }
            _ui.update { if (result is BlockedList.Failed && it.accounts is BlockedList.Loaded) it else it.copy(accounts = result) }
        }
    }

    fun loadServers() {
        serversJob?.cancel()
        _ui.update { if (it.servers is BlockedList.Loaded) it else it.copy(servers = BlockedList.Loading) }
        serversJob = viewModelScope.launch {
            val result: BlockedList<String> = try {
                BlockedList.Loaded(moderation.blockedServers())
            } catch (e: GhostException) {
                BlockedList.Failed(e)
            } catch (e: SocialWebException) {
                BlockedList.Failed(e)
            }
            _ui.update { if (result is BlockedList.Failed && it.servers is BlockedList.Loaded) it else it.copy(servers = result) }
        }
    }

    /** Lifts the block on [target]; its row goes once the service has done it. */
    fun unblock(target: UnblockTarget) {
        if (target.key in _ui.value.busy) return
        _ui.update { it.copy(busy = it.busy + target.key) }
        viewModelScope.launch {
            val failure = try {
                when (target) {
                    is UnblockTarget.Account -> moderation.unblock(target.author.handle, target.author.id)
                    is UnblockTarget.Server -> moderation.unblockServer(target.host)
                }
                null
            } catch (e: GhostException) {
                e
            } catch (e: SocialWebException) {
                e
            }
            _ui.update { state ->
                val settled = state.copy(busy = state.busy - target.key)
                if (failure != null) {
                    return@update settled.copy(notice = BlockedNotice.Failed(failure, target))
                }
                when (target) {
                    is UnblockTarget.Account -> settled.copy(
                        accounts = (settled.accounts as? BlockedList.Loaded)
                            ?.let { BlockedList.Loaded(it.items.filterNot { a -> a.id == target.author.id }) }
                            ?: settled.accounts,
                        notice = BlockedNotice.Unblocked(target.author.name.ifBlank { target.author.handle }),
                    )
                    is UnblockTarget.Server -> settled.copy(
                        servers = (settled.servers as? BlockedList.Loaded)
                            ?.let { BlockedList.Loaded(it.items.filterNot { h -> h == target.host }) }
                            ?: settled.servers,
                        notice = BlockedNotice.Unblocked(target.host),
                    )
                }
            }
        }
    }

    /** The snackbar for [notice] is done; a newer notice that replaced it stays. */
    fun noticeShown(notice: BlockedNotice) {
        _ui.update { if (it.notice === notice) it.copy(notice = null) else it }
    }
}
