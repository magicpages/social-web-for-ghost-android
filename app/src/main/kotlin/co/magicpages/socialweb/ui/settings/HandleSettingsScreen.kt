package co.magicpages.socialweb.ui.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.AccountAlias
import co.magicpages.socialweb.data.activitypub.AccountIdentity
import co.magicpages.socialweb.data.activitypub.AccountMigration
import co.magicpages.socialweb.data.activitypub.SocialWebDomain
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.activitypub.normalizeDomainInput
import co.magicpages.socialweb.data.activitypub.normalizeHandleInput
import co.magicpages.socialweb.data.activitypub.sameHandle
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.errorMessage
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.theme.Gutter
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A part of the screen that is read from the service: not asked yet, answered, or refused. */
sealed interface IdentityPart<out T> {
    /** What the service answered; null until it has. */
    val value: T?

    data object Loading : IdentityPart<Nothing> {
        override val value: Nothing? = null
    }

    data class Failed(val error: Throwable) : IdentityPart<Nothing> {
        override val value: Nothing? = null
    }

    data class Ready<T>(override val value: T) : IdentityPart<T>
}

/**
 * A save the service refused: [status] and [code] when it answered, or the [error] when it didn't
 * reach it. The screen words each reason, so it shows what went wrong rather than a number.
 */
data class IdentityFailure(val status: Int? = null, val code: String? = null, val error: Throwable? = null) {
    /** Whether the staff user isn't allowed to do this: the app doesn't store the role, the service decides. */
    val forbidden: Boolean get() = status == 401 || status == 403
}

/** Which domain change the confirmation dialog is about: the validated one, or going back to the site's host. */
private const val DomainSave = "save"
private const val DomainRemove = "remove"

/** What the handle screen shows: each part of the service's answer, and the change under way. */
data class HandleUi(
    val domain: IdentityPart<SocialWebDomain> = IdentityPart.Loading,
    /** What the user typed for the custom domain, as typed. */
    val domainInput: String = "",
    /** Typing a new domain, rather than looking at the one in use. */
    val editingDomain: Boolean = false,
    /** The input showed its redirect instructions, so the domain can be validated. */
    val instructionsShown: Boolean = false,
    /** The domain the service has validated, ready to be saved. */
    val validatedDomain: String? = null,
    val domainBusy: Boolean = false,
    val domainError: IdentityFailure? = null,
    /** The change waits for its confirmation: [DomainSave] for [validatedDomain], [DomainRemove] for the site's host. */
    val confirmDomain: String? = null,
    val aliases: IdentityPart<List<AccountAlias>> = IdentityPart.Loading,
    val aliasInput: String = "",
    val aliasBusy: Boolean = false,
    val aliasError: IdentityFailure? = null,
    /** The actor URL of the alias being removed. */
    val removingAlias: String? = null,
    /** Null while the service isn't asked, or doesn't do moving away: not every Ghost has it. */
    val migration: AccountMigration? = null,
    /** The service has no move at all (an older Ghost), so the section is left out entirely. */
    val migrationHidden: Boolean = false,
    /** The staff user isn't the Owner, which the service says with 401 or 403. */
    val migrationForbidden: Boolean = false,
    val migrationError: Throwable? = null,
    val moveInput: String = "",
    /** A valid destination, waiting for its confirmation. */
    val confirmMove: String? = null,
    val moving: Boolean = false,
    val moveError: IdentityFailure? = null,
)

/**
 * The handle, its custom domain, the account's aliases and moving its followers away, for the signed-in
 * account [accountId]'s site. The service refuses all of it to staff who aren't Owners or Administrators
 * (and moving away to everyone but the Owner), which shows as [IdentityFailure], since the app doesn't
 * know the staff role.
 */
