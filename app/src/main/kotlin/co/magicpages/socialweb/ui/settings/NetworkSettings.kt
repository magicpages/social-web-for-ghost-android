package co.magicpages.socialweb.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import co.magicpages.socialweb.data.activitypub.SensitiveMediaPreference
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.errorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NetworkUi(
    val sensitiveLoading: Boolean = true,
    /** Why the last change of "Show sensitive media" was undone. */
    val sensitiveError: Throwable? = null,
)

/**
 * The sensitive-media choice of the active account. The screen belongs to one
 * account session (the navigation entries are keyed per session), so another account gets a fresh instance.
 */
@HiltViewModel
class NetworkSettingsViewModel @Inject constructor(
    private val sensitiveMedia: SensitiveMediaPreference,
) : ViewModel() {
    private val _ui = MutableStateFlow(NetworkUi())
    val ui: StateFlow<NetworkUi> = _ui.asStateFlow()

    /** The service's answer for "Show sensitive media"; null until it has given one. */
    val showSensitiveMedia: StateFlow<Boolean?> = sensitiveMedia.preference

    init {
        loadSensitiveMedia()
    }

    fun loadSensitiveMedia() {
        _ui.update { it.copy(sensitiveLoading = true, sensitiveError = null) }
        viewModelScope.launch {
            sensitiveMedia.refresh()
            _ui.update { it.copy(sensitiveLoading = false) }
        }
    }

    fun setShowSensitiveMedia(show: Boolean) {
        _ui.update { it.copy(sensitiveError = null) }
        viewModelScope.launch {
            try {
                sensitiveMedia.set(show)
            } catch (e: GhostException) {
                _ui.update { it.copy(sensitiveError = e) }
            } catch (e: SocialWebException) {
                _ui.update { it.copy(sensitiveError = e) }
            }
        }
    }
}

/**
 * The settings section that lives on the site's service: showing sensitive media. [siteTitle] names
 * the site it applies to. Emits into the screen's column.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NetworkSettings(siteTitle: String, viewModel: NetworkSettingsViewModel = hiltViewModel()) {
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