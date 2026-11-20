package co.magicpages.socialweb.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.FollowList
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.activitypub.serverOf
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.ghost.displayAddress
import co.magicpages.socialweb.data.ghost.fediPubUrl
import co.magicpages.socialweb.data.ghost.ghostSiteUrl
import co.magicpages.socialweb.ui.browser.PortalPage
import co.magicpages.socialweb.ui.browser.openPortal
import co.magicpages.socialweb.ui.components.ActionSheet
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.CenteredLoading
import co.magicpages.socialweb.ui.components.EmojiText
import co.magicpages.socialweb.ui.components.FullScreenError
import co.magicpages.socialweb.ui.components.HtmlText
import co.magicpages.socialweb.ui.components.LocalImageViewer
import co.magicpages.socialweb.ui.components.SheetAction
import co.magicpages.socialweb.ui.components.ViewerImage
import co.magicpages.socialweb.ui.components.errorMessage
import co.magicpages.socialweb.ui.posts.CollectMessages
import co.magicpages.socialweb.ui.posts.FeedItem
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.posts.PostCallbacks
import co.magicpages.socialweb.ui.posts.ReplyEditorHost
import co.magicpages.socialweb.ui.posts.feedItems
import co.magicpages.socialweb.ui.posts.rememberCopyLink
import co.magicpages.socialweb.ui.moderation.BlockDialog
import co.magicpages.socialweb.ui.moderation.BlockTarget
import co.magicpages.socialweb.ui.moderation.LocalOpenReport
import co.magicpages.socialweb.ui.moderation.ReportKey
import co.magicpages.socialweb.ui.theme.Gutter
import java.text.NumberFormat
import kotlinx.coroutines.launch