@HiltViewModel(assistedFactory = HandleSettingsViewModel.Factory::class)
class HandleSettingsViewModel @AssistedInject constructor(
    @Assisted private val accountId: String,
    private val identity: AccountIdentity,
    private val accounts: AccountStore,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(accountId: String): HandleSettingsViewModel
    }

    private val _ui = MutableStateFlow(HandleUi())
    val ui: StateFlow<HandleUi> = _ui.asStateFlow()

    /** The handle the account is stored with, shown while the service is asked and after a domain change. */
    private val _storedHandle = MutableStateFlow("")
    val storedHandle: StateFlow<String> = _storedHandle.asStateFlow()

    // One job per section, so a retry replaces the read that's still running.
    private var domainJob: Job? = null
    private var aliasesJob: Job? = null
    private var migrationJob: Job? = null

    init {
        viewModelScope.launch {
            accounts.accounts.collect { all ->
                _storedHandle.value = all.firstOrNull { it.id == accountId }?.profile?.handle.orEmpty()
            }
        }
        loadDomain()
        loadAliases()
        loadMigration()
    }

    /** Reads the domain again on return, unless the user is in the middle of changing it. The value in use stays on screen meanwhile. */
    fun refreshDomain() {
        if (_ui.value.editingDomain || _ui.value.domainBusy || _ui.value.confirmDomain != null) return
        fetchDomain(showLoading = false)
    }

    fun loadDomain() = fetchDomain(showLoading = true)

    private fun fetchDomain(showLoading: Boolean) {
        domainJob?.cancel()
        if (showLoading) _ui.update { it.copy(domain = IdentityPart.Loading, domainError = null) }
        domainJob = viewModelScope.launch {
            val part = asked { identity.domain(accountId) }
            // A failed refresh keeps what the screen showed rather than replacing it with an error.
            _ui.update { it.copy(domain = if (part is IdentityPart.Failed && !showLoading) it.domain else part) }
        }
    }

    fun loadAliases() {
        aliasesJob?.cancel()
        _ui.update { it.copy(aliases = IdentityPart.Loading, aliasError = null) }
        aliasesJob = viewModelScope.launch {
            val part = asked { identity.aliases(accountId) }
            _ui.update { it.copy(aliases = part) }
        }
    }

    /**
     * Asks where a move to another account stands. An older service without the endpoint leaves the
     * section out; a staff user who isn't the Owner gets a line saying so. Anything else can be retried.
     */
    fun loadMigration() {
        migrationJob?.cancel()
        _ui.update { it.copy(migration = null, migrationHidden = false, migrationForbidden = false, migrationError = null) }
        migrationJob = viewModelScope.launch {
            try {
                val status = identity.migration(accountId)
                _ui.update { it.copy(migration = status) }
            } catch (e: SocialWebException) {
                when {
                    // Not the Owner: say so rather than leave the section out, as Ghost Admin does.
                    e.status == 401 || e.status == 403 -> _ui.update { it.copy(migrationForbidden = true) }
                    // An older service without the endpoint: nothing to offer here.
                    e.status in MoveUnavailableStatuses -> _ui.update { it.copy(migrationHidden = true) }
                    else -> _ui.update { it.copy(migrationError = e) }
                }
            } catch (e: GhostException) {
                _ui.update { it.copy(migrationError = e) }
            }
        }
    }

    fun setDomainInput(value: String) {
        _ui.update { it.copy(domainInput = value, instructionsShown = false, validatedDomain = null, domainError = null) }
    }

    /** Shows the redirect instructions for what was typed; the domain is checked only once they're followed. */
    fun showDomainInstructions() {
        val domain = normalizeDomainInput(_ui.value.domainInput) ?: return
        _ui.update {
            it.copy(domainInput = domain, editingDomain = true, instructionsShown = true, validatedDomain = null, domainError = null)
        }
    }

    /** Opens the domain for editing; the field starts empty, with the one in use as its hint, as Ghost Admin's does. */
    fun editDomain() {
        _ui.update { it.copy(editingDomain = true, domainInput = "", instructionsShown = false, validatedDomain = null, domainError = null) }
    }

    fun cancelDomainEdit() {
        _ui.update { it.copy(editingDomain = false, domainInput = "", instructionsShown = false, validatedDomain = null, domainError = null) }
    }

    fun validateDomain() {
        val domain = normalizeDomainInput(_ui.value.domainInput) ?: return
        _ui.update { it.copy(domainBusy = true, domainError = null) }
        viewModelScope.launch {
            try {
                identity.validateDomain(accountId, domain)
                // The loaded domain stays the one in use: only saving puts the new domain in place.
                _ui.update { it.copy(domainBusy = false, validatedDomain = domain) }
            } catch (e: SocialWebException) {
                _ui.update { it.copy(domainBusy = false, domainError = IdentityFailure(e.status, e.code)) }
            } catch (e: GhostException) {
                _ui.update { it.copy(domainBusy = false, domainError = IdentityFailure(error = e)) }
            }
        }
    }

    fun askToSaveDomain() {
        _ui.update { it.copy(confirmDomain = if (it.validatedDomain != null) DomainSave else null) }
    }

    fun askToRemoveDomain() {
        _ui.update { it.copy(confirmDomain = DomainRemove) }
    }

    fun cancelDomainConfirm() {
        _ui.update { if (it.domainBusy) it else it.copy(confirmDomain = null) }
    }

    /** Saves the confirmed change: [validatedDomain], or the site's own host for [DomainRemove]. */
    fun saveDomain() {
        val ui = _ui.value
        if (ui.domainBusy) return
        val domain = when (ui.confirmDomain) {
            DomainSave -> ui.validatedDomain
            DomainRemove -> null
            else -> return
        }
        _ui.update { it.copy(domainBusy = true, domainError = null) }
        viewModelScope.launch {
            try {
                val saved = identity.setDomain(accountId, domain)
                // The stored handle follows, so the rest of the app shows the new one.
                val account = accounts.signedIn(accountId)
                if (account != null && saved.handle.isNotBlank()) {
                    accounts.updateProfile(account.id, account.profile.copy(handle = saved.handle))
                }
                _ui.update {
                    it.copy(
                        domain = IdentityPart.Ready(saved),
                        domainBusy = false,
                        confirmDomain = null,
                        editingDomain = false,
                        domainInput = "",
                        instructionsShown = false,
                        validatedDomain = null,
                    )
                }
            } catch (e: SocialWebException) {
                _ui.update { it.copy(domainBusy = false, confirmDomain = null, domainError = IdentityFailure(e.status, e.code)) }
            } catch (e: GhostException) {
                _ui.update { it.copy(domainBusy = false, confirmDomain = null, domainError = IdentityFailure(error = e)) }
            }
        }
    }

    fun setAliasInput(value: String) {
        _ui.update { it.copy(aliasInput = value, aliasError = null) }
    }

    fun addAlias() {
        val handle = normalizeHandleInput(_ui.value.aliasInput)
        if (handle == null) {
            _ui.update { it.copy(aliasError = IdentityFailure(status = 400)) }
            return
        }
        _ui.update { it.copy(aliasBusy = true, aliasError = null) }
        viewModelScope.launch {
            try {
                val aliases = identity.addAlias(accountId, handle)
                _ui.update { it.copy(aliases = IdentityPart.Ready(aliases), aliasBusy = false, aliasInput = "") }
            } catch (e: SocialWebException) {
                _ui.update { it.copy(aliasBusy = false, aliasError = IdentityFailure(e.status, e.code)) }
            } catch (e: GhostException) {
                _ui.update { it.copy(aliasBusy = false, aliasError = IdentityFailure(error = e)) }
            }
        }
    }

    fun removeAlias(alias: AccountAlias) {
        _ui.update { it.copy(removingAlias = alias.apId, aliasError = null) }
        viewModelScope.launch {
            try {
                val aliases = identity.removeAlias(accountId, alias)
                _ui.update { it.copy(aliases = IdentityPart.Ready(aliases), removingAlias = null) }
            } catch (e: SocialWebException) {
                _ui.update { it.copy(removingAlias = null, aliasError = IdentityFailure(e.status, e.code)) }
            } catch (e: GhostException) {
                _ui.update { it.copy(removingAlias = null, aliasError = IdentityFailure(error = e)) }
            }
        }
    }

    fun setMoveInput(value: String) {
        _ui.update { it.copy(moveInput = value, moveError = null) }
    }

    /** Checks the destination, then asks before doing anything: a move can't be called back. */
    fun askToMove() {
        val ui = _ui.value
        val ownHandle = ui.domain.value?.handle?.takeIf { it.isNotBlank() } ?: _storedHandle.value
        // A move that has started can only be repeated to its own destination, as Ghost Admin allows.
        val typed = if (ui.migration?.targetApId != null) ui.migration?.targetHandle.orEmpty() else ui.moveInput
        val handle = normalizeHandleInput(typed)
        if (handle == null) {
            _ui.update { it.copy(moveError = IdentityFailure(status = 400)) }
            return
        }
        if (ownHandle.isNotBlank() && sameHandle(handle, ownHandle)) {
            _ui.update { it.copy(moveError = IdentityFailure(status = 400, code = SelfMove)) }
            return
        }
        _ui.update { it.copy(confirmMove = handle, moveError = null) }
    }

    fun cancelMove() {
        _ui.update { if (it.moving) it else it.copy(confirmMove = null) }
    }

    fun move() {
        val target = _ui.value.confirmMove ?: return
        if (_ui.value.moving) return
        _ui.update { it.copy(moving = true, moveError = null) }
        viewModelScope.launch {
            try {
                val result = identity.moveAccount(accountId, target)
                _ui.update { it.copy(moving = false, confirmMove = null, migration = result, moveInput = "") }
            } catch (e: SocialWebException) {
                _ui.update { it.copy(moving = false, confirmMove = null, moveError = IdentityFailure(e.status, e.code)) }
            } catch (e: GhostException) {
                _ui.update { it.copy(moving = false, confirmMove = null, moveError = IdentityFailure(error = e)) }
            }
        }
    }

    /** Runs one read of the service, keeping a failure instead of throwing it. */
    private suspend fun <T> asked(read: suspend () -> T): IdentityPart<T> = try {
        IdentityPart.Ready(read())
    } catch (e: SocialWebException) {
        IdentityPart.Failed(e)
    } catch (e: GhostException) {
        IdentityPart.Failed(e)
    }

    private companion object {
        const val SelfMove = "SELF_MOVE"
        val MoveUnavailableStatuses = setOf(404, 405, 501)
    }
}

