package co.magicpages.socialweb.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.translation.SupportedLanguages
import co.magicpages.socialweb.data.translation.TextTranslator
import co.magicpages.socialweb.data.translation.TranslationLanguage
import co.magicpages.socialweb.data.translation.TranslationSettings
import co.magicpages.socialweb.data.translation.translationLanguage
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.theme.Gutter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A language to translate into: its BCP 47 [tag] ("zh-Hant" picks the script) and its name in the app's language. */
@Immutable
data class TargetChoice(val tag: String, val name: String)

/** The languages on the device and what's being downloaded; [downloaded] is null until it has been looked at. */
@Immutable
data class DownloadsUi(
    val downloaded: List<TranslationLanguage>? = null,
    /** Model code to name, for the languages being downloaded. */
    val downloading: Map<String, String> = emptyMap(),
    val usedBytes: Long = 0,
)

/** The languages that can be downloaded, as the "Download a language" dialog shows them. */
@Immutable
sealed interface DownloadableUi {
    data object Loading : DownloadableUi

    data object Failed : DownloadableUi

    data class Loaded(val languages: List<TranslationLanguage>) : DownloadableUi
}

@HiltViewModel
class TranslationSettingsViewModel @Inject constructor(
    private val settings: TranslationSettings,
    private val translator: TextTranslator,
) : ViewModel() {
    val target: StateFlow<String?> = settings.target
    val automatic: StateFlow<Boolean> = settings.automatic
    val skipped: StateFlow<Set<String>> = settings.skipped

    private val _downloads = MutableStateFlow(DownloadsUi())
    val downloads: StateFlow<DownloadsUi> = _downloads.asStateFlow()

    private val _downloadable = MutableStateFlow<DownloadableUi>(DownloadableUi.Loading)
    val downloadable: StateFlow<DownloadableUi> = _downloadable.asStateFlow()

    private val _failedDownloads = Channel<String>(Channel.BUFFERED)

    /** The names of languages whose download failed. */
    val failedDownloads: Flow<String> = _failedDownloads.receiveAsFlow()

    init {
        refresh()
    }

    /** Whether the device translates into the target language. */
    val canTranslate: Boolean get() = translator.targetLanguage != null

    /** The app's own language, by name, for "Same as the app". */
    val appLanguageName: String get() = languageName(appLanguageTag())

    /** Every language Firefox's models translate into, Chinese once per script, sorted by name. */
    val targetChoices: List<TargetChoice> by lazy {
        SupportedLanguages.flatMap { code ->
            when (code) {
                "zh" -> listOf("zh-Hans", "zh-Hant")
                "no" -> listOf("nb")
                else -> listOf(code)
            }
        }.map { TargetChoice(it, languageName(it)) }.sortedBy { it.name }
    }

    /** The languages that can be marked as read, by [translationLanguage] code, without the target. */
    fun readableChoices(): List<TargetChoice> =
        SupportedLanguages.filter { it != translator.targetLanguage }
            .map { TargetChoice(it, capitalized(translator.languageName(it))) }
            .sortedBy { it.name }

    fun targetName(tag: String?): String = if (tag == null) appLanguageName else languageName(tag)

    fun readableName(code: String): String = capitalized(translator.languageName(code))

    fun setTarget(tag: String?) {
        settings.setTarget(tag)
        refresh()
    }

    fun setAutomatic(enabled: Boolean) = settings.setAutomatic(enabled)

    fun setSkipped(code: String, skip: Boolean) = settings.setSkipped(code, skip)

    fun loadDownloadable() {
        _downloadable.value = DownloadableUi.Loading
        viewModelScope.launch {
            _downloadable.value = try {
                DownloadableUi.Loaded(translator.downloadableLanguages().map { it.copy(name = capitalized(it.name)) })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                DownloadableUi.Failed
            }
        }
    }

    /** Downloads [language], which the user picked knowing its size. */
    fun download(language: TranslationLanguage) {
        if (language.code in _downloads.value.downloading) return
        _downloads.update { it.copy(downloading = it.downloading + (language.code to capitalized(language.name))) }
        viewModelScope.launch {
            try {
                translator.downloadLanguage(language.code)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _failedDownloads.send(language.name)
            } finally {
                _downloads.update { it.copy(downloading = it.downloading - language.code) }
            }
            refresh()
        }
    }

    fun delete(language: TranslationLanguage) {
        viewModelScope.launch {
            translator.deleteLanguage(language.code)
            refresh()
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            translator.deleteAllLanguages()
            refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val downloaded = translator.downloadedLanguages().map { it.copy(name = capitalized(it.name)) }
            val used = translator.downloadedBytes()
            _downloads.update { it.copy(downloaded = downloaded, usedBytes = used) }
        }
    }

    private fun capitalized(name: String): String = name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

/** The app's own language as a BCP 47 tag, with its script ("zh-Hant"). */
internal fun appLanguageTag(): String =
    Locale.getDefault().let { Locale.Builder().setLanguage(it.language).setScript(it.script).build().toLanguageTag() }

/** The language [tag] names, in the app's language, starting with a capital as a list item does. */
internal fun languageName(tag: String): String {
    val locale = Locale.getDefault()
    return Locale.forLanguageTag(tag).getDisplayName(locale).ifBlank { tag }.replaceFirstChar { it.titlecase(locale) }
}

/**
 * Translation settings: the language to translate into, translating on its own when a post is opened
 * (except from the languages the user reads), and the languages downloaded ahead of time.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TranslationSettingsScreen(
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    viewModel: TranslationSettingsViewModel = hiltViewModel(),
) {
    val target by viewModel.target.collectAsStateWithLifecycle()
    val automatic by viewModel.automatic.collectAsStateWithLifecycle()
    val skipped by viewModel.skipped.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf<Picker?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.failedDownloads.collect { name ->
            snackbar.showSnackbar(resources.getString(R.string.translation_settings_download_failed, name))
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_translation)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                SectionTitle(R.string.translation_settings_target)
                val targetName = if (target == null) {
                    stringResource(R.string.translation_settings_target_app, viewModel.appLanguageName)
                } else {
                    viewModel.targetName(target)
                }
                Rows(
                    listOf(
                        RowSpec(AppIcons.Translate, targetName, onClick = { picking = Picker.Target }),
                    ),
                )
                if (!viewModel.canTranslate) {
                    Explainer(stringResource(R.string.translation_settings_unsupported, viewModel.targetName(target)))
                }

                SectionTitle(R.string.translation_settings_automatic_title, Modifier.padding(top = 16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    SegmentedListItem(
                        onClick = { viewModel.setAutomatic(!automatic) },
                        shapes = ListItemDefaults.segmentedShapes(0, 2),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            role = Role.Switch
                            toggleableState = ToggleableState(automatic)
                        },
                        supportingContent = { Text(stringResource(R.string.translation_settings_automatic_summary)) },
                        trailingContent = {
                            Switch(
                                checked = automatic,
                                onCheckedChange = null,
                                thumbContent = if (automatic) {
                                    { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                                } else {
                                    null
                                },
                            )
                        },
                    ) {
                        Text(stringResource(R.string.translation_settings_automatic))
                    }
                    SegmentedListItem(
                        onClick = { picking = Picker.Skipped },
                        shapes = ListItemDefaults.segmentedShapes(1, 2),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        supportingContent = {
                            Text(
                                if (skipped.isEmpty()) {
                                    stringResource(R.string.translation_settings_skipped_none)
                                } else {
                                    skipped.map(viewModel::readableName).sorted().joinToString(", ")
                                },
                            )
                        },
                    ) {
                        Text(stringResource(R.string.translation_settings_skipped))
                    }
                }

                SectionTitle(R.string.translation_settings_downloads, Modifier.padding(top = 16.dp))
                Explainer(stringResource(R.string.translation_settings_downloads_explainer))
                Downloads(downloads, onDelete = viewModel::delete, onAdd = {
                    viewModel.loadDownloadable()
                    picking = Picker.Download
                }, enabled = viewModel.canTranslate)

                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    val rows = buildList {
                        if (downloads.usedBytes > 0) {
                            add(
                                RowSpec(
                                    AppIcons.Delete,
                                    stringResource(R.string.translation_settings_delete_all),
                                    stringResource(R.string.translation_settings_delete_all_summary, megabytes(downloads.usedBytes)),
                                    onClick = { picking = Picker.DeleteAll },
                                ),
                            )
                        }
                        add(RowSpec(AppIcons.PrivacyTip, stringResource(R.string.translation_info_title), onClick = onOpenInfo))
                    }
                    Rows(rows)
                }
            }
        }
    }

    when (picking) {
        Picker.Target -> TargetDialog(
            current = target,
            appChoice = stringResource(R.string.translation_settings_target_app, viewModel.appLanguageName),
            choices = viewModel.targetChoices,
            onPick = { tag ->
                picking = null
                viewModel.setTarget(tag)
            },
            onDismiss = { picking = null },
        )

        Picker.Skipped -> SkippedDialog(
            choices = remember(target) { viewModel.readableChoices() },
            skipped = skipped,
            onToggle = viewModel::setSkipped,
            onDismiss = { picking = null },
        )

        Picker.Download -> {
            val downloadable by viewModel.downloadable.collectAsStateWithLifecycle()
            DownloadDialog(
                state = downloadable,
                onRetry = viewModel::loadDownloadable,
                onPick = { language ->
                    picking = null
                    viewModel.download(language)
                },
                onDismiss = { picking = null },
            )
        }

        Picker.DeleteAll -> AlertDialog(
            onDismissRequest = { picking = null },
            icon = { Icon(AppIcons.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.translation_settings_delete_all_title)) },
            text = { Text(stringResource(R.string.translation_settings_delete_all_text)) },
            confirmButton = {
                TextButton(onClick = {
                    picking = null
                    viewModel.deleteAll()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.dialog_cancel)) } },
        )

        null -> Unit
    }
}

private enum class Picker { Target, Skipped, Download, DeleteAll }

private class RowSpec(val icon: ImageVector, val label: String, val summary: String? = null, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Rows(rows: List<RowSpec>) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { index, row ->
            SegmentedListItem(
                onClick = row.onClick,
                shapes = ListItemDefaults.segmentedShapes(index, rows.size),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                leadingContent = { Icon(row.icon, contentDescription = null) },
                supportingContent = row.summary?.let { summary -> { Text(summary) } },
            ) {
                Text(row.label)
            }
        }
    }
}

/** The downloaded languages, each with its size and a delete button, then "Download a language". */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Downloads(ui: DownloadsUi, onDelete: (TranslationLanguage) -> Unit, onAdd: () -> Unit, enabled: Boolean) {
    val downloaded = ui.downloaded ?: return
    val count = downloaded.size + ui.downloading.size + 1
    var index = 0
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        if (downloaded.isEmpty() && ui.downloading.isEmpty()) {
            Explainer(stringResource(R.string.translation_settings_none_downloaded))
        }
        for (language in downloaded) {
            SegmentedListItem(
                onClick = {},
                enabled = false,
                shapes = ListItemDefaults.segmentedShapes(index++, count),
                colors = ListItemDefaults.segmentedColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface,
                    disabledSupportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledTrailingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                supportingContent = {
                    Text(
                        if (language.needed) {
                            stringResource(R.string.translation_settings_needed, megabytes(language.bytes))
                        } else {
                            stringResource(R.string.translation_settings_size, megabytes(language.bytes))
                        },
                    )
                },
                trailingContent = if (language.needed) {
                    null
                } else {
                    {
                        IconButton(onClick = { onDelete(language) }) {
                            Icon(AppIcons.Delete, contentDescription = stringResource(R.string.translation_settings_delete, language.name))
                        }
                    }
                },
            ) {
                Text(language.name)
            }
        }
        for (name in ui.downloading.values) {
            SegmentedListItem(
                onClick = {},
                enabled = false,
                shapes = ListItemDefaults.segmentedShapes(index++, count),
                colors = ListItemDefaults.segmentedColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface,
                    disabledSupportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledTrailingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                supportingContent = { Text(stringResource(R.string.translation_settings_downloading)) },
                trailingContent = { LoadingIndicator(Modifier.size(32.dp)) },
            ) {
                Text(name)
            }
        }
        SegmentedListItem(
            onClick = onAdd,
            enabled = enabled,
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            leadingContent = { Icon(AppIcons.Download, contentDescription = null) },
        ) {
            Text(stringResource(R.string.translation_settings_add))
        }
    }
}

