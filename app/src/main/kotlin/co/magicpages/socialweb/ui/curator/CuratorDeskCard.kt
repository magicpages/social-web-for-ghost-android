package co.magicpages.socialweb.ui.curator

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.curator.deskSite
import co.magicpages.socialweb.data.curator.publishedMillis
import co.magicpages.socialweb.ui.components.ActionSheet
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.EmojiText
import co.magicpages.socialweb.ui.components.HandleText
import co.magicpages.socialweb.ui.components.SheetAction
import co.magicpages.socialweb.ui.components.emojiSourceId
import co.magicpages.socialweb.ui.posts.Cover
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.posts.displayName
import co.magicpages.socialweb.ui.posts.relativeTime
import co.magicpages.socialweb.ui.quote.rememberQuoteCardPalette
import co.magicpages.socialweb.ui.theme.Gutter

/**
 * Keeps the curator desk's loading in step with the screen it is on: asks for today's posts whenever the screen comes
 * to the front (which is also how a new day starts), and once the screen goes away with the day's end on show,
 * tells the desk so, which then stays away for the rest of the day. Not part of [CuratorDeskCard], which
 * a list scrolls in and out of.
 */
@Composable
fun CuratorDeskEffects(viewModel: CuratorDeskViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val finished by rememberUpdatedState(ui is CuratorUi.Finished)
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { if (finished) viewModel.acknowledgeFinished() }
    }
}

/**
 * The curator desk: one card at the top of the Reader that puts a few posts from the writer's topics in front of
 * them, one at a time, to repost, answer or skip. It asks for the topics first. Draws nothing when it has nothing to
 * offer, which includes any failure: an offer isn't worth an error.
 */
@Composable
fun CuratorDeskCard(
    viewModel: CuratorDeskViewModel,
    onOpenPost: (Post) -> Unit,
    onOpenProfile: (Author) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    AnimatedContent(
        targetState = ui,
        modifier = modifier,
        contentKey = { state ->
            when (state) {
                is CuratorUi.Review -> state.post.id
                is CuratorUi.Choose -> "choose"
                CuratorUi.Finished -> "finished"
                CuratorUi.Hidden -> "hidden"
            }
        },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "curator desk",
    ) { state ->
        when (state) {
            CuratorUi.Hidden -> Unit
            is CuratorUi.Choose -> DeskFrame { ChooseTopics(state, viewModel) }
            is CuratorUi.Review -> DeskFrame { Review(state, viewModel, onOpenPost, onOpenProfile) }
            CuratorUi.Finished -> DeskFrame { Finished(viewModel) }
        }
    }
}

