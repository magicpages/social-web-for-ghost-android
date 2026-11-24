package co.magicpages.socialweb.ui.feed

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import co.magicpages.socialweb.ui.curator.CuratorDeskCard
import co.magicpages.socialweb.ui.curator.CuratorDeskEffects
import co.magicpages.socialweb.ui.curator.CuratorDeskViewModel
import co.magicpages.socialweb.ui.posts.CollectMessages
import co.magicpages.socialweb.ui.posts.PostCallbacks
import co.magicpages.socialweb.ui.posts.ReplyEditorHost
import co.magicpages.socialweb.ui.posts.feedItems
import co.magicpages.socialweb.ui.posts.rememberReaderPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A list that has been loaded this long ago is loaded again when the user comes back to it. */
private const val StaleAfterMillis = 5 * 60_000L

/**
 * One timeline tab. [reselections] counts taps on its already-selected tab, each a request to
 * scroll back to the top; [accountMenu] sits at the end of the top bar. The Reader shows its
 * timeline and a page for each of Ghost's topics, side by side under chips.
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
    val viewModel = hiltViewModel<FeedViewModel, FeedViewModel.Factory>(key = feed.name) { it.create(feed, null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    // The Reader's own: a few posts a day from the topics the writer picked, above its timeline.
    val curator = if (feed == Feed.Reader) hiltViewModel<CuratorDeskViewModel>() else null
    if (curator != null) CuratorDeskEffects(curator)
    val deskHeader: (@Composable () -> Unit)? = if (curator == null) null else {
        { CuratorDeskCard(curator, onOpenPost, onOpenProfile) }
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
        if (feed == Feed.Reader) {
            ReaderPager(Modifier.fillMaxSize().padding(padding)) { topic, current, onLoaded ->
                // Each topic has a view model and list of its own, so a page is where it was left when swiped back to.
                val topicModel = topic?.let {
                    hiltViewModel<FeedViewModel, FeedViewModel.Factory>(key = "${feed.name}:${it.slug}") { factory ->
                        factory.create(feed, it.slug)
                    }
                }
                FeedPage(
                    viewModel = topicModel ?: viewModel,
                    feed = feed,
                    account = account,
                    listState = if (topic == null) listState else rememberLazyListState(),
                    current = current,
                    reselections = reselections,
                    emptyMessage = if (topic == null) R.string.reader_empty else R.string.reader_topic_empty,
                    snackbar = snackbar,
                    onOpenPost = onOpenPost,
                    onOpenProfile = onOpenProfile,
                    onSignOut = onSignOut,
                    onLoaded = onLoaded,
                    // Only the timeline has it; a topic's page is that topic.
                    header = if (topic == null) deskHeader else null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            FeedPage(
                viewModel = viewModel,
                feed = feed,
                account = account,
                listState = listState,
                current = true,
                reselections = reselections,
                emptyMessage = R.string.notes_empty,
                snackbar = snackbar,
                onOpenPost = onOpenPost,
                onOpenProfile = onOpenProfile,
                onSignOut = onSignOut,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

/**
 * One list of a timeline tab, with its pull to refresh, empty and error states, and its reply editor.
 * Only the [current] list scrolls back to the top on [reselections]; [onLoaded] is called each time
 * the list has finished loading.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FeedPage(
    viewModel: FeedViewModel,
    feed: Feed,
    account: StoredAccount?,
    listState: LazyListState,
    current: Boolean,
    reselections: Int,
    @StringRes emptyMessage: Int,
    snackbar: SnackbarHostState,
    onOpenPost: (Post) -> Unit,
    onOpenProfile: (Author) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    onLoaded: () -> Unit = {},
    header: (@Composable () -> Unit)? = null,
) {
    val posts = viewModel.posts.collectAsLazyPagingItems()
    val filtering by viewModel.filtering.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Composed only while current, so a page swiped to later doesn't take the taps made while it was away for its own.
    if (current) OnReselect(reselections) { listState.scrollToTop() }
    CollectMessages(viewModel.messages, snackbar)
    val newPosts = rememberNewPosts(viewModel, account?.id, posts, listState)
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
    // Remember when the list was last up to date, and reload one that went stale while the app was away.
    val loaded by rememberUpdatedState(onLoaded)
    LaunchedEffect(posts) {
        snapshotFlow { posts.loadState.refresh }.collect {
            if (it is LoadState.NotLoading) {
                viewModel.lastRefreshed = SystemClock.elapsedRealtime()
                loaded()
            }
        }
    }
    LifecycleResumeEffect(posts) {
        val stale = SystemClock.elapsedRealtime() - viewModel.lastRefreshed > StaleAfterMillis
        if (stale && viewModel.positionRestored && posts.loadState.refresh !is LoadState.Loading) posts.refresh()
        onPauseOrDispose {}
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

    Box(modifier) {
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
            modifier = Modifier.fillMaxSize(),
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
                    stringResource(if (filtering) R.string.feed_filtered_empty else emptyMessage),
                    header = header,
                )
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val reader = if (feed == Feed.Reader) rememberReaderPage(posts, maxWidth, maxHeight) else null
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        feedItems(posts, callbacks, reader = reader, caughtUpAbove = newPosts.caughtUpAbove, header = header)
                    }
                }
            }
            NewPostsPill(
                count = newPosts.count,
                onClick = { scope.launch { listState.scrollToTop() } },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            )
        }
        ReplyEditorHost(viewModel.composer)
    }
}
