package co.magicpages.socialweb.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.PublishingRhythm
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.CenteredLoading
import co.magicpages.socialweb.ui.components.EmojiText
import co.magicpages.socialweb.ui.components.FullScreenError
import co.magicpages.socialweb.ui.components.HandleText
import co.magicpages.socialweb.ui.components.bioHtml
import co.magicpages.socialweb.ui.components.emojiSourceId
import co.magicpages.socialweb.ui.components.errorMessage
import co.magicpages.socialweb.ui.components.parsePostHtml
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.posts.displayName
import co.magicpages.socialweb.ui.profile.RecommendationDialog
import co.magicpages.socialweb.ui.theme.Gutter

/**
 * The Neighbours tab: writers in the site's own topics that it doesn't follow yet, as cards that say what is
 * known about them (their newest article, how often they write, whether their site recommends this one) and
 * offer to follow them or recommend their site. [details] fill in as cards come into view, through [onLoadDetails].
 * There is no count of followers or anything to rank them by, and no way to act on all of them at once.
 */
@Composable
internal fun NeighboursList(
    state: NeighboursUiState,
    following: Map<String, Boolean>,
    details: Map<String, NeighbourDetails>,
    recommendStates: RecommendStates,
    listState: LazyListState,
    onToggleFollow: (Author) -> Unit,
    onRecommend: (Neighbour) -> Unit,
    onLoadDetails: (Neighbour) -> Unit,
    onOpenProfile: (Author) -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    when (state) {
        // The tab is gone by the time this would show; there is nothing to say about it.
        NeighboursUiState.Loading, NeighboursUiState.Unavailable -> CenteredLoading()
        is NeighboursUiState.Failed -> FullScreenError(
            errorMessage(state.error),
            onRetry = onRetry,
            sessionEnded = state.error is GhostException.Unauthorized,
            onSignOut = onSignOut,
        )
        is NeighboursUiState.Loaded -> {
            // Recommending is public to the site's readers and tells the other site, so it asks first.
            var pending by remember { mutableStateOf<Neighbour?>(null) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item(key = "intro", contentType = "intro") {
                    Text(
                        stringResource(R.string.neighbours_intro),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .widthIn(max = MaxContentWidth)
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .semantics { heading() },
                    )
                }
                items(state.neighbours, key = { it.account.handle.lowercase() }, contentType = { "neighbour" }) { neighbour ->
                    val key = neighbour.account.handle.lowercase()
                    // Cards compose as they come into view, which is when their facts are worth a request.
                    LaunchedEffect(key) { onLoadDetails(neighbour) }
                    NeighbourCard(
                        neighbour = neighbour,
                        following = following,
                        details = details[key],
                        recommend = recommendStates.of(neighbour.siteUrl),
                        onToggleFollow = onToggleFollow,
                        onRecommend = { pending = neighbour },
                        onOpenProfile = onOpenProfile,
                    )
                }
            }
            pending?.let { neighbour ->
                RecommendationDialog(
                    recommended = false,
                    name = neighbour.account.toAuthor().displayName,
                    onConfirm = {
                        onRecommend(neighbour)
                        pending = null
                    },
                    onDismiss = { pending = null },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeighbourCard(
    neighbour: Neighbour,
    following: Map<String, Boolean>,
    details: NeighbourDetails?,
    recommend: RecommendState,
    onToggleFollow: (Author) -> Unit,
    onRecommend: () -> Unit,
    onOpenProfile: (Author) -> Unit,
) {
    val account = neighbour.account
    val author = remember(account) { account.toAuthor() }
    val name = author.displayName
    val bio = remember(account.bio) { parsePostHtml(bioHtml(account.bio)).text.trim() }
    Card(
        onClick = { onOpenProfile(author) },
        modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(author.avatarUrl, name, 48.dp)
                Column(Modifier.weight(1f)) {
                    EmojiText(name, author.emojiSourceId, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HandleText(
                        author.handle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (bio.isNotEmpty()) {
                EmojiText(bio, author.emojiSourceId, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (details != null) NeighbourLines(details)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                FollowButton(name, following.isFollowing(author), onClick = { onToggleFollow(author) })
                RecommendButton(name, recommend, onRecommend)
            }
        }
    }
}

/** What is known about the neighbour beyond their profile; each line only when it is a fact that was found. */
@Composable
private fun NeighbourLines(details: NeighbourDetails) {
    val title = details.latestArticleTitle
    val rhythm = details.rhythm
    if (title == null && rhythm == null && !details.recommendsYou) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null) {
            Text(
                stringResource(R.string.neighbours_latest, title),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (rhythm != null) {
            Text(
                stringResource(rhythm.label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (details.recommendsYou) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.StarFilled, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text(
                    stringResource(R.string.neighbours_recommends_you),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/** Offers the neighbour's site as a recommendation; once done it says so, and with nothing to offer it isn't there. */
@Composable
private fun RecommendButton(name: String, state: RecommendState, onClick: () -> Unit) {
    if (state == RecommendState.Hidden) return
    val description = stringResource(R.string.neighbours_recommend_account, name)
    val content: @Composable RowScope.() -> Unit = {
        Icon(
            if (state == RecommendState.Done) AppIcons.StarFilled else AppIcons.Star,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(if (state == RecommendState.Done) R.string.profile_recommended else R.string.profile_recommend), maxLines = 1)
    }
    val modifier = Modifier.semantics { contentDescription = description }
    if (state == RecommendState.Done) {
        // Settled, so tonal like "Following"; there is nothing left to press.
        FilledTonalButton(
            onClick = {},
            modifier = modifier,
            enabled = false,
            colors = ButtonDefaults.filledTonalButtonColors(
                disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            content = content,
        )
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, enabled = state == RecommendState.Available, content = content)
    }
}

private val PublishingRhythm.label: Int
    get() = when (this) {
        PublishingRhythm.Daily -> R.string.neighbours_rhythm_daily
        PublishingRhythm.SeveralTimesAWeek -> R.string.neighbours_rhythm_several_a_week
        PublishingRhythm.Weekly -> R.string.neighbours_rhythm_weekly
        PublishingRhythm.EveryTwoWeeks -> R.string.neighbours_rhythm_every_two_weeks
        PublishingRhythm.Monthly -> R.string.neighbours_rhythm_monthly
    }