/** The card itself, as wide as the Reader's cards and set off from the page like a settings group. */
@Composable
private fun DeskFrame(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .padding(horizontal = Gutter, vertical = 8.dp)
            .animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun ChooseTopics(state: CuratorUi.Choose, viewModel: CuratorDeskViewModel) {
    // Starts from what is chosen now, and is the writer's own until they save.
    var selected by rememberSaveable(state.selected) { mutableStateOf<Set<String>>(state.selected) }
    Text(
        stringResource(R.string.curator_choose_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        stringResource(R.string.curator_choose_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CuratorTopicChips(state.topics, selected, onChange = { selected = it })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = if (state.editing) viewModel::cancelEditing else viewModel::notNow) {
            Text(stringResource(if (state.editing) R.string.dialog_cancel else R.string.curator_not_now))
        }
        Button(
            onClick = { viewModel.saveTopics(state.topics.filter { it.slug in selected }) },
            enabled = state.topics.any { it.slug in selected },
        ) { Text(stringResource(R.string.curator_topics_save)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun Review(
    state: CuratorUi.Review,
    viewModel: CuratorDeskViewModel,
    onOpenPost: (Post) -> Unit,
    onOpenProfile: (Author) -> Unit,
) {
    val post = state.post
    val position = state.index + 1
    val palette = rememberQuoteCardPalette()
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            deskTitle(state),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(
            stringResource(R.string.curator_progress, position, state.total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { menuOpen = true }) {
            Icon(AppIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
    }
    val progressLabel = stringResource(R.string.curator_progress_description, position, state.total)
    LinearWavyProgressIndicator(
        progress = { position / state.total.toFloat() },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressLabel },
    )

    // The post opens as it does in the Reader; its author's name and picture open their profile instead.
    Column(
        Modifier.clip(MaterialTheme.shapes.large).clickable { onOpenPost(post) },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Byline(post, onOpenProfile)
        Text(
            post.title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (post.excerpt.isNotBlank()) {
            Text(
                post.excerpt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!post.featureImageUrl.isNullOrBlank()) {
            Cover(post, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium))
        }
    }

    // After a few reposts in a day the card steers towards saying something: a feed of reposts is not a conversation.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val repost = @Composable {
            DeskButton(
                label = stringResource(R.string.action_repost),
                icon = AppIcons.Repost,
                primary = !state.thoughtFirst,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    viewModel.repost(post, state.index)
                },
            )
        }
        val thought = @Composable {
            DeskButton(
                label = stringResource(R.string.curator_thought),
                icon = AppIcons.Edit,
                primary = state.thoughtFirst,
                onClick = { viewModel.addThought(post, state.index, palette) },
            )
        }
        if (state.thoughtFirst) {
            thought()
            repost()
        } else {
            repost()
            thought()
        }
        TextButton(onClick = { viewModel.skip(state.index) }) { Text(stringResource(R.string.curator_skip)) }
    }

    if (menuOpen) {
        ActionSheet(onDismiss = { menuOpen = false }) {
            SheetAction(stringResource(R.string.curator_menu_topics), AppIcons.Edit) { viewModel.editTopics() }
            SheetAction(stringResource(R.string.curator_menu_hide_site, deskSite(post)), AppIcons.VisibilityOff) {
                viewModel.hideSite(state.index)
            }
            SheetAction(stringResource(R.string.curator_menu_skip_all), AppIcons.Close) { viewModel.skipAll() }
        }
    }
}

/** What the card is titled: the topic, or, when it had to turn to the default topic, saying so. */
@Composable
private fun deskTitle(state: CuratorUi.Review): String = when {
    state.fellBack && state.topicName != null -> stringResource(R.string.curator_fallback_topic, state.topicName)
    state.fellBack -> stringResource(R.string.curator_fallback_topics)
    state.topicName != null -> stringResource(R.string.curator_title_topic, state.topicName)
    else -> stringResource(R.string.curator_title_topics)
}

/** The post's author, picture and name and handle, and when it came out; tapping it opens the author's profile. */
@Composable
private fun Byline(post: Post, onOpenProfile: (Author) -> Unit) {
    val author = post.author
    val date = remember(post.publishedAt) { publishedMillis(post).takeIf { it != Long.MIN_VALUE }?.let(::relativeTime) }
    Row(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(onClickLabel = stringResource(R.string.action_open_profile)) { onOpenProfile(author) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(author.avatarUrl, author.displayName, 36.dp)
        Column(Modifier.weight(1f)) {
            EmojiText(
                author.displayName,
                emojiSourceId = author.emojiSourceId,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                HandleText(
                    author.handle,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (date != null) {
                    Text(
                        " · $date",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A button of the card's answers: filled when it is the one the card asks for, tonal when it is the other. */
@Composable
private fun DeskButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(label)
    }
    if (primary) {
        Button(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { content() }
    } else {
        FilledTonalButton(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { content() }
    }
}

/** The day's end. Another round is there for the writer who wants it, never offered more loudly than going away. */
@Composable
private fun Finished(viewModel: CuratorDeskViewModel) {
    val more by viewModel.more.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(if (more == CuratorMore.NothingLeft) R.string.curator_nothing_left else R.string.curator_done),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (more != CuratorMore.NothingLeft) {
            TextButton(onClick = viewModel::again, enabled = more == CuratorMore.Offered) {
                Text(stringResource(R.string.curator_more))
            }
        }
        TextButton(onClick = viewModel::hide) { Text(stringResource(R.string.curator_hide)) }
    }
}
