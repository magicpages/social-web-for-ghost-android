package co.magicpages.socialweb.ui.explore

import androidx.compose.foundation.clickable
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
import co.magicpages.socialweb.ui.components.parsePostHtml
import co.magicpages.socialweb.ui.components.scrollToTop
import co.magicpages.socialweb.ui.posts.CollectMessages
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.posts.displayName
import co.magicpages.socialweb.ui.theme.Gutter
import kotlinx.coroutines.launch

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
    onOpenProfile: (Author) -> Unit,
    onSignOut: () -> Unit,
    accountMenu: @Composable () -> Unit,
    onCover: (cover: (@Composable () -> Unit)?) -> Unit,
) {
    val viewModel = hiltViewModel<ExploreViewModel>()
    val suggestions = viewModel.suggestions.collectAsLazyPagingItems()
    val topics by viewModel.topics.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val followingState = viewModel.following.collectAsStateWithLifecycle()
    val following by followingState
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
    LaunchedEffect(selected) { listState.scrollToItem(0) }

    // Beside the tabs the profile opens in the detail pane, which a back gesture closes without ever hiding
    // this tab, so there is nothing to cover and the search simply closes.
    val sideBySide = LocalListDetailSceneScope.current != null
    val openResult: (Author) -> Unit = { author ->
        if (!sideBySide) {
            resultOpen = true
            resultsIndex = resultsState.firstVisibleItemIndex
            resultsOffset = resultsState.firstVisibleItemScrollOffset
        }
        scope.launch {
            // The cover is under the search before it closes, so the tab never shows in between.
            if (!sideBySide) repeat(COVER_FRAMES) { withFrameNanos {} }
            searchBarState.snapTo(0f)
            onOpenProfile(author)
        }
    }
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
                    TopicChip(stringResource(R.string.explore_for_you), selected = selected == null, onClick = { viewModel.select(null) })
                }
                items(topics, key = { it.slug }) { topic ->
                    TopicChip(topic.name, selected = selected == topic.slug, onClick = { viewModel.select(topic.slug) })
                }
            }

            val refresh = suggestions.loadState.refresh
            val refreshing = refresh is LoadState.Loading && suggestions.itemCount > 0
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    viewModel.refresh()
                    suggestions.refresh()
                },
                state = pullState,
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(pullState, refreshing, Modifier.align(Alignment.TopCenter))
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    refresh is LoadState.Error && suggestions.itemCount == 0 -> FullScreenError(
                        errorMessage(refresh.error),
                        onRetry = suggestions::retry,
                        sessionEnded = refresh.error is GhostException.Unauthorized,
                        onSignOut = onSignOut,
                    )
                    refresh is LoadState.Loading && suggestions.itemCount == 0 -> CenteredLoading()
                    refresh is LoadState.NotLoading && suggestions.itemCount == 0 ->
                        ScrollableMessage(stringResource(R.string.explore_empty))
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
            is SearchUiState.Found -> if (state.accounts.isEmpty()) {
                ScrollableMessage(stringResource(R.string.explore_search_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
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
    val bioText = remember(bio) { bio?.let { parsePostHtml(it).text.trim() }.orEmpty() }
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