private val BannerHeight = 168.dp
private val AvatarSize = 96.dp
private val AvatarRing = 4.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileScreen(
    handle: String,
    fallbackUrl: String?,
    onBack: () -> Unit,
    onOpenPost: (Post) -> Unit,
    onOpenProfile: (Author) -> Unit,
    onOpenFollows: (name: String, list: FollowList) -> Unit,
    onSignOut: () -> Unit,
) {
    val viewModel = hiltViewModel<ProfileViewModel, ProfileViewModel.Factory>(key = handle) { it.create(handle) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val posts = viewModel.posts.collectAsLazyPagingItems()
    val site by viewModel.site.collectAsStateWithLifecycle()
    var confirmRecommendation by remember { mutableStateOf(false) }
    var shareOpen by rememberSaveable { mutableStateOf(false) }
    // The edit form's own state, so a rotation or a trip to the photo picker keeps what was typed.
    val edit = hiltViewModel<ProfileEditViewModel>(key = "edit-$handle")
    val editing by edit.state.collectAsStateWithLifecycle()
    LaunchedEffect(edit) {
        edit.saved.collect {
            viewModel.onEdited()
            posts.refresh()
        }
    }
    // What the menu or the Unblock button asked to change, and whether to block (or unblock) it.
    var blockDialog by remember { mutableStateOf<Pair<BlockTarget, Boolean>?>(null) }
    val uris = LocalUriHandler.current
    // A recommended site that has a social web account opens as a profile in the app; any other opens as a website.
    val openRecommendation: (SiteRecommendation) -> Unit = { item ->
        val details = item.details
        val account = details?.handle
        if (details != null && account != null) {
            onOpenProfile(Author(id = account, handle = account, name = details.displayName, avatarUrl = details.iconUrl, url = item.url))
        } else {
            uris.openUri(item.url)
        }
    }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    val callbacks = remember(viewModel) {
        PostCallbacks(
            open = onOpenPost,
            // Tapping someone on their own profile would only stack the same screen again. `me`
            // never equals an author's handle, so compare with the loaded one too.
            openProfile = {
                val shown = (viewModel.state.value as? ProfileUiState.Loaded)?.content?.profile?.handle
                if (it.handle != handle && it.handle != shown) onOpenProfile(it)
            },
            reply = viewModel.composer::start,
            toggleLike = viewModel::toggleLike,
            toggleRepost = viewModel::toggleRepost,
        )
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The bar starts transparent over the banner and turns solid once the list leaves the top; the
    // name only appears in it once the header with the big name has scrolled away. Read from the
    // list itself rather than a nested-scroll behaviour, so jumps like "show posts" count too.
    val scrolled by remember { derivedStateOf { listState.canScrollBackward } }
    val barColor by animateColorAsState(
        if (scrolled) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val headerGone by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val loaded = (state as? ProfileUiState.Loaded)?.content
    val profileUrl = loaded?.profile?.url?.takeIf { it.isNotBlank() } ?: fallbackUrl
    val shareUrl = loaded?.profile?.let { profileShareUrl(it.handle, it.apId, it.url, fallbackUrl) } ?: fallbackUrl

    BannerStatusBar(overBanner = !scrolled && !loaded?.profile?.bannerImageUrl.isNullOrBlank())
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(
                        headerGone && loaded != null,
                        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                        exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
                    ) {
                        EmojiText(
                            loaded?.profile?.name.orEmpty(),
                            loaded?.profile?.apId,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) {
                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (profileUrl != null) {
                        val uriHandler = LocalUriHandler.current
                        FilledTonalIconButton(onClick = { uriHandler.openUri(profileUrl) }) {
                            Icon(AppIcons.OpenInNew, contentDescription = stringResource(R.string.open_in_browser))
                        }
                    }
                    if (loaded != null) {
                        ProfileMoreMenu(loaded, profileUrl, onShare = { shareOpen = true }, onBlock = { target, block -> blockDialog = target to block })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor),
            )
        },
    ) { padding ->
        // The banner runs under the status bar and the app bar, so only the bottom inset is applied.
        val bottom = padding.calculateBottomPadding()
        when (val current = state) {
            ProfileUiState.Loading -> CenteredLoading(Modifier.padding(padding))
            is ProfileUiState.Failed -> ProfileError(current.error, profileUrl, viewModel::load, onSignOut, Modifier.padding(padding))
            is ProfileUiState.Loaded -> {
                val refreshing = posts.loadState.refresh is LoadState.Loading && posts.itemCount > 0
                val pullState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        viewModel.load()
                        posts.refresh()
                    },
                    state = pullState,
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            pullState,
                            refreshing,
                            Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp + bottom),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        item(key = "header", contentType = "header") {
                            val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                            val profile = current.content.profile
                            ProfileHeader(
                                current.content,
                                statusBarHeight = statusBar,
                                site = site,
                                onRecommend = { confirmRecommendation = true },
                                onToggleFollow = viewModel::toggleFollow,
                                onUnblock = { blockDialog = BlockTarget.Account(profile.handle) to false },
                                onEdit = { edit.begin(profile) },
                                onShare = { shareOpen = true },
                                onShowPosts = {
                                    viewModel.select(ProfileTab.Posts)
                                    scope.launch { listState.animateScrollToItem(1) }
                                },
                                onShowFollows = { onOpenFollows(profile.name.ifBlank { profile.handle }, it) },
                            )
                        }
                        item(key = "tabs", contentType = "tabs") {
                            ProfileTabs(tabs = current.content.tabs, selected = tab, onSelect = viewModel::select)
                        }
                        if (tab == ProfileTab.Recommendations) {
                            recommendationItems(site, openRecommendation)
                        } else {
                            postsOrMessage(posts, callbacks, tab)
                        }
                    }
                }
            }
        }
    }
    val dialogSite = site
    if (confirmRecommendation && dialogSite?.recommended != null) {
        RecommendationDialog(
            recommended = dialogSite.recommended == true,
            name = loaded?.profile?.name?.takeIf { it.isNotBlank() } ?: displayAddress(dialogSite.url),
            onConfirm = {
                confirmRecommendation = false
                if (dialogSite.recommended == true) viewModel.unrecommend() else viewModel.recommend()
            },
            onDismiss = { confirmRecommendation = false },
        )
    }
    blockDialog?.let { (target, block) ->
        BlockDialog(
            target,
            unblock = !block,
            onConfirm = {
                blockDialog = null
                viewModel.setBlocked(target, block)
            },
            onDismiss = { blockDialog = null },
        )
    }
    if (shareOpen) {
        loaded?.profile?.let { profile ->
            ProfileShareSheet(
                name = profile.name,
                handle = profile.handle,
                avatarUrl = profile.avatarUrl?.takeIf { it.isNotBlank() },
                url = shareUrl,
                onDismiss = { shareOpen = false },
            )
        }
    }
    editing?.let { ProfileEditDialog(it, edit) }
    ReplyEditorHost(viewModel.composer)
}

