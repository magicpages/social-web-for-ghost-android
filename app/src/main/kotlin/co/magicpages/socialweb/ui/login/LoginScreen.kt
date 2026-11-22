package co.magicpages.socialweb.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.theme.Gutter

/**
 * Signing in to a Ghost site. Shown alone when nobody is signed in; to add another site it is
 * shown on top of the app, with [onClose] to leave it (back button and system back) and called
 * once the account was added. [onBack] is for the first run, where the screen follows the
 * welcome: it only leaves, and is not called once the account was added. With [siteAddress] it
 * starts at the sign-in step for that site, for signing in to a stored site again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onClose: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    siteAddress: String? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val leave = onClose ?: onBack
    LaunchedEffect(siteAddress) {
        if (siteAddress != null) viewModel.startAt(siteAddress)
    }
    // Back from the sign-in or code step returns to the address step instead of leaving the screen.
    // While a request is in flight it is swallowed, so a late result can't land on a reset form.
    BackHandler(enabled = state.step != LoginStep.Site || onBack != null) {
        if (state.busy) return@BackHandler
        if (state.step != LoginStep.Site) viewModel.onChangeSite() else onBack?.invoke()
    }
    LaunchedEffect(state.finished) {
        if (state.finished) onClose?.invoke()
    }
    val motion = MaterialTheme.motionScheme
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (leave != null) {
                // A bar of its own, so the form scrolls under it instead of under a floating button.
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = leave, enabled = !state.busy) {
                            Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // The bar already keeps clear of the status bar.
                    .windowInsetsPadding(
                        if (leave != null) WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) else WindowInsets.safeDrawing,
                    )
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth()
                        .padding(horizontal = Gutter)
                        .padding(vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(if (siteAddress != null) R.string.sign_in_again else R.string.login_title), style = MaterialTheme.typography.headlineMedium)
                    AnimatedContent(
                        targetState = state.step,
                        transitionSpec = {
                            (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec()))
                                .using(SizeTransform(sizeAnimationSpec = { _, _ -> motion.defaultSpatialSpec() }))
                        },
                        label = "login step",
                    ) { step ->
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            when (step) {
                                LoginStep.Site -> SiteStep(state, viewModel)
                                LoginStep.Credentials -> CredentialsStep(state, viewModel)
                                LoginStep.Verify -> VerifyStep(state, viewModel)
                            }
                        }
                    }
                    state.error?.let { ErrorText(it) }
                    // After the form and its error, so a failed attempt is never pushed below the fold.
                    AnimatedVisibility(
                        visible = state.step == LoginStep.Site,
                        enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()),
                        exit = fadeOut(motion.defaultEffectsSpec()) + shrinkVertically(motion.defaultSpatialSpec()),
                    ) {
                        NoSiteHint()
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteStep(state: LoginUiState, viewModel: LoginViewModel) {
    Requirements()
    OutlinedTextField(
        value = state.siteInput,
        onValueChange = viewModel::onSiteInputChange,
        label = { Text(stringResource(R.string.login_site_label)) },
        placeholder = { Text("example.com") },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { viewModel.submitSite() }),
        modifier = Modifier.fillMaxWidth(),
    )
    PrimaryButton(R.string.login_continue, busy = state.busy, enabled = state.siteInput.isNotBlank(), onClick = viewModel::submitSite)
}

@Composable
private fun CredentialsStep(state: LoginUiState, viewModel: LoginViewModel) {
    val site = state.site ?: return
    SiteHeader(site.title, site.siteUrl, onChange = viewModel::onChangeSite, enabled = !state.busy)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        LoginMethod.entries.forEachIndexed { index, method ->
            SegmentedButton(
                selected = state.method == method,
                onClick = { viewModel.onMethodChange(method) },
                shape = SegmentedButtonDefaults.itemShape(index, LoginMethod.entries.size),
                enabled = !state.busy,
            ) {
                Text(stringResource(if (method == LoginMethod.Password) R.string.login_method_password else R.string.login_method_token))
            }
        }
    }
    when (state.method) {
        LoginMethod.Password -> {
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = { Text(stringResource(R.string.login_email_label)) },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username + ContentType.EmailAddress },
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text(stringResource(R.string.login_password_label)) },
                singleLine = true,
                enabled = !state.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.submitCredentials() }),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
            )
            PrimaryButton(
                R.string.login_sign_in,
                busy = state.busy,
                enabled = state.email.isNotBlank() && state.password.isNotEmpty(),
                onClick = viewModel::submitCredentials,
            )
        }
        LoginMethod.StaffToken -> {
            TokenHowTo(adminUrl = site.adminApiUrl.removeSuffix("api/admin/"), enabled = !state.busy)
            OutlinedTextField(
                value = state.staffToken,
                onValueChange = viewModel::onStaffTokenChange,
                label = { Text(stringResource(R.string.login_token_label)) },
                singleLine = true,
                enabled = !state.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { viewModel.submitCredentials() }),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(R.string.login_sign_in, busy = state.busy, enabled = state.staffToken.isNotBlank(), onClick = viewModel::submitCredentials)
        }
    }
}

@Composable
private fun VerifyStep(state: LoginUiState, viewModel: LoginViewModel) {
    val site = state.site ?: return
    SiteHeader(site.title, site.siteUrl, onChange = viewModel::onChangeSite, enabled = !state.busy)
    Text(
        stringResource(if (state.codeResent) R.string.login_code_resent else R.string.login_code_intro),
        style = MaterialTheme.typography.bodyLarge,
    )
    OutlinedTextField(
        value = state.code,
        onValueChange = viewModel::onCodeChange,
        label = { Text(stringResource(R.string.login_code_label)) },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { viewModel.submitCode() }),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
    )
    PrimaryButton(R.string.login_verify, busy = state.busy, enabled = state.code.length == 6, onClick = viewModel::submitCode)
    TextButton(onClick = viewModel::resendCode, enabled = !state.busy) { Text(stringResource(R.string.login_resend_code)) }
}

@Composable
private fun SiteHeader(title: String, url: String, onChange: () -> Unit, enabled: Boolean) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onChange, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.login_change_site))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrimaryButton(label: Int, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        if (busy) LoadingIndicator(Modifier.size(32.dp)) else Text(stringResource(label))
    }
}

@Composable
private fun ErrorText(error: LoginError) {
    val text = when (error) {
        LoginError.NotAGhostSite -> stringResource(R.string.login_error_not_ghost)
        is LoginError.UnsupportedVersion -> stringResource(R.string.login_error_old_ghost, error.version)
        LoginError.Network -> stringResource(R.string.error_network)
        LoginError.InvalidStaffToken -> stringResource(R.string.login_error_token_format)
        is LoginError.NotAllowed -> stringResource(R.string.login_error_not_allowed) + error.ghostMessage.asSuffix()
        is LoginError.Rejected -> error.ghostMessage ?: stringResource(R.string.login_error_rejected)
        LoginError.TooManyAttempts -> stringResource(R.string.login_error_too_many)
        is LoginError.SocialWebUnavailable -> stringResource(R.string.login_error_social_web_off, error.status)
        is LoginError.Unexpected -> stringResource(R.string.error_unexpected, error.status)
    }
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

private fun String?.asSuffix() = if (isNullOrBlank()) "" else "\n\nGhost: $this"

/** What the site needs, up front, so nobody types an address only to be turned away. */
@Composable
private fun Requirements() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.login_requirements_title), style = MaterialTheme.typography.titleSmall)
            listOf(
                R.string.login_requirement_version,
                R.string.login_requirement_social_web,
                R.string.login_requirement_role,
            ).forEach { text ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Where to find a Staff Access Token; the token path skips the password and the emailed code. */
@Composable
private fun TokenHowTo(adminUrl: String, enabled: Boolean) {
    val uriHandler = LocalUriHandler.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.login_token_howto_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.login_token_intro), style = MaterialTheme.typography.bodyMedium)
            listOf(
                R.string.login_token_step_1,
                R.string.login_token_step_2,
                R.string.login_token_step_3,
                R.string.login_token_step_4,
            ).forEachIndexed { index, text ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onClick = { uriHandler.openUri(adminUrl) }, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
                Icon(AppIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.login_open_ghost_admin), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Managed Ghost hosting providers, for people who have no site yet; the names are brands, not translated. */
private data class HostingProvider(val name: String, val url: String, val byThisApp: Boolean = false)

private val HostingProviders = listOf(
    // Magic Pages makes this app: first, and labelled as such rather than listed as a neutral pick.
    HostingProvider("Magic Pages", "https://www.magicpages.co/pricing/", byThisApp = true),
    HostingProvider("Ghost(Pro)", "https://ghost.org/pricing/"),
    HostingProvider("Synaps Media", "https://www.synapsmedia.com/pricing/"),
)

@Composable
private fun NoSiteHint() {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.login_no_site_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.login_no_site_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedCard(Modifier.fillMaxWidth()) {
            HostingProviders.forEach { provider ->
                ListItem(
                    headlineContent = { Text(provider.name) },
                    supportingContent = if (provider.byThisApp) {
                        { Text(stringResource(R.string.login_hosting_developer)) }
                    } else {
                        null
                    },
                    trailingContent = { Icon(AppIcons.OpenInNew, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(
                        onClickLabel = stringResource(R.string.open_in_browser),
                        role = Role.Button,
                    ) { uriHandler.openUri(provider.url) },
                )
            }
        }
    }
}
