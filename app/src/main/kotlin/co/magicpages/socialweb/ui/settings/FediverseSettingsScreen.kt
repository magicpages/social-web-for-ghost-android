package co.magicpages.socialweb.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.fediverse.FediverseClient
import co.magicpages.socialweb.data.fediverse.FediverseExplore
import co.magicpages.socialweb.data.fediverse.LanguageServers
import co.magicpages.socialweb.data.fediverse.PublicServerException
import co.magicpages.socialweb.data.fediverse.ServerTopic
import co.magicpages.socialweb.data.fediverse.TopicServers
import co.magicpages.socialweb.data.fediverse.normalizeServer
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.settings.AppSettings
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.theme.Gutter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where checking a typed-in server stands. */
sealed interface ServerCheck {
    data object Idle : ServerCheck

    data class Checking(val server: String) : ServerCheck

    /** [server] is null when what was typed isn't a server address at all. */
    data class Failed(val server: String?, val notPublic: Boolean = false) : ServerCheck
}

@HiltViewModel
class FediverseSettingsViewModel @Inject constructor(
    private val settings: AppSettings,
    private val client: FediverseClient,
) : ViewModel() {
    val fediverse: StateFlow<FediverseExplore> = settings.fediverseExplore

    private val _check = MutableStateFlow<ServerCheck>(ServerCheck.Idle)
    val check: StateFlow<ServerCheck> = _check.asStateFlow()

    private var checking: Job? = null

    fun setEnabled(enabled: Boolean) = settings.setFediverseExploreEnabled(enabled)

    fun choose(server: String) {
        checking?.cancel()
        _check.value = ServerCheck.Idle
        settings.setFediverseServer(server)
    }

    /**
     * Switches to the server [input] names once it has answered for its trending posts, so Explore isn't
     * left on one it can't read. Returns at once; [check] says how it went, back at [ServerCheck.Idle] when it worked.
     */
    fun useTyped(input: String) {
        val server = normalizeServer(input)
        if (server == null) {
            _check.value = ServerCheck.Failed(null)
            return
        }
        checking?.cancel()
        _check.value = ServerCheck.Checking(server)
        checking = viewModelScope.launch {
            _check.value = try {
                client.check(server)
                settings.setFediverseServer(server)
                ServerCheck.Idle
            } catch (_: GhostException) {
                ServerCheck.Failed(server)
            } catch (e: PublicServerException) {
                ServerCheck.Failed(server, notPublic = e.notPublic)
            }
        }
    }
}

/**
 * The experimental Fediverse source of Explore: switching it on, and the server whose trends it shows,
 * picked by topic or language or typed in.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FediverseSettingsScreen(onBack: () -> Unit, viewModel: FediverseSettingsViewModel = hiltViewModel()) {
    val fediverse by viewModel.fediverse.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scroll = rememberScrollState()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_fediverse_explore)) },
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
                    // The server field is at the bottom: the keyboard shrinks the list so it scrolls into view above it.
                    .imePadding()
                    .verticalScroll(scroll)
                    .padding(horizontal = Gutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Explainer(stringResource(R.string.fediverse_settings_intro))
                SwitchRow(
                    title = stringResource(R.string.fediverse_settings_switch),
                    checked = fediverse.enabled,
                    onCheckedChange = viewModel::setEnabled,
                    index = 0,
                    count = 1,
                    icon = AppIcons.Public,
                )
                if (!fediverse.enabled) return@Column

                FediverseServerPicker(scroll, viewModel = viewModel)
                Explainer(stringResource(R.string.fediverse_settings_privacy, fediverse.server))
            }
        }
    }
}

/**
 * The servers Explore can show, by topic and by language, and a field for any other; picking one
 * switches to it. Settings and Explore's server sheet both show it, inside a column that scrolls
 * with [scroll], so a refused address scrolls into view with its reason. [onChosen] runs once the
 * pick is the server in use.
 */
