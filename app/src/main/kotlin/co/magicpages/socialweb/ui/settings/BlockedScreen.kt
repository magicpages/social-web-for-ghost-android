package co.magicpages.socialweb.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.CenteredLoading
import co.magicpages.socialweb.ui.components.EmojiText
import co.magicpages.socialweb.ui.components.FullScreenError
import co.magicpages.socialweb.ui.components.ScrollableMessage
import co.magicpages.socialweb.ui.components.emojiSourceId
import co.magicpages.socialweb.ui.components.errorMessage
import co.magicpages.socialweb.ui.moderation.BlockDialog
import co.magicpages.socialweb.ui.moderation.BlockTarget
import co.magicpages.socialweb.ui.posts.MaxContentWidth

/**
 * The accounts and servers the signed-in account [accountId] has blocked, one tab each, with a way
 * to lift each block after a confirmation. Profiles open, and an ended session signs out, only for
 * the active account: [onOpenProfile] and [onSignOut] are null for another, as they'd act as the active one.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BlockedScreen(
    accountId: String,
    onBack: () -> Unit,
    onOpenProfile: ((Author) -> Unit)?,
    onSignOut: (() -> Unit)?,
) {
    val viewModel = hiltViewModel<BlockedViewModel, BlockedViewModel.Factory>(key = accountId) { it.create(accountId) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // The first load, and again on each return: a profile opened from here may have lifted a block.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirming by remember { mutableStateOf<UnblockTarget?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // The failure text needs a composable lookup, so it is resolved here rather than in the effect.
    val notice = ui.notice
    val failureText = (notice as? BlockedNotice.Failed)?.let { errorMessage(it.error) }
    val resources = LocalResources.current
    val retryLabel = stringResource(R.string.retry)
    LaunchedEffect(notice) {
        when (notice) {
            null -> Unit
            is BlockedNotice.Unblocked -> {
                snackbar.showSnackbar(resources.getString(R.string.blocked_unblocked, notice.label))
                viewModel.noticeShown(notice)
            }
            is BlockedNotice.Failed -> {
                val result = snackbar.showSnackbar(
                    message = failureText.orEmpty(),
                    actionLabel = retryLabel,
                    duration = SnackbarDuration.Long,
                )
                viewModel.noticeShown(notice)
                if (result == SnackbarResult.ActionPerformed) viewModel.unblock(notice.target)
            }
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.blocked_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                SecondaryTabRow(selectedTabIndex = tab) {
                    val accountCount = (ui.accounts as? BlockedList.Loaded)?.items?.size
                    val serverCount = (ui.servers as? BlockedList.Loaded)?.items?.size
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = {
                            Text(
                                if (accountCount == null) {
                                    stringResource(R.string.blocked_tab_accounts)
                                } else {
                                    stringResource(R.string.blocked_tab_accounts_count, accountCount)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = {
                            Text(
                                if (serverCount == null) {
                                    stringResource(R.string.blocked_tab_servers)
                                } else {
                                    stringResource(R.string.blocked_tab_servers_count, serverCount)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        if (tab == 0) {
            BlockedContent(
                list = ui.accounts,
                emptyText = R.string.blocked_no_accounts,
                onRetry = viewModel::loadAccounts,
                onSignOut = onSignOut,
                modifier = modifier,
            ) { accounts ->
                items(accounts, key = { it.id }, contentType = { "account" }) { author ->
                    AccountRow(
                        author = author,
                        busy = author.id in ui.busy,
                        onOpen = onOpenProfile?.let { open -> { open(author) } },
                        onUnblock = { confirming = UnblockTarget.Account(author) },
                    )
                }
            }
        } else {
            BlockedContent(
                list = ui.servers,
                emptyText = R.string.blocked_no_servers,
                onRetry = viewModel::loadServers,
                onSignOut = onSignOut,
                modifier = modifier,
            ) { servers ->
                items(servers, key = { it }, contentType = { "server" }) { host ->
                    ServerRow(host = host, busy = host in ui.busy, onUnblock = { confirming = UnblockTarget.Server(host) })
                }
            }
        }
    }

    confirming?.let { target ->
        BlockDialog(
            target = when (target) {
                is UnblockTarget.Account -> BlockTarget.Account(target.author.handle)
                is UnblockTarget.Server -> BlockTarget.Server(target.host)
            },
            unblock = true,
            onConfirm = {
                confirming = null
                viewModel.unblock(target)
            },
            onDismiss = { confirming = null },
        )
    }
}

/** Loading, error and empty states of one list; [rows] adds the items once it has some. */
@Composable
private fun <T> BlockedContent(
    list: BlockedList<T>,
    emptyText: Int,
    onRetry: () -> Unit,
    onSignOut: (() -> Unit)?,
    modifier: Modifier,
    rows: LazyListScope.(List<T>) -> Unit,
) {
    when (list) {
        BlockedList.Loading -> CenteredLoading(modifier)
        is BlockedList.Failed -> FullScreenError(
            errorMessage(list.error),
            onRetry = onRetry,
            modifier = modifier,
            sessionEnded = list.error is GhostException.Unauthorized,
            onSignOut = onSignOut,
        )
        is BlockedList.Loaded -> if (list.items.isEmpty()) {
            ScrollableMessage(stringResource(emptyText), modifier)
        } else {
            LazyColumn(
                modifier,
                contentPadding = PaddingValues(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { rows(list.items) }
        }
    }
}

@Composable
private fun AccountRow(author: Author, busy: Boolean, onOpen: (() -> Unit)?, onUnblock: () -> Unit) {
    val name = author.name.ifBlank { author.handle }
    ListItem(
        headlineContent = {
            EmojiText(name, author.emojiSourceId, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                author.handle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { Avatar(author.avatarUrl, name, 40.dp) },
        trailingContent = { TextButton(onClick = onUnblock, enabled = !busy) { Text(stringResource(R.string.action_unblock)) } },
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = stringResource(R.string.open_profile), onClick = onOpen) else Modifier),
    )
}

@Composable
private fun ServerRow(host: String, busy: Boolean, onUnblock: () -> Unit) {
    ListItem(
        headlineContent = { Text(host, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(AppIcons.Public, contentDescription = null) },
        trailingContent = { TextButton(onClick = onUnblock, enabled = !busy) { Text(stringResource(R.string.action_unblock)) } },
        modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
    )
}
