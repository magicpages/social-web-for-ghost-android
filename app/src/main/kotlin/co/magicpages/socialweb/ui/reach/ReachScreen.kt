package co.magicpages.socialweb.ui.reach

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.serverOf
import co.magicpages.socialweb.data.feed.htmlToPlainText
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.reach.Interaction
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.CenteredLoading
import co.magicpages.socialweb.ui.components.FullScreenError
import co.magicpages.socialweb.ui.components.errorMessage
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.posts.displayName
import co.magicpages.socialweb.ui.posts.relativeTime
import co.magicpages.socialweb.ui.theme.Gutter
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** The site's reach lately, its top posts, and its recent posts, each opening how it travelled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReachScreen(onBack: () -> Unit, onOpenPostReach: (String) -> Unit, onSignOut: () -> Unit) {
    val viewModel = hiltViewModel<ReachViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReachScaffold(stringResource(R.string.reach_title), onBack) { padding ->
        when (val current = state) {
            ReachUiState.Loading -> CenteredLoading(Modifier.padding(padding))
            is ReachUiState.Failed -> FullScreenError(
                errorMessage(current.error),
                onRetry = viewModel::load,
                modifier = Modifier.padding(padding),
                sessionEnded = current.error is GhostException.Unauthorized,
                onSignOut = onSignOut,
            )
            is ReachUiState.Loaded -> PullToRefreshBox(
                isRefreshing = false,
                onRefresh = viewModel::load,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item(key = "summary") { SummaryCard(current.summary) }
                    if (current.top.isEmpty() && current.recent.isEmpty()) {
                        item(key = "nothing") { Note(stringResource(R.string.reach_nothing)) }
                    }
                    postSection("top", R.string.reach_top, current.top, onOpenPostReach)
                    postSection("recent", R.string.reach_recent, current.recent, onOpenPostReach)
                }
            }
        }
    }
}

