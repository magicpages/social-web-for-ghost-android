package co.magicpages.socialweb.ui.settings

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.BlueskySharing
import co.magicpages.socialweb.data.activitypub.BlueskyStatus
import co.magicpages.socialweb.data.activitypub.DomainHandleStatus
import co.magicpages.socialweb.data.activitypub.SensitiveMediaPreference
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.errorMessage
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** How the site's Bluesky sharing stands. */
sealed interface BlueskyPhase {
    /** The site's account hasn't been read yet. */
    data object Loading : BlueskyPhase

    data class Failed(val error: Throwable) : BlueskyPhase

    data object Off : BlueskyPhase

    /** Enabled; Bridgy Fed is still making the Bluesky account, or giving it the site's domain as its handle. */
    data object Connecting : BlueskyPhase

    data class On(val handle: String) : BlueskyPhase
}

/** Why the last change of Bluesky sharing didn't happen. */
sealed interface BlueskyProblem {
    data class Failed(val error: Throwable) : BlueskyProblem

    /** Bridgy Fed didn't make the account in time; sharing was switched off again. */
    data object TimedOut : BlueskyProblem
}

data class NetworkUi(
    val bluesky: BlueskyPhase = BlueskyPhase.Loading,
    val problem: BlueskyProblem? = null,
    /** The site's account, for the connected card and for what enabling needs. */
    val name: String = "",
    val handle: String = "",
    val avatarUrl: String? = null,
    /** The disable question is open; [disabling] while the service does it. */
    val confirmDisable: Boolean = false,
    val disabling: Boolean = false,
    val sensitiveLoading: Boolean = true,
    /** Why the last change of "Show sensitive media" was undone. */
    val sensitiveError: Throwable? = null,
)

