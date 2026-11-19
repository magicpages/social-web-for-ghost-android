package co.magicpages.socialweb.ui.moderation

import android.content.ActivityNotFoundException
import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.moderation.ServerRule
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.errorMessage
import co.magicpages.socialweb.ui.posts.CollectMessages
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.posts.relativeTime
import co.magicpages.socialweb.ui.theme.Gutter
import kotlinx.coroutines.launch

/**
 * Reporting an account or one of its posts. Ghost's service has no report API, so the last step
 * hands the report to the reader's mail app, addressed to Magic Pages (and the account's server
 * when it publishes a moderator contact). "I don't like it" is no report at all: it only offers
 * unfollowing and blocking.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReportScreen(key: ReportKey, onBack: () -> Unit) {
    val viewModel = hiltViewModel<ReportViewModel, ReportViewModel.Factory>(key = key.toString()) { it.create(key) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val name = key.name.ifBlank { key.handle }
    var confirm by rememberSaveable { mutableStateOf<PersonalAction?>(null) }

    // The system back gesture steps back through the answers; from the first screen (and the thanks) it leaves.
    BackHandler(enabled = state.step != ReportStep.Reason && state.step != ReportStep.Done) { viewModel.back() }
    val up: () -> Unit = { if (!viewModel.back()) onBack() }

    val send: () -> Unit = {
        viewModel.draft()?.let { draft ->
            val email = buildReportEmail(resources, draft)
            try {
                context.startActivity(email.intent())
            } catch (_: ActivityNotFoundException) {
                // No mail app: the report goes to the clipboard, so it isn't lost.
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(resources.getString(R.string.report_clip_label), email.asText)))
                    snackbar.showSnackbar(resources.getString(R.string.report_no_mail_app, email.recipients.joinToString(", ")))
                }
            }
            viewModel.markSent()
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (key.postId != null) stringResource(R.string.action_report_post) else stringResource(R.string.action_report_account, name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = up) {
                        if (state.step == ReportStep.Reason || state.step == ReportStep.Done) {
                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.report_close))
                        } else {
                            Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Box(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Gutter, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label = when (state.step) {
                    ReportStep.Comment -> R.string.report_send
                    ReportStep.Personal, ReportStep.Done -> R.string.report_done
                    else -> R.string.report_next
                }
                Button(
                    onClick = {
                        when (state.step) {
                            ReportStep.Comment -> send()
                            ReportStep.Personal, ReportStep.Done -> onBack()
                            else -> viewModel.next()
                        }
                    },
                    enabled = state.step != ReportStep.Reason || (state.reason != null && !state.advancing),
                    modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
                ) {
                    if (state.advancing) LoadingIndicator(Modifier.size(ButtonDefaults.IconSize)) else Text(stringResource(label))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val path = state.path
            val position = path.indexOf(state.step).coerceAtLeast(0)
            val motion = MaterialTheme.motionScheme
            val progress by animateFloatAsState(
                targetValue = (position + 1f) / path.size,
                animationSpec = motion.defaultEffectsSpec<Float>(),
                label = "reportProgress",
            )
            val stepLabel = stringResource(R.string.report_step_progress, position + 1, path.size)
            LinearWavyProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp).semantics { contentDescription = stepLabel },
            )
            AnimatedContent(
                targetState = state.step,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
                contentAlignment = Alignment.TopCenter,
                label = "reportStep",
            ) { step ->
                Column(
                    Modifier
                        .widthIn(max = MaxContentWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Gutter, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (step) {
                        ReportStep.Reason -> ReasonStep(
                            title = if (key.postId != null) {
                                stringResource(R.string.report_reason_title_post)
                            } else {
                                stringResource(R.string.report_reason_title_account, name)
                            },
                            selected = state.reason,
                            onSelect = viewModel::selectReason,
                        )
                        ReportStep.Personal -> {
                            StepHeader(stringResource(R.string.report_personal_title), stringResource(R.string.report_personal_subtitle))
                            ModerationActions(state, name, key.handle, viewModel.host, onUnfollow = viewModel::unfollow, onBlock = { confirm = it })
                        }
                        ReportStep.Rules -> RulesStep(state.rules.orEmpty(), state.selectedRules, viewModel::toggleRule)
                        ReportStep.Posts -> PostsStep(state, viewModel::togglePost, viewModel::retryPosts)
                        ReportStep.Comment -> CommentStep(
                            comment = state.comment,
                            contact = state.contact,
                            forward = state.forward,
                            host = viewModel.host,
                            onComment = viewModel::setComment,
                            onForward = viewModel::setForward,
                        )
                        ReportStep.Done -> {
                            StepHeader(
                                stringResource(R.string.report_done_title),
                                stringResource(R.string.report_done_subtitle, name),
                            )
                            ModerationActions(state, name, key.handle, viewModel.host, onUnfollow = viewModel::unfollow, onBlock = { confirm = it })
                        }
                    }
                }
            }
        }
    }

    confirm?.let { action ->
        BlockDialog(
            target = if (action == PersonalAction.BlockServer) BlockTarget.Server(viewModel.host) else BlockTarget.Account(key.handle),
            onConfirm = {
                confirm = null
                if (action == PersonalAction.BlockServer) viewModel.blockServer() else viewModel.block()
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun StepHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Step 1: one reason, as a single-choice list. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReasonStep(title: String, selected: ReportReason?, onSelect: (ReportReason) -> Unit) {
    StepHeader(title, stringResource(R.string.report_reason_subtitle))
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        ReportReason.entries.forEachIndexed { index, reason ->
            val isSelected = selected == reason
            SegmentedListItem(
                onClick = { onSelect(reason) },
                shapes = ListItemDefaults.segmentedShapes(index, ReportReason.entries.size),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                // One radio button to screen readers, not a button plus a separate radio.
                modifier = Modifier.semantics(mergeDescendants = true) {
                    role = Role.RadioButton
                    this.selected = isSelected
                },
                supportingContent = { Text(stringResource(reason.summary)) },
                trailingContent = { RadioButton(selected = isSelected, onClick = null) },
            ) {
                Text(stringResource(reason.title))
            }
        }
    }
}

/** Step 2: the rules the server publishes, to name those broken. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RulesStep(rules: List<ServerRule>, selected: Set<String>, onToggle: (String) -> Unit) {
    StepHeader(stringResource(R.string.report_rules_title), stringResource(R.string.report_select_all))
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rules.forEachIndexed { index, rule ->
            CheckRow(
                checked = rule.id in selected,
                index = index,
                count = rules.size,
                onToggle = { onToggle(rule.id) },
                supporting = rule.hint.takeIf { it.isNotBlank() },
            ) {
                Text(rule.text)
            }
        }
    }
}

/** Step 3: posts of the account that back the report up; the reported one is already chosen. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PostsStep(state: ReportUiState, onToggle: (String) -> Unit, onRetry: () -> Unit) {
    StepHeader(stringResource(R.string.report_posts_title), stringResource(R.string.report_select_all))
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        state.posts.forEachIndexed { index, post ->
            CheckRow(
                checked = post.id in state.selectedPosts,
                index = index,
                count = state.posts.size,
                onToggle = { onToggle(post.id) },
                supporting = post.millis?.let(::relativeTime),
            ) {
                Text(post.excerpt.ifBlank { post.url }, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    when (val status = state.postsStatus) {
        PostsStatus.Loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        PostsStatus.Loaded -> if (state.posts.isEmpty()) {
            Text(
                stringResource(R.string.report_posts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is PostsStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.report_posts_failed) + " " + errorMessage(status.error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

/** One multi-choice row of a segmented list: the whole row toggles. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CheckRow(
    checked: Boolean,
    index: Int,
    count: Int,
    onToggle: () -> Unit,
    supporting: String?,
    headline: @Composable () -> Unit,
) {
    SegmentedListItem(
        onClick = onToggle,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.semantics(mergeDescendants = true) {
            role = Role.Checkbox
            toggleableState = ToggleableState(checked)
        },
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        supportingContent = supporting?.let { text -> { Text(text) } },
    ) {
        headline()
    }
}

/** Step 4: anything to add, and whether the account's server's moderators get a copy. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CommentStep(
    comment: String,
    contact: ContactState,
    forward: Boolean,
    host: String,
    onComment: (String) -> Unit,
    onForward: (Boolean) -> Unit,
) {
    StepHeader(stringResource(R.string.report_comment_title))
    // The field keeps its own text (and survives rotation); the view model gets a copy for the email.
    val text = rememberTextFieldState(comment)
    LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect { onComment(it) } }
    OutlinedTextField(
        state = text,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.report_comment_label)) },
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 10),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
    when (contact) {
        ContactState.Pending -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LoadingIndicator(Modifier.size(32.dp))
            Text(
                stringResource(R.string.report_forward_looking, host),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ContactState.None -> Text(
            stringResource(R.string.report_forward_none, host),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is ContactState.Found -> SegmentedListItem(
            onClick = { onForward(!forward) },
            shapes = ListItemDefaults.segmentedShapes(0, 1),
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = ToggleableState(forward)
            },
            supportingContent = { Text(stringResource(R.string.report_forward_summary, host)) },
            trailingContent = {
                Switch(
                    checked = forward,
                    onCheckedChange = null,
                    thumbContent = if (forward) {
                        { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            },
        ) {
            Text(stringResource(R.string.report_forward_title, host))
        }
    }
}

/**
 * What the reader can do about the account itself: unfollow it (only when followed), block it,
 * block its server. A block is confirmed first ([onBlock] asks the screen to), and one already in
 * place shows as a note instead of a button.
 */
