package co.magicpages.socialweb.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.settings.AccountColors
import co.magicpages.socialweb.data.settings.AppSettings
import co.magicpages.socialweb.data.settings.ColorSource
import co.magicpages.socialweb.data.settings.parseHexColor
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.HandleText
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.theme.Gutter
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** A signed-in site, as its settings screen shows it, with its colours: the site's own accent colour (ARGB, if it has one) and the choice. */
data class ColorsUi(
    val accountId: String,
    val siteTitle: String,
    val siteUrl: String,
    val handle: String,
    val siteColor: Int?,
    val colors: AccountColors,
)

@HiltViewModel(assistedFactory = SiteSettingsViewModel.Factory::class)
class SiteSettingsViewModel @AssistedInject constructor(
    @Assisted private val accountId: String,
    private val settings: AppSettings,
    accounts: AccountStore,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(accountId: String): SiteSettingsViewModel
    }

    /** The account's site and colours; null until the accounts have been read, and once it's signed out. */
    val colors: StateFlow<ColorsUi?> = combine(accounts.accounts, settings.accountColors) { all, choices ->
        all.firstOrNull { it.id == accountId }?.let {
            ColorsUi(it.id, it.site.title, it.site.siteUrl, it.profile.handle, parseHexColor(it.site.accentColor), choices[it.id] ?: AccountColors())
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setColors(colors: AccountColors) = settings.setAccountColors(accountId, colors)
}

/**
 * The settings a signed-in site keeps for itself: the app's colours, blocks, sensitive media and
 * Bluesky sharing, then signing in to it again and removing it. Any site's, not just the active
 * one's; titled with the site, so it's clear whose they are.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SiteSettingsScreen(
    accountId: String,
    onBack: () -> Unit,
    onOpenBlocked: () -> Unit,
    onOpenIdentity: () -> Unit,
    onSignInAgain: (siteUrl: String) -> Unit,
    onRemove: () -> Unit,
) {
    val viewModel = hiltViewModel<SiteSettingsViewModel, SiteSettingsViewModel.Factory>(key = accountId) { it.create(accountId) }
    val site by viewModel.colors.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirmingRemove by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(site?.siteTitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = { site?.let { HandleText(it.handle) } },
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
            val ui = site ?: return@Box
            Column(
                Modifier
                    .widthIn(max = MaxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Gutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The handle readers know this site by, with the domain, aliases and follower move behind it.
                val identitySummary = stringResource(R.string.settings_identity_summary)
                SectionTitle(R.string.identity_handle)
                LinkRow(
                    title = stringResource(R.string.settings_identity),
                    status = ui.handle.ifBlank { identitySummary },
                    onClick = onOpenIdentity,
                    index = 0,
                    count = 1,
                    icon = AppIcons.Mention,
                )

                SectionTitle(R.string.settings_colors, Modifier.padding(top = 16.dp))
                ColorChoices(ui, viewModel::setColors)
                Explainer(stringResource(R.string.settings_colors_explainer, ui.siteTitle))

                SectionTitle(R.string.settings_safety, Modifier.padding(top = 16.dp))
                LinkRow(
                    title = stringResource(R.string.settings_blocked),
                    status = stringResource(R.string.settings_blocked_summary, ui.siteTitle),
                    onClick = onOpenBlocked,
                    index = 0,
                    count = 1,
                    icon = AppIcons.Block,
                )

                NetworkSettings(ui.accountId, ui.siteTitle)

                Box(Modifier.padding(top = 16.dp)) {
                    SegmentedGroup {
                        ActionRow(
                            title = stringResource(R.string.sign_in_again),
                            icon = AppIcons.Login,
                            onClick = { onSignInAgain(ui.siteUrl) },
                            index = 0,
                            count = 2,
                        )
                        ActionRow(
                            title = stringResource(R.string.settings_remove_site),
                            icon = AppIcons.Logout,
                            onClick = { confirmingRemove = true },
                            index = 1,
                            count = 2,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Explainer(stringResource(R.string.settings_site_actions_footer))
            }
            if (confirmingRemove) {
                AlertDialog(
                    onDismissRequest = { confirmingRemove = false },
                    title = { Text(stringResource(R.string.settings_remove_site_title, ui.siteTitle)) },
                    text = { Text(stringResource(R.string.settings_remove_site_body, ui.siteTitle)) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmingRemove = false
                            onRemove()
                        }) { Text(stringResource(R.string.settings_remove_site_confirm), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmingRemove = false }) { Text(stringResource(R.string.dialog_cancel)) }
                    },
                )
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
 * Site, device or custom colours as one single-choice segmented list. For the active site the theme
 * follows each choice at once, so the screen previews it. "Custom" asks for the colour first.
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