/** Bluesky sharing and the sensitive-media choice of the signed-in account [accountId], active or not. */
@HiltViewModel(assistedFactory = NetworkSettingsViewModel.Factory::class)
class NetworkSettingsViewModel @AssistedInject constructor(
    @Assisted private val accountId: String,
    private val bluesky: BlueskySharing,
    private val sensitiveMedia: SensitiveMediaPreference,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(accountId: String): NetworkSettingsViewModel
    }

    private val _ui = MutableStateFlow(NetworkUi())
    val ui: StateFlow<NetworkUi> = _ui.asStateFlow()

    /** The service's answer for "Show sensitive media"; null until it has given one. */
    val showSensitiveMedia: StateFlow<Boolean?> =
        sensitiveMedia.preferenceOf(accountId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var blueskyJob: Job? = null

    init {
        loadBluesky()
        loadSensitiveMedia()
    }

    fun loadSensitiveMedia() {
        _ui.update { it.copy(sensitiveLoading = true, sensitiveError = null) }
        viewModelScope.launch {
            sensitiveMedia.refresh(accountId)
            _ui.update { it.copy(sensitiveLoading = false) }
        }
    }

    fun setShowSensitiveMedia(show: Boolean) {
        _ui.update { it.copy(sensitiveError = null) }
        viewModelScope.launch {
            try {
                sensitiveMedia.set(accountId, show)
            } catch (e: GhostException) {
                _ui.update { it.copy(sensitiveError = e) }
            } catch (e: SocialWebException) {
                _ui.update { it.copy(sensitiveError = e) }
            }
        }
    }

    fun loadBluesky() {
        blueskyJob?.cancel()
        _ui.update { it.copy(bluesky = BlueskyPhase.Loading, problem = null) }
        blueskyJob = viewModelScope.launch {
            val state = try {
                bluesky.state(accountId)
            } catch (e: GhostException) {
                _ui.update { it.copy(bluesky = BlueskyPhase.Failed(e)) }
                return@launch
            } catch (e: SocialWebException) {
                _ui.update { it.copy(bluesky = BlueskyPhase.Failed(e)) }
                return@launch
            }
            _ui.update { it.copy(name = state.name, handle = state.handle, avatarUrl = state.avatarUrl) }
            show(state.status)
        }
    }

    /** Starts sharing. The Bluesky account takes a while to appear, so this goes on to wait for its handle. */
    fun enable() {
        val current = _ui.value
        if (current.bluesky != BlueskyPhase.Off || current.avatarUrl.isNullOrBlank()) return
        blueskyJob?.cancel()
        _ui.update { it.copy(bluesky = BlueskyPhase.Connecting, problem = null) }
        blueskyJob = viewModelScope.launch {
            val status = try {
                bluesky.enable(accountId)
            } catch (e: GhostException) {
                failedToEnable(e)
                return@launch
            } catch (e: SocialWebException) {
                failedToEnable(e)
                return@launch
            }
            show(status)
        }
    }

    private fun failedToEnable(error: Throwable) {
        _ui.update { it.copy(bluesky = BlueskyPhase.Off, problem = BlueskyProblem.Failed(error)) }
    }

    /** Shows [status]; one still waiting for its handle is polled until Bridgy Fed has made the account. */
    private suspend fun show(status: BlueskyStatus) {
        when {
            !status.enabled -> _ui.update { it.copy(bluesky = BlueskyPhase.Off) }
            status.confirmedHandle != null -> connected(status)
            else -> {
                _ui.update { it.copy(bluesky = BlueskyPhase.Connecting) }
                awaitHandle()
            }
        }
    }

    /**
     * Shows a [status], then follows a switch to the site's domain while one is going on. Returns the
     * last answer, which has no confirmed handle when the switch was all that would bring one.
     */
    private suspend fun connected(status: BlueskyStatus): BlueskyStatus {
        showConfirmed(status, takingLong = false)
        return followDomainHandle(status, ::confirm, ::showConfirmed)
    }

    private fun showConfirmed(status: BlueskyStatus, takingLong: Boolean) {
        val phase = confirmedPhase(status, takingLong) ?: return
        _ui.update { it.copy(bluesky = phase) }
    }

    /** A failed ask counts as no answer. */
    private suspend fun confirm(): BlueskyStatus? = try {
        bluesky.confirmHandle(accountId)
    } catch (_: GhostException) {
        null
    } catch (_: SocialWebException) {
        null
    }

    /**
     * Asks every [ConfirmInterval] whether the handle is there yet, as Ghost's own client does. When
     * it never comes, sharing is switched off again.
     */
    private suspend fun awaitHandle() {
        for (attempt in 1..MaxConfirmations) {
            delay(ConfirmInterval)
            val status = confirm() ?: continue
            // Bluesky may refuse the handle kept from an earlier connection. The service then claims
            // the domain first and confirms the handle once Bluesky accepts it, which can take ten
            // minutes. Switching sharing off before that would stop the claim.
            if (status.confirmedHandle != null || status.domainHandle?.status == DomainHandleStatus.Claiming) {
                if (connected(status).confirmedHandle != null) return
                break
            }
        }
        try {
            bluesky.disable(accountId)
            _ui.update { it.copy(bluesky = BlueskyPhase.Off, problem = BlueskyProblem.TimedOut) }
        } catch (e: GhostException) {
            _ui.update { it.copy(bluesky = BlueskyPhase.Failed(e)) }
        } catch (e: SocialWebException) {
            _ui.update { it.copy(bluesky = BlueskyPhase.Failed(e)) }
        }
    }

    fun askToDisable() {
        if (_ui.value.bluesky is BlueskyPhase.On) _ui.update { it.copy(confirmDisable = true, problem = null) }
    }

    fun cancelDisable() {
        _ui.update { if (it.disabling) it else it.copy(confirmDisable = false) }
    }

    fun disable() {
        if (_ui.value.disabling) return
        _ui.update { it.copy(disabling = true) }
        viewModelScope.launch {
            val failure = try {
                bluesky.disable(accountId)
                null
            } catch (e: GhostException) {
                e
            } catch (e: SocialWebException) {
                e
            }
            // A switch to the domain handle may still be followed; its answers would turn sharing back on.
            if (failure == null) blueskyJob?.cancel()
            _ui.update {
                if (failure == null) {
                    it.copy(bluesky = BlueskyPhase.Off, confirmDisable = false, disabling = false)
                } else {
                    it.copy(confirmDisable = false, disabling = false, problem = BlueskyProblem.Failed(failure))
                }
            }
        }
    }
}

private const val ConfirmInterval = 5_000L
private const val MaxConfirmations = 12