/** "How it travelled": one of the site's posts, who passed it on, and how far that could have gone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostReachScreen(
    postId: String,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenProfile: (Author) -> Unit,
    onSignOut: () -> Unit,
) {
    val viewModel = hiltViewModel<PostReachViewModel, PostReachViewModel.Factory>(key = postId) { it.create(postId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReachScaffold(stringResource(R.string.reach_post_title), onBack) { padding ->
        when (val current = state) {
            PostReachUiState.Loading -> CenteredLoading(Modifier.padding(padding))
            is PostReachUiState.Failed -> FullScreenError(
                errorMessage(current.error),
                onRetry = viewModel::load,
                modifier = Modifier.padding(padding),
                sessionEnded = current.error is GhostException.Unauthorized,
                onSignOut = onSignOut,
            )
            is PostReachUiState.Loaded -> PullToRefreshBox(
                isRefreshing = false,
                onRefresh = viewModel::load,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                PostReachList(current.reach, onOpenPost, onOpenProfile)
            }
        }
    }
}

@Composable
private fun PostReachList(reach: PostReach, onOpenPost: (String) -> Unit, onOpenProfile: (Author) -> Unit) {
    val post = reach.post
    val activity = reach.activity
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "post") {
            val row = remember(post) { post.toReachPost() }
            Column(ContentColumn.padding(horizontal = Gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.text, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                row.publishedMillis?.let {
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onOpenPost(post.id) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.reach_open_post))
                }
            }
        }
        item(key = "counts") {
            Card {
                StatRow(post.repostCount, post.replyCount, post.likeCount)
                val people = activity.all.mapTo(HashSet()) { it.author.handle }.size
                if (people > 0) PeopleOnServers(people, activity.servers.size)
            }
        }
        reach.potentialReach?.let { potential ->
            item(key = "potential") { PotentialReach(potential, pendingReposters = activity.reposts.size > reach.reposterFollowers.size) }
        }
        if (reach.days.size > 1 && reach.days.any { it > 0 }) {
            item(key = "timeline") { Timeline(reach.days, reach.firstDayPercent) }
        }
        if (activity.reposts.isNotEmpty()) {
            sectionLabel("reposts-label", R.string.reach_reposted_by)
            val sorted = activity.reposts.sortedByDescending { reach.reposterFollowers[it.author.handle] ?: -1 }
            items(sorted, key = { "repost:" + it.author.handle }) { repost ->
                PersonRow(repost, reach.reposterFollowers[repost.author.handle], onOpenProfile)
            }
        }
        if (activity.replies.isNotEmpty()) {
            sectionLabel("replies-label", R.string.reach_replies)
            items(activity.replies, key = { "reply:" + it.reply?.id }) { reply ->
                ReplyRow(reply, onOpen = { reply.reply?.let { onOpenPost(it.id) } })
            }
        }
        if (activity.likes.isNotEmpty()) {
            sectionLabel("likes-label", R.string.reach_liked_by)
            items(activity.likes, key = { "like:" + it.author.handle }) { like -> PersonRow(like, null, onOpenProfile) }
        }
        if (activity.all.isEmpty() && post.repostCount + post.replyCount + post.likeCount == 0) {
            item(key = "nothing") { Note(stringResource(R.string.reach_nothing)) }
        }
        reach.listedSince?.let { since ->
            item(key = "since") {
                Note(stringResource(R.string.reach_listed_since, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(since))))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReachScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) {
                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        content = content,
    )
}

/** The column every block of these screens sits in. */
private val ContentColumn = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth()

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = ContentColumn.padding(horizontal = Gutter, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun SummaryCard(summary: ReachSummary) {
    Card {
        Text(
            pluralStringResource(R.plurals.reach_last_days, ReachViewModel.WindowDays, ReachViewModel.WindowDays),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        StatRow(summary.reposts, summary.replies, summary.likes)
        if (summary.people > 0) PeopleOnServers(summary.people, summary.servers)
        if (summary.partial) {
            Text(
                stringResource(R.string.reach_partial),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatRow(reposts: Int, replies: Int, likes: Int) {
    Row(Modifier.fillMaxWidth()) {
        Stat(reposts, R.plurals.reach_reposts, Modifier.weight(1f))
        Stat(replies, R.plurals.reach_replies_count, Modifier.weight(1f))
        Stat(likes, R.plurals.reach_likes, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(value: Int, label: Int, modifier: Modifier) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Column(modifier) {
        Text(numbers.format(value), style = MaterialTheme.typography.headlineMedium)
        Text(
            pluralStringResource(label, value),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PeopleOnServers(people: Int, servers: Int) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Text(
        stringResource(
            R.string.reach_people_on_servers,
            pluralStringResource(R.plurals.reach_people, people, numbers.format(people)),
            pluralStringResource(R.plurals.reach_servers, servers, numbers.format(servers)),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun PotentialReach(potential: Long, pendingReposters: Boolean) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Card {
        Text(
            pluralStringResource(R.plurals.reach_potential, potential.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), numbers.format(potential)),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(if (pendingReposters) R.string.reach_potential_counting else R.string.reach_potential_explained),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Interactions per day as bars, the first day on the left. */
@Composable
private fun Timeline(days: IntArray, firstDayPercent: Int?) {
    val resources = LocalResources.current
    val description = remember(days, resources) {
        days.withIndex().joinToString { (index, count) -> resources.getString(R.string.reach_day_count, index + 1, count) }
    }
    val most = days.max().coerceAtLeast(1)
    Card {
        Text(
            stringResource(R.string.reach_timeline),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            Modifier.fillMaxWidth().height(72.dp).clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEach { count ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            // An empty day still shows a sliver, so the days line up as a scale.
                            .fillMaxHeight((count.toFloat() / most).coerceAtLeast(0.04f))
                            .background(
                                if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                            ),
                    )
                }
            }
        }
        firstDayPercent?.let {
            Text(stringResource(R.string.reach_first_day, it), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun LazyListScope.sectionLabel(key: String, text: Int) {
    item(key = key) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = ContentColumn.padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 8.dp).semantics { heading() },
        )
    }
}

private fun LazyListScope.postSection(key: String, title: Int, posts: List<ReachPost>, onOpen: (String) -> Unit) {
    if (posts.isEmpty()) return
    sectionLabel("$key-label", title)
    items(posts, key = { "$key:" + it.id }) { post -> PostRow(post, onClick = { onOpen(post.id) }) }
}

@Composable
private fun PostRow(post: ReachPost, onClick: () -> Unit) {
    Row(
        ContentColumn
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Gutter, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                post.text,
                style = if (post.isArticle) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Count(AppIcons.Repost, post.reposts, R.plurals.reach_reposts)
                Count(AppIcons.Reply, post.replies, R.plurals.reach_replies_count)
                Count(AppIcons.Favorite, post.likes, R.plurals.reach_likes)
                post.publishedMillis?.let {
                    Text(relativeTime(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Count(icon: ImageVector, value: Int, label: Int) {
    val description = "$value ${pluralStringResource(label, value)}"
    Row(
        Modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PersonRow(interaction: Interaction, followers: Int?, onOpenProfile: (Author) -> Unit) {
    val author = interaction.author
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Row(
        ContentColumn
            .clickable(role = Role.Button) { onOpenProfile(author) }
            .padding(horizontal = Gutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(author.avatarUrl, author.displayName, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(author.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                author.handle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        followers?.let {
            Text(
                "${numbers.format(it)} ${pluralStringResource(R.plurals.profile_follower_count, it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReplyRow(interaction: Interaction, onOpen: () -> Unit) {
    val author = interaction.author
    val text = remember(interaction.reply) { interaction.reply?.content?.let(::htmlToPlainText).orEmpty() }
    Row(
        ContentColumn
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Gutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(author.avatarUrl, author.displayName, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${author.displayName} · ${serverOf(author.handle)}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = ContentColumn.padding(horizontal = Gutter, vertical = 16.dp),
    )
}