@Composable
private fun ModerationActions(
    state: ReportUiState,
    name: String,
    handle: String,
    host: String,
    onUnfollow: () -> Unit,
    onBlock: (PersonalAction) -> Unit,
) {
    val idle = state.busy == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.standing.followed) {
            ActionButton(
                icon = AppIcons.PersonRemove,
                label = stringResource(R.string.report_unfollow, name),
                busy = state.busy == PersonalAction.Unfollow,
                enabled = idle,
                destructive = false,
                onClick = onUnfollow,
            )
        }
        if (state.standing.blocked) {
            AlreadyBlocked(handle)
        } else {
            ActionButton(
                icon = AppIcons.Block,
                label = stringResource(R.string.action_block_account, handle),
                busy = state.busy == PersonalAction.Block,
                enabled = idle,
                destructive = true,
                onClick = { onBlock(PersonalAction.Block) },
            )
        }
        if (state.standing.serverBlocked) {
            AlreadyBlocked(host)
        } else {
            ActionButton(
                icon = AppIcons.Block,
                label = stringResource(R.string.action_block_server, host),
                busy = state.busy == PersonalAction.BlockServer,
                enabled = idle,
                destructive = true,
                onClick = { onBlock(PersonalAction.BlockServer) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionButton(icon: ImageVector, label: String, busy: Boolean, enabled: Boolean, destructive: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = if (destructive) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
    ) {
        if (busy) {
            LoadingIndicator(Modifier.size(ButtonDefaults.IconSize))
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        }
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** [what] (an account's handle or a server) is blocked already. */
@Composable
private fun AlreadyBlocked(what: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.report_already_blocked, what),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
