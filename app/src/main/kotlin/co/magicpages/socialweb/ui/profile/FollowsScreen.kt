package co.magicpages.socialweb.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.FollowList
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.AppendState
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.CenteredLoading
import co.magicpages.socialweb.ui.components.EmojiText
import co.magicpages.socialweb.ui.components.emojiSourceId
import co.magicpages.socialweb.ui.components.FullScreenError
import co.magicpages.socialweb.ui.components.HandleText
import co.magicpages.socialweb.ui.components.ScrollableMessage
import co.magicpages.socialweb.ui.components.errorMessage
import co.magicpages.socialweb.ui.posts.MaxContentWidth

/** Followers | Following of [handle] (or `me`), opened on [initial]. [name] titles the screen. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FollowsScreen(
    handle: String,
    name: String,
    initial: FollowList,
    onBack: () -> Unit,
    onOpenProfile: (Author) -> Unit,
    onSignOut: () -> Unit,
) {
    val viewModel = hiltViewModel<FollowsViewModel, FollowsViewModel.Factory>(key = handle) { it.create(handle, initial) }
    val selected by viewModel.list.collectAsStateWithLifecycle()
    val accounts = viewModel.accountsList.collectAsLazyPagingItems()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                )
                SecondaryTabRow(selectedTabIndex = selected.ordinal) {
                    FollowList.entries.forEach { list ->
                        Tab(
                            selected = selected == list,
                            onClick = { viewModel.select(list) },
                            text = { Text(stringResource(list.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // A fresh list state per tab, so each starts at its own top rather than the other's offset.
        key(selected) {
            AccountList(accounts, selected, onOpenProfile, onSignOut, Modifier.fillMaxSize().padding(padding))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccountList(
    accounts: LazyPagingItems<Author>,
    list: FollowList,
    onOpenProfile: (Author) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier,
) {
    val refresh = accounts.loadState.refresh
    val refreshing = refresh is LoadState.Loading && accounts.itemCount > 0
    val pullState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = accounts::refresh,
        state = pullState,
        indicator = { PullToRefreshDefaults.LoadingIndicator(pullState, refreshing, Modifier.align(Alignment.TopCenter)) },
        modifier = modifier,
    ) {
        when {
            refresh is LoadState.Error && accounts.itemCount == 0 -> FullScreenError(
                errorMessage(refresh.error),
                onRetry = accounts::retry,
                sessionEnded = refresh.error is GhostException.Unauthorized,
                onSignOut = onSignOut,
            )
            refresh is LoadState.Loading && accounts.itemCount == 0 -> CenteredLoading()
            refresh is LoadState.NotLoading && accounts.itemCount == 0 -> ScrollableMessage(
                stringResource(if (list == FollowList.Followers) R.string.follows_no_followers else R.string.follows_no_following),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(accounts.itemCount, key = accounts.itemKey { it.handle }, contentType = { "account" }) { index ->
                    accounts[index]?.let { AccountRow(it, onClick = { onOpenProfile(it) }) }
                }
                item(key = "append", contentType = "append") { AppendState(accounts.loadState.append, accounts::retry) }
            }
        }
    }
}

@Composable
private fun AccountRow(author: Author, onClick: () -> Unit) {
    val name = author.name.ifBlank { author.handle }
    ListItem(
        headlineContent = {
            EmojiText(name, author.emojiSourceId, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            HandleText(
                author.handle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { Avatar(author.avatarUrl, name, 40.dp) },
        trailingContent = if (author.followedByMe) {
            {
                Text(
                    stringResource(R.string.profile_following),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.open_profile), onClick = onClick),
    )
}

private val FollowList.title: Int
    get() = when (this) {
        FollowList.Followers -> R.string.follows_followers
        FollowList.Following -> R.string.follows_following
    }
