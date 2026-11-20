package co.magicpages.socialweb.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.background.NotificationAnnouncer
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.Notification
import co.magicpages.socialweb.data.settings.AccountColors
import co.magicpages.socialweb.data.settings.AppSettings
import co.magicpages.socialweb.data.settings.ColorSource
import co.magicpages.socialweb.data.settings.parseHexColor
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.feed.FeedFiltersScreen
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.theme.Gutter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The colours section: whose they are, the site's own accent colour (ARGB, if it has one) and the choice. */
data class ColorsUi(val accountId: String, val siteTitle: String, val siteColor: Int?, val colors: AccountColors)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettings,
    private val announcer: NotificationAnnouncer,
    accounts: AccountStore,
) : ViewModel() {
    val autoplayVideos: StateFlow<Boolean> = settings.autoplayVideos
    val announcedTypes: StateFlow<Set<Notification.Type>> = settings.announcedTypes

    /** The active account's colours; null until the account has been read. */
    val colors: StateFlow<ColorsUi?> = combine(accounts.account, settings.accountColors) { account, choices ->
        account?.let { ColorsUi(it.id, it.site.title, parseHexColor(it.site.accentColor), choices[it.id] ?: AccountColors()) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _notificationsAllowed = MutableStateFlow(announcer.canAnnounce())

    /** Whether Android would show a system notification right now. It changes outside the app, so see [refreshNotificationsAllowed]. */
    val notificationsAllowed: StateFlow<Boolean> = _notificationsAllowed.asStateFlow()

    /** The permission and channel are changed in Android's settings: look again when the screen is shown. */
    fun refreshNotificationsAllowed() {
        _notificationsAllowed.value = announcer.canAnnounce()
    }

    fun setAutoplayVideos(enabled: Boolean) = settings.setAutoplayVideos(enabled)

    fun setAnnounced(type: Notification.Type, enabled: Boolean) = settings.setAnnounced(type, enabled)

    fun setColors(colors: AccountColors) {
        val id = this.colors.value?.accountId ?: return
        settings.setAccountColors(id, colors)
    }
}

/** Each switchable kind of notification with its label, in the order the screen lists them. */
private val AnnouncedTypeLabels: List<Pair<Notification.Type, Int>> = listOf(
    Notification.Type.Reply to R.string.settings_notify_replies,
    Notification.Type.Mention to R.string.settings_notify_mentions,
    Notification.Type.Like to R.string.settings_notify_likes,
    Notification.Type.Repost to R.string.settings_notify_reposts,
    Notification.Type.Follow to R.string.settings_notify_follows,
)

/** Settings, with the feed filters laid over it when opened from its row: they have no screen of their own to navigate to. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenBlocked: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var showFeedFilters by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        SettingsContent(onBack, onOpenBlocked, onOpenAbout, onOpenFeedFilters = { showFeedFilters = true }, viewModel = viewModel)
        if (showFeedFilters) FeedFiltersScreen(onBack = { showFeedFilters = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsContent(
    onBack: () -> Unit,
    onOpenBlocked: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenFeedFilters: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val autoplay by viewModel.autoplayVideos.collectAsStateWithLifecycle()
    val announced by viewModel.announcedTypes.collectAsStateWithLifecycle()
    val notificationsAllowed by viewModel.notificationsAllowed.collectAsStateWithLifecycle()
    val colors by viewModel.colors.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshNotificationsAllowed()
        onPauseOrDispose {}
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = MaxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Gutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                colors?.let { ui ->
                    SectionTitle(R.string.settings_colors)
                    Text(
                        stringResource(R.string.settings_colors_explainer, ui.siteTitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    ColorChoices(ui, viewModel::setColors)
                }

                colors?.let { ui ->
                    SectionTitle(R.string.settings_safety, Modifier.padding(top = 16.dp))
                    Column {
                        SegmentedListItem(
                            onClick = onOpenBlocked,
                            shapes = ListItemDefaults.segmentedShapes(0, 1),
                            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            leadingContent = { Icon(AppIcons.Block, contentDescription = null) },
                            supportingContent = { Text(stringResource(R.string.settings_blocked_summary, ui.siteTitle)) },
                        ) {
                            Text(stringResource(R.string.settings_blocked))
                        }
                    }
                }

                SectionTitle(R.string.settings_notifications, Modifier.padding(top = if (colors != null) 16.dp else 0.dp))
                Text(
                    stringResource(R.string.settings_notifications_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                if (!notificationsAllowed) NotificationsBlocked()
                SwitchGroup(
                    AnnouncedTypeLabels.map { (type, label) ->
                        SwitchRow(label, null, type in announced) { viewModel.setAnnounced(type, it) }
                    },
                )

                Column(Modifier.padding(top = 16.dp)) {
                    SegmentedListItem(
                        onClick = onOpenFeedFilters,
                        shapes = ListItemDefaults.segmentedShapes(0, 1),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        leadingContent = { Icon(AppIcons.VisibilityOff, contentDescription = null) },
                        supportingContent = { Text(stringResource(R.string.feed_filters_summary)) },
                    ) {
                        Text(stringResource(R.string.feed_filters_title))
                    }
                }

                SectionTitle(R.string.settings_video, Modifier.padding(top = 16.dp))
                SwitchGroup(
                    listOf(
                        SwitchRow(R.string.settings_autoplay_videos, R.string.settings_autoplay_videos_summary, autoplay, viewModel::setAutoplayVideos),
                    ),
                )

                Column(Modifier.padding(top = 16.dp)) {
                    SegmentedListItem(
                        onClick = onOpenAbout,
                        shapes = ListItemDefaults.segmentedShapes(0, 1),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        leadingContent = { Icon(AppIcons.Info, contentDescription = null) },
                        supportingContent = { Text(stringResource(R.string.settings_about_summary)) },
                    ) {
                        Text(stringResource(R.string.settings_about))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

private class SwitchRow(
    @StringRes val label: Int,
    @StringRes val summary: Int?,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
)

/** Switches as one M3 segmented list; the whole row toggles. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwitchGroup(rows: List<SwitchRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { index, row ->
            SegmentedListItem(
                onClick = { row.onCheckedChange(!row.checked) },
                shapes = ListItemDefaults.segmentedShapes(index, rows.size),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                // Reads as one switch to screen readers, not a button plus a separate switch.
                modifier = Modifier.semantics(mergeDescendants = true) {
                    role = Role.Switch
                    toggleableState = ToggleableState(row.checked)
                },
                supportingContent = row.summary?.let { summary -> { Text(stringResource(summary)) } },
                trailingContent = {
                    Switch(
                        checked = row.checked,
                        onCheckedChange = null,
                        thumbContent = if (row.checked) {
                            { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                        } else {
                            null
                        },
                    )
                },
            ) {
                Text(stringResource(row.label))
            }
        }
    }
}

/** Shown while Android won't display this app's notifications: the choices below can't take effect until that's fixed there. */
@Composable
private fun NotificationsBlocked() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.NotificationsOff, contentDescription = null)
                Text(stringResource(R.string.settings_notifications_blocked_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.settings_notifications_blocked_body), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            ) {
                Text(stringResource(R.string.settings_open_notification_settings))
            }
        }
    }
}

