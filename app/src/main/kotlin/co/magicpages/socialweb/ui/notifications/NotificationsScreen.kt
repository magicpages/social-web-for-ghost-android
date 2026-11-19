package co.magicpages.socialweb.ui.notifications

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Notification
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.ActionSheet
import co.magicpages.socialweb.ui.components.AppendState
import co.magicpages.socialweb.ui.components.SheetAction
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.CenteredLoading
import co.magicpages.socialweb.ui.components.EmojiText
import co.magicpages.socialweb.ui.components.emojiSourceId
import co.magicpages.socialweb.ui.components.FullScreenError
import co.magicpages.socialweb.ui.components.OnReselect
import co.magicpages.socialweb.ui.components.ScrollableMessage
import co.magicpages.socialweb.ui.components.errorMessage
import co.magicpages.socialweb.ui.components.scrollToTop
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.posts.displayName
import co.magicpages.socialweb.ui.posts.relativeTime

/**
 * The notifications tab. [reselections] counts taps on its already-selected tab, each a request to
 * scroll back to the top; [accountMenu] sits at the end of the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NotificationsScreen(
    account: StoredAccount?,
    reselections: Int,
    onOpenPost: (postId: String, url: String?) -> Unit,
    onOpenProfile: (Author) -> Unit,
    onSignOut: () -> Unit,
    accountMenu: @Composable () -> Unit,
) {
    val viewModel = hiltViewModel<NotificationsViewModel>()
    val notifications = viewModel.notifications.collectAsLazyPagingItems()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    OnReselect(reselections) { listState.scrollToTop() }
    LaunchedEffect(viewModel) { viewModel.unseen.collect { notifications.refresh() } }
    // The background check can only announce new notifications once the user allowed it.
    RequestNotificationPermissionOnce()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_notifications)) },
                subtitle = { account?.let { Text(it.site.title) } },
                actions = { accountMenu() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val refresh = notifications.loadState.refresh
        val refreshing = refresh is LoadState.Loading && notifications.itemCount > 0
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = notifications::refresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(pullState, refreshing, Modifier.align(Alignment.TopCenter))
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                refresh is LoadState.Error && notifications.itemCount == 0 -> FullScreenError(
                    errorMessage(refresh.error),
                    onRetry = notifications::retry,
                    sessionEnded = refresh.error is GhostException.Unauthorized,
                    onSignOut = onSignOut,
                )
                refresh is LoadState.Loading && notifications.itemCount == 0 -> CenteredLoading()
                refresh is LoadState.NotLoading && notifications.itemCount == 0 ->
                    ScrollableMessage(stringResource(R.string.notifications_empty))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(
                        count = notifications.itemCount,
                        key = notifications.itemKey { it.id },
                        contentType = notifications.itemContentType { if (it.actors.size > 1) "group" else it.type },
                    ) { index ->
                        val group = notifications[index] ?: return@items
                        NotificationRow(group, onOpenPost = onOpenPost, onOpenProfile = onOpenProfile)
                    }
                    item(key = "append", contentType = "append") {
                        AppendState(notifications.loadState.append, onRetry = notifications::retry)
                    }
                }
            }
        }
    }
}

/**
 * One notification, or the several of one kind that [NotificationsPagingSource] folded together.
 * A single one shows its actor's avatar with a badge for what they did. A group shows the badge
 * alone and, above the summary line, a stack of the first avatars, each opening its profile.
 * Tapping opens the post, or the profile for a follow; several followers open a list to pick from.
 */
@Composable
private fun NotificationRow(
    group: NotificationGroup,
    onOpenPost: (postId: String, url: String?) -> Unit,
    onOpenProfile: (Author) -> Unit,
) {
    val item = group.newest
    val actors = group.actors
    val actor = actors.first()
    val name = actor.displayName
    val headline = headline(group)
    val text = item.post?.takeUnless { it.isEmpty }
    // Only a reply answers something; a mention's inReplyTo, if any, is the post it sits under.
    val replyingTo = item.inReplyTo?.takeUnless { it.isEmpty || item.type != Notification.Type.Reply }
    val isReplyLike = item.type == Notification.Type.Reply || item.type == Notification.Type.Mention
    val isGroup = actors.size > 1
    val listsFollowers = isGroup && item.type == Notification.Type.Follow
    var followersOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isGroup) AvatarStack(actors, onOpenProfile)
                // The time sits beside the headline's first line rather than centred on the whole item.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmojiText(
                        headline,
                        // The first name's emoji; a second name from another server shows its shortcodes as text.
                        actor.emojiSourceId,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).alignByBaseline(),
                    )
                    item.createdAtMillis?.let { millis ->
                        Text(
                            relativeTime(millis),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
            }
        },
        supportingContent = if (text != null || replyingTo != null) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (text != null) {
                        Text(
                            text.display(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (isReplyLike) 3 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (replyingTo != null) {
                        Text(
                            stringResource(R.string.notification_replying_to, replyingTo.display()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            null
        },
        leadingContent = {
            if (isGroup) {
                TypeBadge(item.type)
            } else {
                ActorAvatar(actor, item.type, name) { onOpenProfile(actor) }
            }
        },
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .clickable(onClickLabel = if (listsFollowers) stringResource(R.string.notification_show_people) else null) {
                // Follows have no post; so does anything the service sent without one.
                val postId = item.postId
                when {
                    listsFollowers -> followersOpen = true
                    item.type == Notification.Type.Follow || postId == null -> onOpenProfile(actor)
                    else -> onOpenPost(postId, item.postUrl)
                }
            },
    )
    if (followersOpen) {
        ActionSheet(onDismiss = { followersOpen = false }) {
            // A long run of followers must stay reachable, and the sheet itself doesn't scroll.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (follower in actors) {
                    SheetAction(
                        follower.displayName,
                        leadingContent = { Avatar(follower.avatarUrl, follower.displayName, 40.dp) },
                        supportingText = follower.handle,
                    ) { onOpenProfile(follower) }
                }
            }
        }
    }
}

/** The first few of a group's people as overlapping avatars; more than fit are in the summary's count. */
@Composable
private fun AvatarStack(actors: List<Author>, onOpenProfile: (Author) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(StackOverlap)) {
        for (actor in actors.take(MaxStackedAvatars)) {
            val name = actor.displayName
            Avatar(
                actor.avatarUrl,
                name,
                StackAvatarSize,
                Modifier
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .clickable(onClickLabel = stringResource(R.string.open_profile), role = Role.Button) { onOpenProfile(actor) }
                    .semantics { contentDescription = name },
            )
        }
    }
}

