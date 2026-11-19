package co.magicpages.socialweb.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.FollowList
import co.magicpages.socialweb.data.activitypub.FollowsPage
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest

/** Followers and following of one account, one tab each. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = FollowsViewModel.Factory::class)
class FollowsViewModel @AssistedInject constructor(
    /** `@user@domain`, or `me`. */
    @Assisted private val handle: String,
    @Assisted initial: FollowList,
    private val accounts: AccountStore,
    private val activityPub: ActivityPubClient,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(handle: String, initial: FollowList): FollowsViewModel
    }

    private val _list = MutableStateFlow(initial)
    val list: StateFlow<FollowList> = _list.asStateFlow()

    // One pager per tab, so switching back doesn't reload what was already fetched.
    private val pages = FollowList.entries.associateWith { list ->
        Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
            AccountsPagingSource { next ->
                val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
                activityPub.follows(account.site, account.credential, handle, list, next)
            }
        }.flow.cachedIn(viewModelScope)
    }

    val accountsList: Flow<PagingData<Author>> = _list.flatMapLatest { pages.getValue(it) }

    fun select(list: FollowList) {
        _list.value = list
    }
}

/** Pages through a follows list with the server's `next` cursor, each account once. */
private class AccountsPagingSource(
    private val loadPage: suspend (next: String?) -> FollowsPage,
) : PagingSource<String, Author>() {
    // Lists can shift while paging (someone follows mid-scroll); LazyColumn keys must stay unique.
    private val seen = HashSet<String>()

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Author> = try {
        val page = loadPage(params.key)
        LoadResult.Page(data = page.accounts.filter { seen.add(it.handle) }, prevKey = null, nextKey = page.next)
    } catch (e: GhostException) {
        LoadResult.Error(e)
    } catch (e: SocialWebException) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<String, Author>): String? = null
}
