package co.magicpages.socialweb.ui.explore

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.AppendState
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.CenteredLoading
import co.magicpages.socialweb.ui.components.EmojiText
import co.magicpages.socialweb.ui.components.FullScreenError
import co.magicpages.socialweb.ui.components.OnReselect
import co.magicpages.socialweb.ui.components.ScrollableMessage
import co.magicpages.socialweb.ui.components.emojiSourceId
import co.magicpages.socialweb.ui.components.errorMessage
import co.magicpages.socialweb.ui.components.bioHtml
import co.magicpages.socialweb.ui.components.parsePostHtml
import co.magicpages.socialweb.ui.components.scrollToTop
import co.magicpages.socialweb.ui.posts.CollectMessages
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.posts.displayName
import co.magicpages.socialweb.ui.theme.Gutter
import kotlinx.coroutines.launch
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.ui.posts.PostCallbacks
import co.magicpages.socialweb.ui.posts.ReplyEditorHost
import co.magicpages.socialweb.ui.posts.feedItems
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.paging.compose.LazyPagingItems
import co.magicpages.socialweb.data.fediverse.FediverseTrends
import co.magicpages.socialweb.data.fediverse.TrendingLink
import co.magicpages.socialweb.data.fediverse.TrendingTag
import co.magicpages.socialweb.ui.posts.FeedItem
import co.magicpages.socialweb.ui.settings.FediverseServerPicker
import co.magicpages.socialweb.ui.settings.fediverseServerLabel
import coil3.compose.AsyncImage
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.text.NumberFormat