/**
 * The profile's "more" button: Copy link, and for other accounts Report and Block (or Unblock)
 * the account or its whole server. [onBlock] gets what to change and whether to block it.
 * A Ghost account's link is its fedi.pub page: Ghost has no profile page, and its URL is the site's home page.
 */
@Composable
private fun ProfileMoreMenu(content: ProfileContent, profileUrl: String?, onShare: () -> Unit, onBlock: (BlockTarget, Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val copyLink = rememberCopyLink()
    val openReport = LocalOpenReport.current
    FilledTonalIconButton(onClick = { open = true }, modifier = Modifier.padding(end = 4.dp)) {
        Icon(AppIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
    }
    if (!open) return
    val profile = content.profile
    ActionSheet(onDismiss = { open = false }) {
        SheetAction(stringResource(R.string.action_share_profile), AppIcons.Share) { onShare() }
        val link = fediPubUrl(profile.handle).takeIf { ghostSiteUrl(profile.apId) != null } ?: profileUrl
        if (link != null) {
            SheetAction(stringResource(R.string.action_copy_link), AppIcons.ContentCopy) { copyLink(link) }
        }
        if (content.isMe) return@ActionSheet
        val name = profile.name.ifBlank { profile.handle }
        if (openReport != null) {
            SheetAction(stringResource(R.string.action_report_account, name), AppIcons.Flag) {
                openReport(ReportKey(profile.apId.ifBlank { profile.id }, profile.handle, name))
            }
        }
        val account = BlockTarget.Account(profile.handle)
        SheetAction(
            stringResource(if (profile.blockedByMe) R.string.action_unblock_account else R.string.action_block_account, profile.handle),
            AppIcons.Block,
        ) { onBlock(account, !profile.blockedByMe) }
        val server = BlockTarget.Server(serverOf(profile.handle))
        SheetAction(
            stringResource(if (profile.domainBlockedByMe) R.string.action_unblock_server else R.string.action_block_server, server.host),
            AppIcons.Block,
        ) { onBlock(server, !profile.domainBlockedByMe) }
    }
}

/** "You blocked this account." under the handle, so a blocked profile isn't mistaken for a quiet one. */
@Composable
private fun BlockedNotice(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(AppIcons.Block, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ProfileError(error: Throwable, profileUrl: String?, onRetry: () -> Unit, onSignOut: () -> Unit, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    FullScreenError(
        modifier = modifier,
        message = if ((error as? SocialWebException)?.status == 404) stringResource(R.string.profile_not_found) else errorMessage(error),
        onRetry = onRetry,
        sessionEnded = error is GhostException.Unauthorized,
        onSignOut = onSignOut,
        secondary = {
            if (profileUrl != null) {
                OutlinedButton(onClick = { uriHandler.openUri(profileUrl) }) { Text(stringResource(R.string.open_in_browser)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileHeader(
    content: ProfileContent,
    statusBarHeight: Dp,
    onToggleFollow: () -> Unit,
    onUnblock: () -> Unit,
    onShowPosts: () -> Unit,
    onShowFollows: (FollowList) -> Unit,
    site: ProfileSite?,
    onRecommend: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
) {
    val profile = content.profile
    val bannerHeight = BannerHeight + statusBarHeight
    Column(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            val viewer = LocalImageViewer.current
            val viewLabel = stringResource(R.string.image_view)
            Banner(
                profile.bannerImageUrl,
                // The status bar and the (transparent) top app bar sit over this image.
                scrimHeight = statusBarHeight + TopAppBarDefaults.TopAppBarExpandedHeight,
                modifier = Modifier.fillMaxWidth().height(bannerHeight),
            ) { url ->
                viewer.show(listOf(ViewerImage(url)), 0)
            }
            val cookie = MaterialShapes.Cookie9Sided.toShape()
            Box(
                Modifier
                    .padding(start = Gutter, top = bannerHeight - AvatarSize / 2)
                    .size(AvatarSize + AvatarRing * 2)
                    .clip(cookie)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                val avatarUrl = profile.avatarUrl?.takeIf { it.isNotBlank() }
                Avatar(
                    avatarUrl,
                    profile.name,
                    AvatarSize,
                    shape = cookie,
                    modifier = if (avatarUrl == null) {
                        Modifier
                    } else {
                        val avatarLabel = stringResource(R.string.image_avatar)
                        Modifier
                            .semantics { contentDescription = avatarLabel }
                            .clickable(onClickLabel = viewLabel, role = Role.Image) {
                                viewer.show(listOf(ViewerImage(avatarUrl, profile.name)), 0)
                            }
                    },
                )
            }
            val buttonModifier = Modifier.align(Alignment.TopEnd).padding(top = bannerHeight + 12.dp, end = Gutter)
            if (content.isMe) {
                Row(buttonModifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = onShare) {
                        Icon(AppIcons.Share, contentDescription = stringResource(R.string.action_share_profile))
                    }
                    FilledTonalButton(onClick = onEdit) {
                        Icon(AppIcons.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.profile_edit))
                    }
                }
            } else if (profile.blockedByMe) {
                // A blocked account can't be followed: the way back is unblocking, so that takes the button's place.
                FilledTonalButton(onClick = onUnblock, modifier = buttonModifier) {
                    Text(stringResource(R.string.action_unblock))
                }
            } else {
                FollowButton(following = profile.followedByMe, onClick = onToggleFollow, modifier = buttonModifier)
            }
        }

        Column(
            Modifier.padding(start = Gutter, end = Gutter, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EmojiText(profile.name, profile.apId, style = MaterialTheme.typography.headlineSmallEmphasized)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        profile.handle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (profile.followsMe) FollowsYouBadge()
                }
                when {
                    profile.blockedByMe -> BlockedNotice(stringResource(R.string.profile_blocked))
                    profile.domainBlockedByMe -> BlockedNotice(stringResource(R.string.profile_server_blocked, serverOf(profile.handle)))
                }
            }
            if (content.bio.isNotEmpty()) {
                HtmlText(content.bio, style = MaterialTheme.typography.bodyLarge, emojiSourceId = profile.apId)
            }
            if (content.fields.isNotEmpty()) ProfileFields(content.fields, profile.apId)
            site?.let { SiteActions(it, content.isMe, onRecommend) }
            Stats(profile.postCount, profile.followingCount, profile.followerCount, onShowPosts, onShowFollows)
        }
    }
}

/**
 * Light status bar icons while they sit on a banner image, which is dark under its scrim whatever
 * the theme; the window's own setting comes back once the bar turns solid or the screen goes.
 * Counted, since a profile opened from a profile takes over before the one below lets go.
 */
@Composable
private fun BannerStatusBar(overBanner: Boolean) {
    if (!overBanner) return
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(activity, view) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        if (BannerStatusBarHolders.count++ == 0) BannerStatusBarHolders.before = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose {
            if (--BannerStatusBarHolders.count == 0) controller.isAppearanceLightStatusBars = BannerStatusBarHolders.before
        }
    }
}

/** The banners showing under the status bar, and the icons' setting from before the first (main thread only). */
private object BannerStatusBarHolders {
    var count = 0
    var before = true
}

@Composable
private fun Banner(url: String?, scrimHeight: Dp, modifier: Modifier, onOpen: (String) -> Unit) {
    val gradient = Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer),
    )
    Box(modifier.background(gradient)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.image_banner),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClickLabel = stringResource(R.string.image_view), role = Role.Image) { onOpen(url) },
            )
            // The status bar icons and the bar's buttons are light-on-transparent, which a pale banner (a yellow one,
            // say) swallows. A dark fade behind them keeps them legible. It lives on the image only, so the solid bar
            // that replaces the banner on scroll is untouched.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(scrimHeight)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FollowButton(following: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Not following yet is the call to action, so it's filled; "Following" settles into a tonal state. It stays a pill,
    // like the site buttons' tonal "on" state (Member, Recommended) below it.
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
    ToggleButton(checked = following, onCheckedChange = { onClick() }, modifier = modifier, shapes = shapes, colors = colors) {
        Icon(
            if (following) AppIcons.Check else AppIcons.Add,
            contentDescription = null,
            modifier = Modifier.size(ToggleButtonDefaults.IconSize),
        )
        Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
        Text(stringResource(if (following) R.string.profile_following else R.string.profile_follow))
    }
}

@Composable
private fun FollowsYouBadge() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            stringResource(R.string.profile_follows_you),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Profile metadata (website, pronouns, …) as an M3 segmented list. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileFields(fields: List<Pair<String, androidx.compose.ui.text.AnnotatedString>>, emojiSourceId: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        fields.forEachIndexed { index, (label, value) ->
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index, fields.size),
                // Filled containers sitting on the page background use surfaceContainer.
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                overlineContent = { Text(label) },
            ) {
                HtmlText(value, style = MaterialTheme.typography.bodyMedium, emojiSourceId = emojiSourceId)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Stats(posts: Int, following: Int, followers: Int, onShowPosts: () -> Unit, onShowFollows: (FollowList) -> Unit) {
    val format = remember { NumberFormat.getIntegerInstance() }
    // Each stat's touch area overhangs its text by StatPadding; pull the row back so the numbers
    // still line up with the text above.
    FlowRow(
        modifier = Modifier.offset(x = -StatPadding),
        horizontalArrangement = Arrangement.spacedBy(StatGap - StatPadding * 2),
    ) {
        Stat(format.format(posts), pluralStringResource(R.plurals.profile_post_count, posts), R.string.stat_show_posts, onShowPosts)
        Stat(format.format(following), stringResource(R.string.profile_following_count), R.string.stat_show_list) {
            onShowFollows(FollowList.Following)
        }
        Stat(format.format(followers), pluralStringResource(R.plurals.profile_follower_count, followers), R.string.stat_show_list) {
            onShowFollows(FollowList.Followers)
        }
    }
}

private val StatPadding = 6.dp

/** The gap between one stat's text and the next. */
private val StatGap = 20.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Stat(value: String, label: String, actionLabel: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(actionLabel), role = Role.Button, onClick = onClick)
            .padding(horizontal = StatPadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(value, style = MaterialTheme.typography.titleMediumEmphasized)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Several lists: tabs. Just the posts: a plain "Posts" heading. */
@Composable
private fun ProfileTabs(tabs: List<ProfileTab>, selected: ProfileTab, onSelect: (ProfileTab) -> Unit) {
    if (tabs.size < 2) {
        Text(
            stringResource(R.string.profile_posts),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth()
                .padding(start = Gutter, end = Gutter, top = 20.dp, bottom = 8.dp),
        )
        return
    }
    // Tabs, not a button group: these switch which list is shown, and every tab keeps one shape.
    SecondaryScrollableTabRow(
        selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        edgePadding = Gutter,
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                text = { Text(stringResource(tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

private val ProfileTab.label: Int
    get() = when (this) {
        ProfileTab.Posts -> R.string.profile_posts
        ProfileTab.Likes -> R.string.profile_likes
        ProfileTab.Replies -> R.string.profile_replies
        ProfileTab.Recommendations -> R.string.profile_recommendations
    }

private val ProfileTab.emptyMessage: Int
    get() = when (this) {
        ProfileTab.Posts -> R.string.profile_no_posts
        ProfileTab.Likes -> R.string.profile_no_likes
        ProfileTab.Replies -> R.string.profile_no_replies
        ProfileTab.Recommendations -> R.string.profile_no_recommendations
    }

private fun androidx.compose.foundation.lazy.LazyListScope.postsOrMessage(
    posts: LazyPagingItems<FeedItem>,
    callbacks: PostCallbacks,
    tab: ProfileTab,
) {
    val refresh = posts.loadState.refresh
    when {
        refresh is LoadState.Loading && posts.itemCount == 0 -> item(key = "loading", contentType = "loading") {
            CenteredLoading(Modifier.height(160.dp))
        }
        refresh is LoadState.Error && posts.itemCount == 0 -> item(key = "error", contentType = "error") {
            FullScreenError(errorMessage(refresh.error), onRetry = posts::retry, modifier = Modifier.height(240.dp))
        }
        refresh is LoadState.NotLoading && posts.itemCount == 0 -> item(key = "empty", contentType = "empty") {
            Text(
                stringResource(tab.emptyMessage),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .widthIn(max = MaxContentWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Gutter, vertical = 24.dp),
            )
        }
        else -> feedItems(posts, callbacks)
    }
}

/**
 * The profile's Ghost site: a link to it, its membership, and the button that adds it to the signed-in site's
 * recommendations. The membership and recommendation buttons are for other people's profiles.
 */
@Composable
private fun SiteActions(site: ProfileSite, isMe: Boolean, onRecommend: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        SiteButton(displayAddress(site.url), AppIcons.Link) { uriHandler.openUri(site.url) }
        if (!isMe) {
            if (site.subscribed || site.acceptsMembers) MembershipButton(site)
            site.recommended?.let { recommended ->
                SiteButton(
                    stringResource(if (recommended) R.string.profile_recommended else R.string.profile_recommend),
                    if (recommended) AppIcons.StarFilled else AppIcons.Star,
                    on = recommended,
                    enabled = !site.recommendBusy,
                    onClick = onRecommend,
                )
            }
        }
    }
}

/** One look for the site row: the same pill throughout, outlined until it's on (a member, recommended), then tonal. */
@Composable
private fun SiteButton(text: String, icon: ImageVector, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (on) FilledTonalButton(onClick, enabled = enabled, content = content) else OutlinedButton(onClick, enabled = enabled, content = content)
}

/**
 * The way into the site's membership, which unlocks its members-only posts. The app only knows about a sign-in made
 * in its own browser, not on the website in the phone's browser, so the sheet says that and offers signing in as
 * plainly as joining: "Subscribe" alone sent existing members to the website.
 */
@Composable
private fun MembershipButton(site: ProfileSite) {
    val context = LocalContext.current
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    SiteButton(
        stringResource(if (site.subscribed) R.string.membership_member else R.string.membership_button),
        if (site.subscribed) AppIcons.Check else AppIcons.CardMembership,
        on = site.subscribed,
    ) { sheetOpen = true }
    if (!sheetOpen) return
    val name = site.title ?: displayAddress(site.url)
    ActionSheet(onDismiss = { sheetOpen = false }) {
        // Lined up with the rows' icons.
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                stringResource(if (site.subscribed) R.string.membership_signed_in else R.string.membership_signed_out, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (site.subscribed) {
            SheetAction(
                stringResource(R.string.membership_manage),
                AppIcons.Settings,
                supportingText = stringResource(R.string.membership_manage_hint),
            ) { openPortal(context, site.url, PortalPage.Account, site.title) }
        } else {
            SheetAction(
                stringResource(R.string.membership_sign_in),
                AppIcons.Login,
                supportingText = stringResource(R.string.membership_sign_in_hint),
            ) { openPortal(context, site.url, PortalPage.SignIn, site.title) }
            SheetAction(
                stringResource(R.string.membership_join),
                AppIcons.PersonAdd,
                supportingText = stringResource(R.string.membership_join_hint),
            ) { openPortal(context, site.url, PortalPage.SignUp, site.title) }
        }
    }
}

/** Asks before changing the signed-in site's recommendations, which its readers see and the recommended site is told about. */
@Composable
private fun RecommendationDialog(recommended: Boolean, name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (recommended) R.string.unrecommend_title else R.string.recommend_title, name)) },
        text = { Text(stringResource(if (recommended) R.string.unrecommend_message else R.string.recommend_message, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(if (recommended) R.string.unrecommend_confirm else R.string.recommend_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

private fun LazyListScope.recommendationItems(site: ProfileSite?, onOpen: (SiteRecommendation) -> Unit) {
    val recommendations = site?.recommendations.orEmpty()
    if (recommendations.isEmpty()) {
        item(key = "recommendations-empty", contentType = "empty") {
            Text(
                stringResource(R.string.profile_no_recommendations),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .widthIn(max = MaxContentWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Gutter, vertical = 24.dp),
            )
        }
        return
    }
    items(recommendations, key = { "recommendation:${it.url}" }, contentType = { "recommendation" }) { item ->
        RecommendationRow(item, onOpen = { onOpen(item) })
    }
}

/** A site and what is known of it so far: its address alone until its name, icon and description arrive. */
@Composable
private fun RecommendationRow(item: SiteRecommendation, onOpen: () -> Unit) {
    val details = item.details
    val address = displayAddress(item.url)
    val opensProfile = details?.handle != null
    ListItem(
        headlineContent = { Text(details?.displayName ?: address, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                details?.excerpt?.takeIf { it.isNotBlank() }?.let {
                    Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (details?.title != null) {
                    Text(
                        address,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        leadingContent = {
            Box(
                Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (details?.iconUrl != null) {
                    AsyncImage(model = details.iconUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(AppIcons.Public, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(if (opensProfile) R.string.recommendation_open_profile else R.string.recommendation_open_site),
                onClick = onOpen,
            ),
    )
}