/** The swatches of the colour picker, with their names for screen readers. */
private val PresetColors: List<Pair<Int, Int>> = listOf(
    0xFFFF1A75.toInt() to R.string.color_pink,
    0xFFE53935.toInt() to R.string.color_red,
    0xFFF4511E.toInt() to R.string.color_orange,
    0xFFFFB300.toInt() to R.string.color_amber,
    0xFF43A047.toInt() to R.string.color_green,
    0xFF00897B.toInt() to R.string.color_teal,
    0xFF00ACC1.toInt() to R.string.color_cyan,
    0xFF1E88E5.toInt() to R.string.color_blue,
    0xFF3949AB.toInt() to R.string.color_indigo,
    0xFF8E24AA.toInt() to R.string.color_purple,
    0xFF6D4C41.toInt() to R.string.color_brown,
    0xFF546E7A.toInt() to R.string.color_gray,
)

private fun hexDigits(argb: Int): String = "%06x".format(argb and 0xFFFFFF)

/**
 * Site, device or custom colours as one single-choice segmented list. The theme follows each choice
 * at once, so the screen previews it. "Custom" asks for the colour first.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColorChoices(ui: ColorsUi, onChange: (AccountColors) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val deviceSummary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.settings_colors_device_wallpaper else R.string.settings_colors_device_default
    val deviceColor = deviceColorSample()
    val rows = listOf(
        // A site without an accent colour gets the device's colours, so that's what its sample shows.
        Triple(ColorSource.Site, R.string.settings_colors_site, ui.siteColor ?: deviceColor),
        Triple(ColorSource.Device, R.string.settings_colors_device, deviceColor),
        Triple(ColorSource.Custom, R.string.settings_colors_custom, ui.colors.custom),
    )
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { index, (source, label, swatch) ->
            val selected = ui.colors.source == source
            val summary = when (source) {
                ColorSource.Site -> stringResource(if (ui.siteColor != null) R.string.settings_colors_site_summary else R.string.settings_colors_site_missing)
                ColorSource.Device -> stringResource(deviceSummary)
                ColorSource.Custom -> ui.colors.custom?.let { "#" + hexDigits(it).uppercase() } ?: stringResource(R.string.settings_colors_custom_summary)
            }
            SegmentedListItem(
                onClick = { if (source == ColorSource.Custom) picking = true else onChange(ui.colors.copy(source = source)) },
                shapes = ListItemDefaults.segmentedShapes(index, rows.size),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.semantics(mergeDescendants = true) {
                    role = Role.RadioButton
                    this.selected = selected
                },
                leadingContent = { Swatch(swatch, Modifier.size(32.dp)) },
                supportingContent = { Text(summary) },
                trailingContent = { RadioButton(selected = selected, onClick = null) },
            ) {
                Text(stringResource(label))
            }
        }
    }
    if (picking) {
        ColorPickerDialog(
            initial = ui.colors.custom ?: ui.siteColor,
            onPick = { color ->
                picking = false
                onChange(AccountColors(ColorSource.Custom, color))
            },
            onDismiss = { picking = false },
        )
    }
}

/** The primary colour Android's own colours would give the app: the wallpaper's on Android 12+. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun deviceColorSample(): Int {
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        darkColorScheme()
    } else {
        expressiveLightColorScheme()
    }
    return scheme.primary.toArgb()
}

/** A round colour sample; an empty ring while there is no [color] yet. */
@Composable
private fun Swatch(color: Int?, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier
            .clip(CircleShape)
            .then(if (color != null) Modifier.background(Color(color)) else Modifier)
            .border(1.dp, outline, CircleShape),
    )
}