/**
 * The Explore tab: the search for accounts, which sits where the other tabs have their title and
 * opens over the whole screen, and below it the accounts the service suggests, for the site or per
 * topic. [reselections] counts taps on its already-selected tab: each scrolls back to the top, or,
 * when already there, opens the search. [accountMenu] sits at the end of the top bar.
 *
 * The open search is a window of its own, which can't be on screen while a back gesture previews
 * this tab under a profile opened from it. So for as long as such a profile is open, [onCover] is
 * handed a copy of the open search, drawn in the app's window over the whole tab, navigation bar
 * included; back here, the search opens over it and the copy goes. Null takes the cover away.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ExploreScreen(
    reselections: Int,
    onOpenPost: (Post) -> Unit,
    onOpenProfile: (Author) -> Unit,
    onSignOut: () -> Unit,
    accountMenu: @Composable () -> Unit,
    onCover: (cover: (@Composable () -> Unit)?) -> Unit,
    onChangeFediverseSource: () -> Unit,
) {
    val viewModel = hiltViewModel<ExploreViewModel>()
    val suggestions = viewModel.suggestions.collectAsLazyPagingItems()
    val topics by viewModel.topics.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val followingState = viewModel.following.collectAsStateWithLifecycle()
    val following by followingState
    val topicPosts = viewModel.topicPosts.collectAsLazyPagingItems()
    val topicView by viewModel.topicView.collectAsStateWithLifecycle()
    val fediverse by viewModel.fediverse.collectAsStateWithLifecycle()
    val fediverseSelected by viewModel.fediverseSelected.collectAsStateWithLifecycle()
    val fediverseTag by viewModel.fediverseTag.collectAsStateWithLifecycle()
    val fediversePosts = viewModel.fediversePosts.collectAsLazyPagingItems()
    val fediverseTrends by viewModel.fediverseTrends.collectAsStateWithLifecycle()
    var serverSheet by rememberSaveable { mutableStateOf(false) }
    // Back steps out of a hashtag to the trending posts before it leaves Explore.
    BackHandler(enabled = fediverseSelected && fediverseTag != null) { viewModel.openTag(null) }
    val showPosts = !fediverseSelected && selected != null && topicView == TopicView.Posts
    // The list on screen: a topic's or the server's posts, or else the suggested accounts.
    val posts = if (fediverseSelected) fediversePosts else topicPosts
    val callbacks = remember(viewModel) {
        PostCallbacks(
            open = onOpenPost,
            openProfile = onOpenProfile,
            reply = viewModel.composer::start,
            toggleLike = viewModel::toggleLike,
            toggleRepost = viewModel::toggleRepost,
        )
    }
    val searchState = viewModel.search.collectAsStateWithLifecycle()
    val search by searchState

    val textFieldState = rememberTextFieldState()
    val searchBarState = rememberSearchBarState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val resultsState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    // Set from opening a result until the search is open again on the way back, with where its list was.
    var resultOpen by rememberSaveable { mutableStateOf(false) }
    var resultsIndex by rememberSaveable { mutableIntStateOf(0) }
    var resultsOffset by rememberSaveable { mutableIntStateOf(0) }
    CollectMessages(viewModel.messages, snackbar)
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect(viewModel::setQuery)
    }
    OnReselect(reselections) {
        if (listState.canScrollBackward) listState.scrollToTop() else searchBarState.animateToExpanded()
    }
    // Each list starts at its own top, not at the offset the other one was left at.
    LaunchedEffect(selected, showPosts, fediverseSelected, fediverseTag, fediverse.server) { listState.scrollToItem(0) }

    // Beside the tabs the profile opens in the detail pane, which a back gesture closes without ever hiding
    // this tab, so there is nothing to cover and the search simply closes.
    val sideBySide = LocalListDetailSceneScope.current != null
    val openFromSearch: (open: () -> Unit) -> Unit = { open ->
        if (!sideBySide) {
            resultOpen = true
            resultsIndex = resultsState.firstVisibleItemIndex
            resultsOffset = resultsState.firstVisibleItemScrollOffset
        }
        scope.launch {
            // The cover is under the search before it closes, so the tab never shows in between.
            if (!sideBySide) repeat(COVER_FRAMES) { withFrameNanos {} }
            searchBarState.snapTo(0f)
            open()
        }
    }
    val openResult: (Author) -> Unit = { author -> openFromSearch { onOpenProfile(author) } }
    val openPostResult: (Post) -> Unit = { post -> openFromSearch { onOpenPost(post) } }
    // Back from the profile, once the gesture is through: the search opens where the cover is.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (resultOpen) scope.launch { searchBarState.snapTo(1f) }
    }
    val cover: @Composable () -> Unit = remember(textFieldState) {
        {
            SearchCover(
                textFieldState = textFieldState,
                resultsIndex = resultsIndex,
                resultsOffset = resultsOffset,
                search = searchState.value,
                following = followingState.value,
            )
        }
    }
    DisposableEffect(resultOpen) {
        onCover(if (resultOpen) cover else null)
        onDispose { onCover(null) }
    }

    ExpandedFullScreenSearchBar(
        state = searchBarState,
        inputField = {
            SearchField(
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                expanded = true,
                onBack = { scope.launch { searchBarState.animateToCollapsed() } },
                // Opened again on the way back, the search waits for a tap before it brings up the keyboard.
                modifier = Modifier.focusProperties { canFocus = !resultOpen },
            )
        },
    ) {
        LaunchedEffect(Unit) {
            if (resultOpen) {
                // A new search window's list starts at the top whatever its state says; it's put back where it was.
                repeat(COVER_FRAMES) {
                    withFrameNanos {}
                    resultsState.scrollToItem(resultsIndex, resultsOffset)
                }
                resultOpen = false
            }
        }
        SearchResults(
            state = search,
            following = following,
            listState = resultsState,
            snackbar = snackbar,
            onToggleFollow = viewModel::toggleFollow,
            onOpenProfile = openResult,
            onOpenPost = openPostResult,
            onRetry = viewModel::retrySearch,
            onSignOut = onSignOut,
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppBarWithSearch(
                state = searchBarState,
                inputField = {
                    SearchField(textFieldState = textFieldState, searchBarState = searchBarState, expanded = false, onBack = {})
                },
                actions = { accountMenu() },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            LazyRow(
                modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Gutter, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "for-you") {
                    TopicChip(stringResource(R.string.explore_for_you), selected = selected == null && !fediverseSelected, onClick = { viewModel.select(null) })
                }
                if (fediverse.enabled) {
                    item(key = "fediverse") {
                        FediverseChip(
                            label = fediverseServerLabel(fediverse.server),
                            selected = fediverseSelected,
                            // Picked already, the chip is where another server is picked.
                            onClick = { if (fediverseSelected) serverSheet = true else viewModel.selectFediverse() },
                        )
                    }
                }
                items(topics, key = { it.slug }) { topic ->
                    TopicChip(topic.name, selected = !fediverseSelected && selected == topic.slug, onClick = { viewModel.select(topic.slug) })
                }
            }

            if (!fediverseSelected && selected != null) {
                SingleChoiceSegmentedButtonRow(
                    Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(horizontal = Gutter).padding(bottom = 8.dp),
                ) {
                    TopicView.entries.forEachIndexed { index, view ->
                        SegmentedButton(
                            selected = topicView == view,
                            onClick = { viewModel.setTopicView(view) },
                            shape = SegmentedButtonDefaults.itemShape(index, TopicView.entries.size),
                        ) {
                            Text(stringResource(if (view == TopicView.Posts) R.string.explore_topic_posts else R.string.explore_topic_people))
                        }
                    }
                }
            }

            val listsPosts = showPosts || fediverseSelected
            val refresh = if (listsPosts) posts.loadState.refresh else suggestions.loadState.refresh
            val itemCount = if (listsPosts) posts.itemCount else suggestions.itemCount
            val refreshing = refresh is LoadState.Loading && itemCount > 0
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    when {
                        fediverseSelected -> {
                            posts.refresh()
                            viewModel.refreshTrends()
                        }
                        listsPosts -> posts.refresh()
                        else -> {
                            viewModel.refresh()
                            suggestions.refresh()
                        }
                    }
                },
                state = pullState,
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(pullState, refreshing, Modifier.align(Alignment.TopCenter))
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    fediverseSelected -> FediverseList(
                        server = fediverse.server,
                        tag = fediverseTag,
                        trends = fediverseTrends,
                        posts = posts,
                        callbacks = callbacks,
                        listState = listState,
                        onOpenTag = viewModel::openTag,
                        onChangeServer = { serverSheet = true },
                        onSignOut = onSignOut,
                    )
                    refresh is LoadState.Error && itemCount == 0 -> FullScreenError(
                        errorMessage(refresh.error),
                        onRetry = if (listsPosts) posts::retry else suggestions::retry,
                        sessionEnded = refresh.error is GhostException.Unauthorized,
                        onSignOut = onSignOut,
                    )
                    refresh is LoadState.Loading && itemCount == 0 -> CenteredLoading()
                    refresh is LoadState.NotLoading && itemCount == 0 -> ScrollableMessage(
                        stringResource(if (showPosts) R.string.explore_topic_posts_empty else R.string.explore_empty),
                    )
                    listsPosts -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        feedItems(posts, callbacks)
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        items(count = suggestions.itemCount, key = suggestions.itemKey { it.handle }, contentType = { "account" }) { index ->
                            val item = suggestions[index] ?: return@items
                            val author = remember(item) { item.toAuthor() }
                            AccountRow(
                                author = author,
                                bio = item.bio,
                                following = following.isFollowing(author),
                                onToggleFollow = { viewModel.toggleFollow(author) },
                                onClick = { onOpenProfile(author) },
                            )
                        }
                        item(key = "append", contentType = "append") {
                            AppendState(suggestions.loadState.append, onRetry = suggestions::retry)
                        }
                    }
                }
            }
        }
    }
    ReplyEditorHost(viewModel.composer)
    if (serverSheet) {
        FediverseServerSheet(onDismiss = { serverSheet = false }, onOpenSettings = onChangeFediverseSource)
    }
}

/** Frames given to the cover or the search to be drawn before the other one goes. */
private const val COVER_FRAMES = 3