// Ghost Admin's pace for a switch to the domain handle: Bridgy Fed usually switches within a
// minute, so every ConfirmInterval for two minutes, then slower until the service has given up
// on the switch (after ten).
private const val FastDomainChecks = 24
private const val SlowConfirmInterval = 30_000L
private const val SlowDomainChecks = 20

// Ghost Admin's two minutes until a switch set up by enabling counts as taking long.
private val TakingLongAfter = (FastDomainChecks * ConfirmInterval).milliseconds

/** The handle as Bluesky shows it, `@example.com`, once [BlueskyStatus.handleConfirmed]. */
internal val BlueskyStatus.confirmedHandle: String?
    get() = handle?.removePrefix("@")?.takeIf { handleConfirmed && it.isNotBlank() }?.let { "@$it" }

/**
 * What a [status] with a confirmed handle shows; null without one. Enabling sharing sets up the
 * domain handle too, which counts as connecting until it's [takingLong], so the handle shown
 * doesn't change a minute after connecting.
 */
internal fun confirmedPhase(status: BlueskyStatus, takingLong: Boolean): BlueskyPhase? {
    val handle = status.confirmedHandle ?: return null
    val domain = status.domainHandle
    val settingUp = domain != null && domain.automatic && domain.status == DomainHandleStatus.Claiming
    return if (settingUp && !takingLong) BlueskyPhase.Connecting else BlueskyPhase.On(handle)
}

/**
 * While Bridgy Fed switches the handle to the site's domain, asks [confirm] again until the switch
 * is over or the service has given up on it, and returns the last answer. Every answer goes to
 * [show], with whether the switch is taking long; a failed ask (null) counts as no change.
 */
internal suspend fun followDomainHandle(
    first: BlueskyStatus,
    confirm: suspend () -> BlueskyStatus?,
    show: (status: BlueskyStatus, takingLong: Boolean) -> Unit,
    clock: TimeSource = TimeSource.Monotonic,
): BlueskyStatus {
    // Measured, as each ask's own time adds to the pauses between them.
    val started = clock.markNow()
    var status = first
    var checks = 0
    while (status.domainHandle?.status == DomainHandleStatus.Claiming && checks < FastDomainChecks + SlowDomainChecks) {
        delay(if (checks < FastDomainChecks) ConfirmInterval else SlowConfirmInterval)
        checks++
        status = confirm() ?: status
        show(status, started.elapsedNow() >= TakingLongAfter)
    }
    return status
}

