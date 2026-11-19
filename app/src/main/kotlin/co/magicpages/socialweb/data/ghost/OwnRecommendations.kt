package co.magicpages.socialweb.data.ghost

import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The signed-in site's own recommendations, read through the Admin API, and the changes to them. Shared by
 * every profile so the Recommend button of one reflects what another did.
 */
@Singleton
class OwnRecommendations @Inject constructor(
    private val accounts: AccountStore,
    private val ghost: GhostAdminClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _list = MutableStateFlow<List<AdminRecommendation>?>(null)

    /** Null until loaded (or after the active account changed or signed out). */
    val list: StateFlow<List<AdminRecommendation>?> = _list.asStateFlow()

    init {
        // Another site's recommendations must never show for this one.
        scope.launch { accounts.activeId.collect { _list.value = null } }
    }

    /** Reads the list again. Throws [GhostException] when Ghost refuses or can't be reached. */
    suspend fun refresh() {
        val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
        val list = ghost.recommendations(account.site, account.credential)
        // The answer belongs to the account asked, not to one that became active meanwhile.
        if (accounts.activeId.first() == account.id) _list.value = list
    }

    /** Adds [siteUrl] to the recommendations. Runs to the end even if the caller goes away. */
    suspend fun recommend(siteUrl: String) {
        scope.async {
            val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
            val added = ghost.recommend(account.site, account.credential, siteUrl)
            if (accounts.activeId.first() == account.id) {
                _list.update { list -> list?.let { current -> current.filterNot { it.id == added.id } + added } ?: listOf(added) }
            }
        }.await()
    }

    /** Removes [siteUrl] from the recommendations; nothing happens when it isn't in them. */
    suspend fun remove(siteUrl: String) {
        scope.async {
            val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
            if (_list.value == null) refresh()
            val ids = _list.value.orEmpty().filter { it.matches(siteUrl) }.mapNotNull { it.id }
            for (id in ids) ghost.removeRecommendation(account.site, account.credential, id)
            if (accounts.activeId.first() == account.id) _list.update { list -> list?.filterNot { it.id in ids } }
        }.await()
    }
}

/** Whether [siteUrl] is one of these recommendations. */
fun List<AdminRecommendation>.recommends(siteUrl: String): Boolean = any { it.matches(siteUrl) }

private fun AdminRecommendation.matches(siteUrl: String): Boolean = url?.let { isSameSite(it, siteUrl) } == true