/** The search field, in the bar or, [expanded], at the top of the open search with a way back and a way to clear it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    textFieldState: TextFieldState,
    searchBarState: SearchBarState,
    expanded: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    SearchBarDefaults.InputField(
        textFieldState = textFieldState,
        searchBarState = searchBarState,
        onSearch = { keyboard?.hide() },
        modifier = modifier,
        placeholder = { SearchPlaceholder() },
        leadingIcon = {
            if (expanded) {
                IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back)) }
            } else {
                Icon(AppIcons.Search, contentDescription = null)
            }
        },
        trailingIcon = if (expanded && textFieldState.text.isNotEmpty()) {
            {
                IconButton(onClick = { textFieldState.clearText() }) {
                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.explore_search_clear))
                }
            }
        } else {
            null
        },
    )
}

/** The search field's hint; the field's own "Search" says what it is to a screen reader. */
@Composable
private fun SearchPlaceholder() {
    Text(stringResource(R.string.explore_search_placeholder), Modifier.clearAndSetSemantics {}, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** What the open search shows under its field. [snackbar] is where follow failures show, if anywhere. */
@Composable
private fun SearchResults(
    state: SearchUiState,
    following: Map<String, Boolean>,
    listState: LazyListState,
    snackbar: SnackbarHostState?,
    onToggleFollow: (Author) -> Unit,
    onOpenProfile: (Author) -> Unit,
    onOpenPost: (Post) -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    Box(Modifier.fillMaxSize().imePadding()) {
        when (state) {
            SearchUiState.Idle -> ScrollableMessage(stringResource(R.string.explore_search_hint))
            SearchUiState.Loading -> CenteredLoading()
            is SearchUiState.Failed -> FullScreenError(
                errorMessage(state.error),
                onRetry = onRetry,
                sessionEnded = state.error is GhostException.Unauthorized,
                onSignOut = onSignOut,
            )
            is SearchUiState.Found -> if (state.accounts.isEmpty() && state.post == null) {
                ScrollableMessage(stringResource(R.string.explore_search_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    state.post?.let { post ->
                        item(key = "post", contentType = "post") { PostRow(post, onClick = { onOpenPost(post) }) }
                    }
                    items(state.accounts, key = { it.handle }, contentType = { "account" }) { author ->
                        AccountRow(
                            author = author,
                            bio = null,
                            following = following.isFollowing(author),
                            onToggleFollow = { onToggleFollow(author) },
                            onClick = { onOpenProfile(author) },
                        )
                    }
                }
            }
        }
        if (snackbar != null) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * The open search as [ExpandedFullScreenSearchBar] lays it out, field, divider and results where it
 * puts them, but in the app's own window and without taking input: it only has to look the same.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchCover(
    textFieldState: TextFieldState,
    resultsIndex: Int,
    resultsOffset: Int,
    search: SearchUiState,
    following: Map<String, Boolean>,
) {
    val colors = SearchBarDefaults.colors()
    Surface(Modifier.fillMaxSize(), color = colors.containerColor) {
        Column(Modifier.windowInsetsPadding(SearchBarDefaults.fullScreenWindowInsets)) {
            Box(
                Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth()
                    .height(SearchBarDefaults.InputFieldHeight)
                    .clip(SearchBarDefaults.inputFieldShape),
                propagateMinConstraints = true,
            ) {
                SearchField(
                    textFieldState = rememberTextFieldState(textFieldState.text.toString()),
                    searchBarState = rememberSearchBarState(initialValue = SearchBarValue.Expanded),
                    expanded = true,
                    onBack = {},
                    modifier = Modifier.focusProperties { canFocus = false },
                )
            }
            HorizontalDivider(color = colors.dividerColor)
            SearchResults(
                state = search,
                following = following,
                // Its own list, where the search's was left, as two lists can't share one state.
                listState = remember { LazyListState(resultsIndex, resultsOffset) },
                snackbar = null,
                onToggleFollow = {},
                onOpenProfile = {},
                onOpenPost = {},
                onRetry = {},
                onSignOut = {},
            )
        }
    }
}

@Composable
private fun TopicChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = if (selected) {
            { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
    )
}

/**
 * The Fediverse source's chip, named for what its server is about. Once picked, it shows that it
 * opens a choice of servers, which a second tap does.
 */
@Composable
private fun FediverseChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val changeLabel = stringResource(R.string.explore_fediverse_pick_server)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(AppIcons.Public, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        trailingIcon = if (selected) {
            { Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        modifier = if (selected) Modifier.semantics { onClick(label = changeLabel) { onClick(); true } } else Modifier,
    )
}

/**
 * The Fediverse source in one scroll: which server it is, its trending hashtags and the stories its
 * people share, then its trending posts; or, with [tag] open, that hashtag's newest posts. A hashtag
 * link in a post opens here as well, rather than on the server's website.
 */
@Composable
private fun FediverseList(
    server: String,
    tag: String?,
    trends: FediverseTrends?,
    posts: LazyPagingItems<FeedItem>,
    callbacks: PostCallbacks,
    listState: LazyListState,
    onOpenTag: (String?) -> Unit,
    onChangeServer: () -> Unit,
    onSignOut: () -> Unit,
) {
    val outer = LocalUriHandler.current
    val uriHandler = remember(outer, onOpenTag) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val name = hashtagIn(uri)
                if (name != null) onOpenTag(name) else outer.openUri(uri)
            }
        }
    }
    val refresh = posts.loadState.refresh
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (tag == null) {
                item(key = "fediverse-header", contentType = "header") { TrendingHeader(server, onChangeServer) }
                val tags = trends?.tags.orEmpty()
                if (tags.isNotEmpty()) {
                    item(key = "fediverse-tags", contentType = "tags") {
                        SectionLabel(stringResource(R.string.explore_hashtags))
                        TagStrip(tags, onOpenTag = onOpenTag)
                    }
                }
                val links = trends?.links.orEmpty()
                if (links.isNotEmpty()) {
                    item(key = "fediverse-stories", contentType = "stories") {
                        SectionLabel(stringResource(R.string.explore_fediverse_stories))
                        StoryCarousel(links)
                    }
                }
                if (tags.isNotEmpty() || links.isNotEmpty()) {
                    item(key = "fediverse-posts", contentType = "label") { SectionLabel(stringResource(R.string.explore_topic_posts)) }
                }
            } else {
                item(key = "fediverse-tag-header", contentType = "header") {
                    val people = trends?.tags?.firstOrNull { it.name.equals(tag, ignoreCase = true) }?.people
                    TagHeader(tag, server, people, onBack = { onOpenTag(null) })
                }
            }
            when {
                refresh is LoadState.Error && posts.itemCount == 0 -> item(key = "fediverse-state", contentType = "state") {
                    FullScreenError(
                        errorMessage(refresh.error),
                        onRetry = posts::retry,
                        modifier = Modifier.fillParentMaxHeight(0.5f),
                        sessionEnded = refresh.error is GhostException.Unauthorized,
                        onSignOut = onSignOut,
                    )
                }
                refresh is LoadState.Loading && posts.itemCount == 0 -> item(key = "fediverse-state", contentType = "state") {
                    CenteredLoading(Modifier.fillParentMaxHeight(0.4f))
                }
                refresh is LoadState.NotLoading && posts.itemCount == 0 -> item(key = "fediverse-state", contentType = "state") {
                    Text(
                        if (tag != null) {
                            stringResource(R.string.explore_fediverse_tag_empty, tag, server)
                        } else {
                            stringResource(R.string.explore_fediverse_posts_empty, server)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(horizontal = Gutter, vertical = 48.dp),
                    )
                }
                else -> feedItems(posts, callbacks)
            }
        }
    }
}

/** Where the trends come from, and the way to another server. */
@Composable
private fun TrendingHeader(server: String, onChangeServer: () -> Unit) {
    Row(
        Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(start = Gutter, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.explore_fediverse_trending, server),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.explore_fediverse_header_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onChangeServer) { Text(stringResource(R.string.explore_fediverse_change)) }
    }
}