/**
 * The site's social web identity: its handle and the custom domain it can answer to, with the redirect
 * that domain needs, the accounts that moved to it, and moving its followers away. Everything here is
 * saved on the site, so it reads the same as in Ghost Admin.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HandleSettingsScreen(accountId: String, onBack: () -> Unit) {
    val viewModel = hiltViewModel<HandleSettingsViewModel, HandleSettingsViewModel.Factory>(key = "handle-$accountId") { it.create(accountId) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val storedHandle by viewModel.storedHandle.collectAsStateWithLifecycle()
    // The username can be edited in Edit profile, so the handle is read again on each return.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshDomain()
        onPauseOrDispose {}
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.identity_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                HandleCard(ui.domain, storedHandle)

                SectionTitle(R.string.identity_domain_section, Modifier.padding(top = 16.dp))
                DomainSection(ui, viewModel)

                SectionTitle(R.string.identity_aliases, Modifier.padding(top = 24.dp))
                AliasesSection(ui, viewModel)

                if (!ui.migrationHidden) {
                    SectionTitle(R.string.identity_move_away, Modifier.padding(top = 24.dp))
                    MoveSection(ui, viewModel)
                }
            }
        }
    }

    ui.confirmDomain?.let { choice ->
        val loaded = ui.domain.value
        val current = loaded?.handle?.takeIf { it.isNotBlank() } ?: storedHandle
        // The handle the account gets, not just the domain, so the dialog reads as a before and after.
        val next = ui.validatedDomain?.let { domain -> loaded?.username?.takeIf { it.isNotBlank() }?.let { "@$it@$domain" } }.orEmpty()
        AlertDialog(
            onDismissRequest = viewModel::cancelDomainConfirm,
            title = { Text(stringResource(if (choice == DomainRemove) R.string.identity_domain_remove_title else R.string.identity_domain_confirm_title)) },
            text = {
                Text(
                    if (choice == DomainRemove) {
                        stringResource(R.string.identity_domain_remove_text, current)
                    } else {
                        stringResource(R.string.identity_domain_confirm_text, current, next)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::saveDomain, enabled = !ui.domainBusy) {
                    Text(
                        stringResource(if (choice == DomainRemove) R.string.identity_domain_remove else R.string.identity_domain_save),
                        color = if (choice == DomainRemove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDomainConfirm, enabled = !ui.domainBusy) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    ui.confirmMove?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::cancelMove,
            title = { Text(stringResource(R.string.identity_move_confirm_title)) },
            text = { Text(stringResource(R.string.identity_move_confirm_text, target)) },
            confirmButton = {
                TextButton(
                    onClick = viewModel::move,
                    enabled = !ui.moving,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    if (ui.moving) LoadingIndicator(Modifier.size(24.dp)) else Text(stringResource(R.string.identity_move_action))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelMove, enabled = !ui.moving) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

/** The handle in full, its two parts labelled, with a way to copy it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HandleCard(domain: IdentityPart<SocialWebDomain>, storedHandle: String) {
    val loaded = domain.value
    val handle = loaded?.handle?.takeIf { it.isNotBlank() } ?: storedHandle
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.identity_handle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (handle.isNotBlank()) CopyButton(handle, stringResource(R.string.settings_bluesky_copy_handle))
            }
            if (handle.isBlank()) {
                LoadingIndicator(Modifier.size(24.dp))
            } else {
                Text(handle, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                if (loaded != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LabeledValue(stringResource(R.string.identity_handle_username), loaded.username)
                        LabeledValue(stringResource(R.string.identity_handle_domain), loaded.handleDomain, highlight = true)
                    }
                }
            }
            Explainer(stringResource(R.string.identity_handle_explainer))
        }
    }
}

/** A value with its caption above it; the domain of the handle is tinted, as Ghost Admin tints it. */
@Composable
private fun LabeledValue(label: String, value: String, highlight: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DomainSection(ui: HandleUi, viewModel: HandleSettingsViewModel) {
    val part = ui.domain
    val loaded = part.value
    when {
        loaded != null -> {
            if (ui.editingDomain) {
                DomainEditor(ui, loaded, viewModel)
            } else {
                OutlinedTextField(
                    value = loaded.domain ?: loaded.actorHost,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.identity_domain_field)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::editDomain, enabled = !ui.domainBusy) {
                        Text(stringResource(R.string.identity_domain_change))
                    }
                    if (loaded.domain != null) {
                        TextButton(onClick = viewModel::askToRemoveDomain, enabled = !ui.domainBusy) {
                            Text(stringResource(R.string.identity_domain_remove), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Explainer(stringResource(R.string.identity_domain_explainer))
            }
        }
        part is IdentityPart.Failed -> PartFailure(part.error, viewModel::loadDomain, R.string.identity_domain_forbidden)
        else -> Box(Modifier.padding(vertical = 8.dp)) { LoadingIndicator(Modifier.size(32.dp)) }
    }
    ui.domainError?.let { DomainErrorText(it) }
}

/** The domain input and, once it's entered, the redirect Ghost needs on it before it can be validated. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DomainEditor(ui: HandleUi, current: SocialWebDomain, viewModel: HandleSettingsViewModel) {
    val domain = normalizeDomainInput(ui.domainInput)
    OutlinedTextField(
        value = ui.domainInput,
        onValueChange = viewModel::setDomainInput,
        label = { Text(stringResource(R.string.identity_domain_field)) },
        placeholder = { Text(current.actorHost) },
        singleLine = true,
        enabled = !ui.domainBusy,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    when {
        !ui.instructionsShown -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::cancelDomainEdit, enabled = !ui.domainBusy) { Text(stringResource(R.string.dialog_cancel)) }
            Button(onClick = viewModel::showDomainInstructions, enabled = !ui.domainBusy && domain != null) {
                Text(stringResource(R.string.identity_domain_activate))
            }
        }
        domain != null -> {
            // What the handle becomes, before anything is saved.
            Text(
                stringResource(R.string.identity_domain_preview, "@${current.username}@$domain"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.identity_redirect_title), style = MaterialTheme.typography.titleSmall)
            Explainer(stringResource(R.string.identity_redirect_body))
            CopyableValue(stringResource(R.string.identity_redirect_from), "https://$domain/.well-known/webfinger")
            CopyableValue(stringResource(R.string.identity_redirect_to), "https://${current.actorHost}/.well-known/webfinger")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::cancelDomainEdit, enabled = !ui.domainBusy) { Text(stringResource(R.string.dialog_cancel)) }
                if (ui.validatedDomain != null) {
                    Button(onClick = viewModel::askToSaveDomain, enabled = !ui.domainBusy) {
                        if (ui.domainBusy) LoadingIndicator(Modifier.size(24.dp)) else Text(stringResource(R.string.identity_domain_save))
                    }
                } else {
                    Button(onClick = viewModel::validateDomain, enabled = !ui.domainBusy) {
                        if (ui.domainBusy) LoadingIndicator(Modifier.size(24.dp)) else Text(stringResource(R.string.identity_domain_validate))
                    }
                }
            }
            if (ui.validatedDomain != null) Explainer(stringResource(R.string.identity_domain_validated))
        }
        // Nothing readable typed yet: the field's hint shows the domain in use.
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AliasesSection(ui: HandleUi, viewModel: HandleSettingsViewModel) {
    Explainer(stringResource(R.string.identity_aliases_explainer))
    val part = ui.aliases
    val aliases = part.value
    when {
        aliases != null -> {
            // Newest first, as Ghost Admin lists them.
            if (aliases.isNotEmpty()) {
                HorizontalDivider()
                aliases.asReversed().forEach { alias ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(alias.display, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { viewModel.removeAlias(alias) }, enabled = ui.removingAlias == null) {
                            if (ui.removingAlias == alias.apId) {
                                LoadingIndicator(Modifier.size(18.dp))
                            } else {
                                Text(stringResource(R.string.identity_alias_unlink))
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
            OutlinedTextField(
                value = ui.aliasInput,
                onValueChange = viewModel::setAliasInput,
                label = { Text(stringResource(R.string.identity_alias_field)) },
                supportingText = { Text(stringResource(R.string.identity_alias_hint)) },
                singleLine = true,
                enabled = !ui.aliasBusy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = viewModel::addAlias, enabled = !ui.aliasBusy && ui.aliasInput.isNotBlank()) {
                if (ui.aliasBusy) LoadingIndicator(Modifier.size(24.dp)) else Text(stringResource(R.string.identity_alias_add))
            }
        }
        part is IdentityPart.Failed -> PartFailure(part.error, viewModel::loadAliases, R.string.identity_aliases_forbidden)
        else -> Box(Modifier.padding(vertical = 8.dp)) { LoadingIndicator(Modifier.size(32.dp)) }
    }
    ui.aliasError?.let { AliasErrorText(it) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MoveSection(ui: HandleUi, viewModel: HandleSettingsViewModel) {
    if (ui.migrationForbidden) {
        Explainer(stringResource(R.string.identity_move_forbidden))
        return
    }
    val migration = ui.migration
    if (migration == null) {
        val failure = ui.migrationError
        if (failure != null) {
            Text(errorMessage(failure), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = viewModel::loadMigration) { Text(stringResource(R.string.retry)) }
        } else {
            Box(Modifier.padding(vertical = 8.dp)) { LoadingIndicator(Modifier.size(32.dp)) }
        }
        return
    }
    if (migration.sent) {
        Text(stringResource(R.string.identity_move_sent, migration.targetHandle.orEmpty()), style = MaterialTheme.typography.bodyLarge)
        return
    }
    Explainer(
        if (migration.targetApId != null) {
            stringResource(R.string.identity_move_in_progress, migration.targetHandle.orEmpty())
        } else {
            stringResource(R.string.identity_move_explainer)
        },
    )
    val locked = migration.targetApId != null
    OutlinedTextField(
        value = if (locked) migration.targetHandle.orEmpty() else ui.moveInput,
        onValueChange = viewModel::setMoveInput,
        readOnly = locked,
        label = { Text(stringResource(R.string.identity_move_field)) },
        supportingText = { Text(stringResource(R.string.identity_move_hint)) },
        singleLine = true,
        enabled = !ui.moving,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = viewModel::askToMove,
        enabled = !ui.moving && (locked || ui.moveInput.isNotBlank()),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
    ) {
        Text(stringResource(R.string.identity_move_action))
    }
    ui.moveError?.let { MoveErrorText(it) }
}

/** A read of the service that failed: why, and a retry unless the staff user may not see it at all. */
@Composable
private fun PartFailure(error: Throwable, onRetry: () -> Unit, forbidden: Int) {
    val forbiddenForRole = error is SocialWebException && (error.status == 401 || error.status == 403)
    Text(
        if (forbiddenForRole) stringResource(forbidden) else errorMessage(error),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    if (!forbiddenForRole) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
}

/** A label and a value to copy into the DNS or redirect settings of the custom domain. */
@Composable
private fun CopyableValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            CopyButton(value, stringResource(R.string.identity_copy_value))
        }
    }
}

/** Copies [value]; shows a check for a moment. */
@Composable
private fun CopyButton(value: String, description: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    IconButton(onClick = {
        scope.launch {
            clipboard.setClipEntry(ClipData.newPlainText(description, value).toClipEntry())
            copied = true
        }
    }) {
        Icon(
            if (copied) AppIcons.Check else AppIcons.ContentCopy,
            contentDescription = if (copied) stringResource(R.string.settings_bluesky_handle_copied) else description,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Why the domain change didn't happen. [IdentityFailure.error] covers network and other failures. */
@Composable
private fun DomainErrorText(failure: IdentityFailure) {
    val text = when {
        failure.forbidden -> stringResource(R.string.identity_domain_forbidden)
        failure.code == "invalid-domain" -> stringResource(R.string.identity_domain_invalid)
        failure.code == "invalid-webfinger" -> stringResource(R.string.identity_domain_no_redirect)
        failure.code == "conflict" -> stringResource(R.string.identity_domain_conflict)
        failure.code == "not-reachable" -> stringResource(R.string.identity_domain_unreachable)
        failure.code == "wrong-actor" -> stringResource(R.string.identity_domain_wrong_actor)
        failure.error != null -> errorMessage(failure.error)
        else -> errorMessage(GhostException.Unexpected(failure.status ?: 0, null))
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun AliasErrorText(failure: IdentityFailure) {
    val text = when {
        failure.forbidden -> stringResource(R.string.identity_aliases_forbidden)
        failure.status == 400 -> stringResource(R.string.identity_alias_invalid)
        failure.status == 404 -> stringResource(R.string.identity_alias_not_found)
        failure.error != null -> errorMessage(failure.error)
        else -> errorMessage(GhostException.Unexpected(failure.status ?: 0, null))
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun MoveErrorText(failure: IdentityFailure) {
    val text = when {
        failure.forbidden -> stringResource(R.string.identity_move_forbidden)
        failure.status == 400 && failure.code == "SELF_MOVE" -> stringResource(R.string.identity_move_self)
        failure.status == 400 -> stringResource(R.string.identity_move_invalid)
        failure.status == 404 -> stringResource(R.string.identity_move_not_found)
        failure.status == 409 -> stringResource(R.string.identity_move_conflict)
        failure.status == 422 -> stringResource(R.string.identity_move_alias_required)
        failure.error != null -> errorMessage(failure.error)
        else -> errorMessage(GhostException.Unexpected(failure.status ?: 0, null))
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