@Composable
private fun TargetDialog(
    current: String?,
    appChoice: String,
    choices: List<TargetChoice>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translation_settings_target)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                item { ChoiceRow(appChoice, selected = current == null, radio = true) { onPick(null) } }
                items(choices, key = { it.tag }) { choice ->
                    ChoiceRow(choice.name, selected = current == choice.tag, radio = true) { onPick(choice.tag) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun SkippedDialog(choices: List<TargetChoice>, skipped: Set<String>, onToggle: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translation_settings_skipped)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.translation_settings_skipped_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(choices, key = { it.tag }) { choice ->
                        val checked = choice.tag in skipped
                        ChoiceRow(choice.name, selected = checked, radio = false) { onToggle(choice.tag, !checked) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.translation_settings_done)) } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadDialog(
    state: DownloadableUi,
    onRetry: () -> Unit,
    onPick: (TranslationLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translation_settings_add)) },
        text = {
            when (state) {
                DownloadableUi.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(Modifier.size(48.dp))
                }

                DownloadableUi.Failed -> Text(stringResource(R.string.translation_settings_add_failed))

                is DownloadableUi.Loaded -> Column {
                    Text(
                        stringResource(R.string.translation_settings_add_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(state.languages, key = { it.code }) { language ->
                            ListItem(
                                headlineContent = { Text(language.name) },
                                supportingContent = { Text(stringResource(R.string.translation_settings_size, megabytes(language.bytes))) },
                                trailingContent = { Icon(AppIcons.Download, contentDescription = null) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                modifier = Modifier.semantics(mergeDescendants = true) { role = Role.Button }
                                    .clickable { onPick(language) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state == DownloadableUi.Failed) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, radio: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            if (radio) RadioButton(selected = selected, onClick = null) else Checkbox(checked = selected, onCheckedChange = null)
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .semantics(mergeDescendants = true) {
                role = if (radio) Role.RadioButton else Role.Checkbox
                if (radio) this.selected = selected else toggleableState = ToggleableState(selected)
            }
            .clickable(onClick = onClick),
    )
}

/** Whole megabytes, rounded up: a download is never less than it says. */
private fun megabytes(bytes: Long): Int = ((bytes + 999_999) / 1_000_000).toInt().coerceAtLeast(1)