/**
 * The settings sections that live on the site's service: showing sensitive media, and sharing the
 * site's posts on Bluesky, for the signed-in account [accountId]. [siteTitle] names the site they
 * apply to. Emits into the screen's column.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NetworkSettings(accountId: String, siteTitle: String) {
    val viewModel = hiltViewModel<NetworkSettingsViewModel, NetworkSettingsViewModel.Factory>(key = "network-$accountId") { it.create(accountId) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val showSensitiveMedia by viewModel.showSensitiveMedia.collectAsStateWithLifecycle()

    SectionTitle(R.string.settings_sensitive_media, Modifier.padding(top = 16.dp))
    val known = showSensitiveMedia
    ToggleRow(
        label = R.string.settings_sensitive_media_show,
        summary = when {
            known != null -> stringResource(R.string.settings_sensitive_media_summary, siteTitle)
            ui.sensitiveLoading -> null
            else -> stringResource(R.string.settings_network_retry_hint)
        },
        checked = known == true,
        busy = known == null && ui.sensitiveLoading,
        enabled = known != null || !ui.sensitiveLoading,
        onClick = { if (known == null) viewModel.loadSensitiveMedia() else viewModel.setShowSensitiveMedia(!known) },
    )
    ui.sensitiveError?.let { ErrorText(it) }

    SectionTitle(R.string.settings_bluesky, Modifier.padding(top = 16.dp))
    BlueskySection(ui, siteTitle, viewModel)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BlueskySection(ui: NetworkUi, siteTitle: String, viewModel: NetworkSettingsViewModel) {
    val phase = ui.bluesky
    val hasAvatar = !ui.avatarUrl.isNullOrBlank()
    when (phase) {
        BlueskyPhase.Loading -> ToggleRow(R.string.settings_bluesky_share, null, checked = false, busy = true, enabled = false, onClick = {})
        is BlueskyPhase.Failed -> {
            Text(
                errorMessage(phase.error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = viewModel::loadBluesky) { Text(stringResource(R.string.retry)) }
        }
        BlueskyPhase.Off -> {
            Explainer(
                if (hasAvatar) {
                    stringResource(R.string.settings_bluesky_explainer, siteTitle)
                } else {
                    stringResource(R.string.settings_bluesky_needs_avatar)
                },
            )
            if (hasAvatar) Explainer(stringResource(R.string.settings_bluesky_handle_warning, ui.handle))
            ToggleRow(
                label = R.string.settings_bluesky_share,
                summary = null,
                checked = false,
                busy = false,
                enabled = hasAvatar,
                onClick = viewModel::enable,
            )
        }
        BlueskyPhase.Connecting -> {
            Explainer(stringResource(R.string.settings_bluesky_explainer, siteTitle))
            ToggleRow(
                label = R.string.settings_bluesky_share,
                summary = stringResource(R.string.settings_bluesky_connecting),
                checked = true,
                busy = true,
                enabled = false,
                onClick = {},
            )
        }
        is BlueskyPhase.On -> {
            Explainer(stringResource(R.string.settings_bluesky_connected, siteTitle))
            ToggleRow(
                label = R.string.settings_bluesky_share,
                summary = null,
                checked = true,
                busy = false,
                enabled = !ui.disabling,
                onClick = viewModel::askToDisable,
            )
            if (phase.handle.isNotBlank()) ConnectedCard(ui.name, ui.avatarUrl, phase.handle)
        }
    }
    ui.problem?.let { problem ->
        when (problem) {
            is BlueskyProblem.Failed -> ErrorText(problem.error)
            BlueskyProblem.TimedOut -> Text(
                stringResource(R.string.settings_bluesky_timed_out),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (ui.confirmDisable) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDisable,
            title = { Text(stringResource(R.string.settings_bluesky_disable_title)) },
            text = { Text(stringResource(R.string.settings_bluesky_disable_text)) },
            confirmButton = {
                TextButton(
                    onClick = viewModel::disable,
                    enabled = !ui.disabling,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    if (ui.disabling) LoadingIndicator(Modifier.size(24.dp)) else Text(stringResource(R.string.settings_bluesky_disable))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDisable, enabled = !ui.disabling) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/** The Bluesky account Bridgy Fed made: whose it is, its handle, and ways to copy and open it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectedCard(name: String, avatarUrl: String?, handle: String) {
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.settings_bluesky_handle_label)
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(avatarUrl, name, 56.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.titleMedium)
                Text(handle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipData.newPlainText(label, handle).toClipEntry())
                            copied = true
                        }
                    },
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(if (copied) AppIcons.Check else AppIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(
                        stringResource(if (copied) R.string.settings_bluesky_handle_copied else R.string.settings_bluesky_copy_handle),
                        modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                    )
                }
                Button(
                    onClick = { uriHandler.openUri("https://bsky.app/profile/${handle.removePrefix("@")}") },
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(AppIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(stringResource(R.string.settings_bluesky_open_profile), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        }
    }
}

@Composable
private fun ErrorText(error: Throwable) {
    Text(
        // Too many requests: Ghost's own client says the same.
        if (error is SocialWebException && error.status == 429) stringResource(R.string.settings_network_rate_limited) else errorMessage(error),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * One switch as a segmented list item; the whole row toggles. A [busy] row shows a progress
 * indicator in place of the switch, and a row that isn't [enabled] ignores taps.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToggleRow(
    @StringRes label: Int,
    summary: String?,
    checked: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = { if (enabled) onClick() },
        shapes = ListItemDefaults.segmentedShapes(0, 1),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        // Reads as one switch to screen readers, not a button plus a separate switch.
        modifier = Modifier
            .alpha(if (enabled || busy) 1f else 0.6f)
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
                if (!enabled) disabled()
            },
        supportingContent = summary?.let { text -> { Text(text) } },
        trailingContent = {
            if (busy) {
                LoadingIndicator(Modifier.size(32.dp))
            } else {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    thumbContent = if (checked) {
                        { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            }
        },
    ) {
        Text(stringResource(label))
    }
}