/** Picks a colour from [PresetColors] or as a hex code; the two stay in step. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerDialog(initial: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var hex by rememberSaveable { mutableStateOf(initial?.let(::hexDigits).orEmpty()) }
    val parsed = parseHexColor(hex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetColors.forEach { (color, name) ->
                        val selected = parsed == color
                        val description = stringResource(name)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { hex = hexDigits(color) })
                                .semantics { contentDescription = description },
                        ) {
                            Swatch(color, Modifier.size(40.dp))
                            if (selected) {
                                Icon(
                                    AppIcons.Check,
                                    contentDescription = null,
                                    tint = if (Color(color).luminance() > 0.5f) Color.Black else Color.White,
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { value -> hex = value.trim().removePrefix("#").take(6) },
                    label = { Text(stringResource(R.string.color_picker_hex)) },
                    prefix = { Text("#") },
                    trailingIcon = parsed?.let { color -> { Swatch(color, Modifier.size(24.dp)) } },
                    isError = hex.isNotEmpty() && parsed == null,
                    supportingText = if (hex.isNotEmpty() && parsed == null) {
                        { Text(stringResource(R.string.color_picker_hex_invalid)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { parsed?.let(onPick) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onPick) }, enabled = parsed != null) {
                Text(stringResource(R.string.color_picker_use))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