/** What a group did, as a round badge where a single notification has the actor's avatar. */
@Composable
private fun TypeBadge(type: Notification.Type) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(AvatarSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Decorative: the headline already says what happened.
            Icon(type.icon, contentDescription = null, modifier = Modifier.size(BadgeIconSize + 6.dp))
        }
    }
}

/** The actor's avatar with a badge for what they did, since the headline alone is easy to skim past. */
@Composable
private fun ActorAvatar(actor: Author, type: Notification.Type, name: String, onOpenProfile: () -> Unit) {
    Box(Modifier.size(AvatarSize)) {
        Avatar(
            actor.avatarUrl,
            name,
            AvatarSize,
            Modifier
                .clickable(onClickLabel = stringResource(R.string.open_profile), role = Role.Button, onClick = onOpenProfile)
                .semantics { contentDescription = name },
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp).size(BadgeSize),
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Decorative: the headline already says what happened.
                Icon(type.icon, contentDescription = null, modifier = Modifier.size(BadgeIconSize))
            }
        }
    }
}

/**
 * "<name> liked your post", or for a group "<a> and <b> …" / "<a>, <b> and 4 others …", with the
 * names in bold. Names are marked in the translated template and swapped in afterwards, so each
 * language keeps its own word order.
 */
@Composable
private fun headline(group: NotificationGroup): AnnotatedString {
    val type = group.type
    val names = group.actors.take(2).map { it.displayName }
    val pair = type.pairRes
    val many = type.manyRes
    val others = group.actors.size - 2
    val template = when {
        names.size == 1 || pair == null || many == null -> stringResource(type.headlineRes, nameMarker(0))
        others < 1 -> stringResource(pair, nameMarker(0), nameMarker(1))
        else -> pluralStringResource(many, others, nameMarker(0), nameMarker(1), others)
    }
    return remember(template, names) {
        buildAnnotatedString {
            var last = 0
            for (marker in NameMarker.findAll(template)) {
                append(template.substring(last, marker.range.first))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(names[marker.groupValues[1].toInt()]) }
                last = marker.range.last + 1
            }
            append(template.substring(last))
        }
    }
}

@Composable
private fun Excerpt.display(): String =
    if (sensitive) text.ifBlank { stringResource(R.string.post_sensitive) } else text

/** The "<name> liked your post" template for each kind; also used by the background announcements. */
internal val Notification.Type.headlineRes: Int
    get() = when (this) {
        Notification.Type.Like -> R.string.notification_liked
        Notification.Type.Repost -> R.string.notification_reposted
        Notification.Type.Reply -> R.string.notification_replied
        Notification.Type.Mention -> R.string.notification_mentioned
        Notification.Type.Follow, Notification.Type.Unknown -> R.string.notification_followed
    }

private val Notification.Type.icon: ImageVector
    get() = when (this) {
        Notification.Type.Like -> AppIcons.FavoriteFilled
        Notification.Type.Repost -> AppIcons.Repost
        Notification.Type.Reply -> AppIcons.Reply
        Notification.Type.Mention -> AppIcons.Mention
        Notification.Type.Follow, Notification.Type.Unknown -> AppIcons.Person
    }

/** The "<a> and <b> …" template for a group of two; null for kinds that are never grouped. */
private val Notification.Type.pairRes: Int?
    get() = when (this) {
        Notification.Type.Like -> R.string.notification_liked_two
        Notification.Type.Repost -> R.string.notification_reposted_two
        Notification.Type.Follow -> R.string.notification_followed_two
        Notification.Type.Reply, Notification.Type.Mention, Notification.Type.Unknown -> null
    }

/** The "<a>, <b> and N others …" plural for a larger group; null for kinds that are never grouped. */
private val Notification.Type.manyRes: Int?
    get() = when (this) {
        Notification.Type.Like -> R.plurals.notification_liked_many
        Notification.Type.Repost -> R.plurals.notification_reposted_many
        Notification.Type.Follow -> R.plurals.notification_followed_many
        Notification.Type.Reply, Notification.Type.Mention, Notification.Type.Unknown -> null
    }

// Stand in for the names while the template is split around them; never occur in translated text.
private fun nameMarker(index: Int) = "\u0000$index\u0000"
private val NameMarker = Regex("\u0000(\\d)\u0000")

private val AvatarSize = 40.dp
private val BadgeSize = 20.dp
private val BadgeIconSize = 14.dp
private val StackAvatarSize = 36.dp
private val StackOverlap = (-10).dp
private const val MaxStackedAvatars = 5
