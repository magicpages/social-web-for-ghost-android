package co.magicpages.socialweb.ui.feed

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.CenteredLoading
import co.magicpages.socialweb.ui.components.FullScreenError
import co.magicpages.socialweb.ui.components.OnReselect
import co.magicpages.socialweb.ui.components.ScrollableMessage
import co.magicpages.socialweb.ui.components.errorMessage
import co.magicpages.socialweb.ui.components.scrollToTop
import co.magicpages.socialweb.ui.posts.CollectMessages
import co.magicpages.socialweb.ui.posts.PostCallbacks
import co.magicpages.socialweb.ui.posts.ReplyEditorHost
import co.magicpages.socialweb.ui.posts.feedItems
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One timeline tab. [reselections] counts taps on its already-selected tab, each a request to
 * scroll back to the top; [accountMenu] sits at the end of the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FeedScreen(
    feed: Feed,
    account: StoredAccount?,
    reselections: Int,
    onOpenPost: (Post) -> Unit,
    onOpenProfile: (Author) -> Unit,
    onSignOut: () -> Unit,
    accountMenu: @Composable () -> Unit,
) {
    val viewModel = hiltViewModel<FeedViewModel, FeedViewModel.Factory>(key = feed.name) { it.create(feed) }
    val posts = viewModel.posts.collectAsLazyPagingItems()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    OnReselect(reselections) { listState.scrollToTop() }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    // A fresh note belongs at the top of the Notes list, which the server has but we don't yet.
    // It arrives above the first visible post, and the list keeps that post in place: scroll up
    // only once the refresh has landed.
    LaunchedEffect(viewModel) {
        // The one composer is shared with the Reader's screen, which has no notes to refresh.
        if (feed != Feed.Notes) return@LaunchedEffect
        viewModel.noteComposer.posted.collect {
            posts.refresh()
            withTimeoutOrNull(1_000) { snapshotFlow { posts.loadState.refresh }.first { it is LoadState.Loading } }
            snapshotFlow { posts.loadState.refresh }.first { it !is LoadState.Loading }
            listState.scrollToTop()
        }
    }
    val callbacks = remember(viewModel) {
        PostCallbacks(
            open = onOpenPost,
            openProfile = onOpenProfile,
            reply = viewModel.composer::start,
            toggleLike = viewModel::toggleLike,
            toggleRepost = viewModel::toggleRepost,
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (feed == Feed.Notes) {
                val label = stringResource(R.string.note_new)
                ExtendedFloatingActionButton(
                    text = { Text(label) },
                    icon = { Icon(AppIcons.Edit, contentDescription = null) },
                    onClick = viewModel.noteComposer::start,
                    expanded = !listState.canScrollBackward,
                    // Its text doesn't reach the accessibility tree (expanded or not), so TalkBack had no name for it.
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (feed) {
                                Feed.Reader -> R.string.reader_title
                                Feed.Notes -> R.string.notes_title
                            },
                        ),
                    )
                },
                subtitle = { account?.let { Text(it.site.title) } },
                actions = { accountMenu() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val refresh = posts.loadState.refresh
        val refreshing = refresh is LoadState.Loading && posts.itemCount > 0
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = posts::refresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(pullState, refreshing, Modifier.align(Alignment.TopCenter))
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                refresh is LoadState.Error && posts.itemCount == 0 -> FullScreenError(
                    errorMessage(refresh.error),
                    onRetry = posts::retry,
                    sessionEnded = refresh.error is GhostException.Unauthorized,
                    onSignOut = onSignOut,
                )
                refresh is LoadState.Loading && posts.itemCount == 0 -> CenteredLoading()
                refresh is LoadState.NotLoading && posts.itemCount == 0 -> ScrollableMessage(
                    stringResource(
                        when (feed) {
                            Feed.Reader -> R.string.reader_empty
                            Feed.Notes -> R.string.notes_empty
                        },
                    ),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    feedItems(posts, callbacks, articleCards = feed == Feed.Reader)
                }
            }
        }
    }
    ReplyEditorHost(viewModel.composer)
}