@Composable
fun FediverseServerPicker(scroll: ScrollState, onChosen: () -> Unit = {}, viewModel: FediverseSettingsViewModel = hiltViewModel()) {
    val fediverse by viewModel.fediverse.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    // Set while a typed server is checked, so the end of that check, and only that, finishes the pick.
    var awaitingTyped by rememberSaveable { mutableStateOf(false) }
    val useDraft = {
        if (draft.isNotBlank() && check !is ServerCheck.Checking) {
            awaitingTyped = true
            viewModel.useTyped(draft)
        }
    }
    val choose = { host: String ->
        awaitingTyped = false
        viewModel.choose(host)
        onChosen()
    }
    LaunchedEffect(check) {
        when (check) {
            is ServerCheck.Failed -> {
                awaitingTyped = false
                // Only the field itself scrolls into view above the keyboard; the reason it was refused is under it.
                scroll.animateScrollTo(scroll.maxValue)
            }
            // The typed server answered and is the one in use now, even when it already was: the field is done with it.
            ServerCheck.Idle -> if (awaitingTyped) {
                awaitingTyped = false
                draft = ""
                onChosen()
            }
            is ServerCheck.Checking -> Unit
        }
    }

    SectionTitle(R.string.fediverse_settings_topics, Modifier.padding(top = 16.dp))
    SegmentedGroup {
        TopicServers.forEachIndexed { index, server ->
            ServerRow(server.host, stringResource(server.topic.label), fediverse.server == server.host, { choose(server.host) }, index, TopicServers.size)
        }
    }

    SectionTitle(R.string.fediverse_settings_languages, Modifier.padding(top = 16.dp))
    SegmentedGroup {
        LanguageServers.forEachIndexed { index, server ->
            ServerRow(server.host, languageName(server.language), fediverse.server == server.host, { choose(server.host) }, index, LanguageServers.size)
        }
    }

    SectionTitle(R.string.fediverse_settings_other, Modifier.padding(top = 16.dp))
    val typedIn = TopicServers.none { it.host == fediverse.server } && LanguageServers.none { it.host == fediverse.server }
    if (typedIn) {
        ServerRow(fediverse.server, stringResource(R.string.fediverse_settings_custom), selected = true, onClick = {}, index = 0, count = 1)
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(stringResource(R.string.fediverse_settings_other_label)) },
            placeholder = { Text("example.social") },
            singleLine = true,
            isError = check is ServerCheck.Failed,
            supportingText = (check as? ServerCheck.Failed)?.let { failed -> { Text(failureMessage(failed)) } },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { useDraft() }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { useDraft() }, enabled = draft.isNotBlank() && check !is ServerCheck.Checking) {
            if (check is ServerCheck.Checking) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.fediverse_settings_use))
            }
        }
    }
}

/** What Explore calls [server]: its topic or language when it's one the app suggests, else the host itself. */
@Composable
fun fediverseServerLabel(server: String): String {
    TopicServers.firstOrNull { it.host == server }?.let { return stringResource(it.topic.label) }
    LanguageServers.firstOrNull { it.host == server }?.let { return languageName(it.language) }
    return server
}

@Composable
private fun failureMessage(failed: ServerCheck.Failed): String = when {
    failed.server == null -> stringResource(R.string.fediverse_settings_invalid)
    failed.notPublic -> stringResource(R.string.fediverse_not_public, failed.server)
    else -> stringResource(R.string.fediverse_settings_unreachable, failed.server)
}

/** A server to pick, with what it's about; [index] of [count] gives the group its shape. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServerRow(host: String, summary: String, selected: Boolean, onClick: () -> Unit, index: Int, count: Int) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.semantics(mergeDescendants = true) {
            role = Role.RadioButton
            this.selected = selected
        },
        supportingContent = { Text(summary) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
    ) {
        Text(host)
    }
}

private val ServerTopic.label: Int
    @StringRes get() = when (this) {
        ServerTopic.General -> R.string.fediverse_topic_general
        ServerTopic.Technology -> R.string.fediverse_topic_technology
        ServerTopic.OpenSource -> R.string.fediverse_topic_open_source
        ServerTopic.Science -> R.string.fediverse_topic_science
        ServerTopic.Climate -> R.string.fediverse_topic_climate
        ServerTopic.Journalism -> R.string.fediverse_topic_journalism
        ServerTopic.GameDevelopment -> R.string.fediverse_topic_game_development
        ServerTopic.Music -> R.string.fediverse_topic_music
        ServerTopic.IndieWeb -> R.string.fediverse_topic_indie_web
    }