/** The hashtag whose newest posts are listed, how many talk about it when the server says, and the way back to the trends. */
@Composable
private fun TagHeader(tag: String, server: String, people: Int?, onBack: () -> Unit) {
    Row(
        Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(start = 4.dp, end = Gutter, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.explore_fediverse_back))
        }
        Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "#$tag",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (people != null) {
                Text(pluralStringResource(R.plurals.explore_hashtag_people, people, people), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(R.string.explore_fediverse_tag_note, server),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** The server's trending hashtags, each with how many people use it; one opens its posts. */
@Composable
private fun TagStrip(tags: List<TrendingTag>, onOpenTag: (String) -> Unit) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    LazyRow(
        modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tags, key = { it.name.lowercase() }) { tag ->
            val people = pluralStringResource(R.plurals.explore_hashtag_people, tag.people, tag.people)
            SuggestionChip(
                onClick = { onOpenTag(tag.name) },
                label = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("#${tag.name}", maxLines = 1)
                        if (tag.people > 0) {
                            Text(numbers.format(tag.people), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "#${tag.name}, $people" },
            )
        }
    }
}

/** The web pages the server's people share most, as cards that open them. */
@Composable
private fun StoryCarousel(links: List<TrendingLink>) {
    LazyRow(
        modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(links, key = { it.url }) { link -> StoryCard(link) }
    }
}

@Composable
private fun StoryCard(link: TrendingLink) {
    val uriHandler = LocalUriHandler.current
    // A picture that can't be loaded leaves a blank block: the card goes without, like one that has none.
    var imageFailed by remember(link.imageUrl) { mutableStateOf(false) }
    OutlinedCard(onClick = { uriHandler.openUri(link.url) }, modifier = Modifier.width(StoryCardWidth)) {
        if (link.imageUrl != null && !imageFailed) {
            AsyncImage(
                model = link.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            link.provider?.let { provider ->
                Text(
                    provider,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(link.title, style = MaterialTheme.typography.titleSmall, minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (link.people > 0) {
                Text(
                    pluralStringResource(R.plurals.explore_story_people, link.people, link.people),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val StoryCardWidth = 260.dp

/** Picking another server for the Fediverse source, over Explore; it closes once the pick is in use. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FediverseServerSheet(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                // The server field is at the bottom: the keyboard shrinks the sheet so it scrolls into view above it.
                .imePadding()
                .verticalScroll(scroll)
                .padding(horizontal = Gutter)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.explore_fediverse_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.explore_fediverse_sheet_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FediverseServerPicker(scroll, onChosen = close)
            TextButton(
                onClick = {
                    onDismiss()
                    onOpenSettings()
                },
            ) {
                Icon(AppIcons.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.explore_fediverse_settings), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private val HashtagPath = Regex("""/(?:discover/)?tags/([^/?#]+)/?""")

/**
 * The hashtag a link points to, for links to a hashtag's page as Mastodon, Pixelfed and the like
 * write them (`/tags/name`, `/discover/tags/name`), or null for any other link. A blog's `/tag/`
 * pages aren't hashtags and stay links.
 */
internal fun hashtagIn(uri: String): String? {
    val parsed = try {
        URI(uri)
    } catch (_: URISyntaxException) {
        return null
    }
    if (parsed.scheme?.lowercase() !in setOf("https", "http") || parsed.host.isNullOrEmpty()) return null
    val name = HashtagPath.matchEntire(parsed.rawPath.orEmpty())?.groupValues?.get(1) ?: return null
    return URLDecoder.decode(name, Charsets.UTF_8.name()).removePrefix("#").takeIf { it.isNotBlank() }
}

/** A post found by its web address: who wrote it and how it starts. Opens its thread. */
@Composable
private fun PostRow(post: Post, onClick: () -> Unit) {
    val author = post.author
    val name = author.displayName
    val text = remember(post.id) { post.title.ifBlank { parsePostHtml(post.content).text.trim() } }
    ListItem(
        headlineContent = {
            EmojiText(name, author.emojiSourceId, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    author.handle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (text.isNotEmpty()) {
                    EmojiText(
                        text,
                        post.id,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        leadingContent = { Avatar(author.avatarUrl, name, 48.dp) },
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.explore_search_open_post), onClick = onClick),
    )
}

/** A person with a follow button; [bio] is the profile's HTML, shown as plain text. */
@Composable
private fun AccountRow(
    author: Author,
    bio: String?,
    following: Boolean,
    onToggleFollow: () -> Unit,
    onClick: () -> Unit,
) {
    val name = author.displayName
    val bioText = remember(bio) { bio?.let { parsePostHtml(bioHtml(it)).text.trim() }.orEmpty() }
    ListItem(
        headlineContent = {
            EmojiText(name, author.emojiSourceId, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    author.handle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bioText.isNotEmpty()) {
                    EmojiText(
                        bioText,
                        author.emojiSourceId,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        leadingContent = { Avatar(author.avatarUrl, name, 48.dp) },
        trailingContent = { FollowButton(name, following, onToggleFollow) },
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.open_profile), onClick = onClick),
    )
}

/** Not following yet is the call to action, so it is filled; "Following" settles into a tonal state, as on the profile. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FollowButton(name: String, following: Boolean, onClick: () -> Unit) {
    val colors = ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    val shapes = ToggleButtonShapes(
        shape = ToggleButtonDefaults.shape,
        pressedShape = ToggleButtonDefaults.pressedShape,
        checkedShape = ToggleButtonDefaults.shape,
    )
    // Several of these are on screen; the name tells a screen reader which person one is for.
    val description = stringResource(if (following) R.string.explore_following_account else R.string.explore_follow_account, name)
    ToggleButton(
        checked = following,
        onCheckedChange = { onClick() },
        modifier = Modifier.semantics { contentDescription = description },
        shapes = shapes,
        colors = colors,
    ) {
        Text(stringResource(if (following) R.string.profile_following else R.string.profile_follow), maxLines = 1)
    }
}

/** What the user changed here wins over the follow state a list was loaded with. */
private fun Map<String, Boolean>.isFollowing(author: Author): Boolean = this[author.handle.lowercase()] ?: author.followedByMe
