package co.magicpages.socialweb.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.Credential
import co.magicpages.socialweb.data.account.GhostSite
import co.magicpages.socialweb.data.account.Profile
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostAdminClient
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.ghost.SignInResult
import co.magicpages.socialweb.data.ghost.StaffTokenJwt
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val ghost: GhostAdminClient,
    private val activityPub: ActivityPubClient,
    private val accounts: AccountStore,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** Cookies of a session that still needs its emailed code. */
    private var pendingCookies: Map<String, String> = emptyMap()

    fun onSiteInputChange(value: String) = _state.update { it.copy(siteInput = value, error = null) }
    fun onMethodChange(value: LoginMethod) = _state.update { it.copy(method = value, error = null) }
    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onStaffTokenChange(value: String) = _state.update { it.copy(staffToken = value, error = null) }
    fun onCodeChange(value: String) = _state.update { it.copy(code = value.filter(Char::isDigit).take(6), error = null) }

    fun onChangeSite() {
        pendingCookies = emptyMap()
        _state.update { LoginUiState(siteInput = it.siteInput) }
    }

    fun submitSite() = launchStep {
        val site = ghost.lookUpSite(state.value.siteInput)
        _state.update { it.copy(site = site, step = LoginStep.Credentials) }
    }

    /**
     * Looks up [address] straight away, for signing in again to a site that's stored: the form starts
     * at the sign-in step. Only on a fresh form, so coming back to the screen doesn't look it up again.
     */
    fun startAt(address: String) {
        if (state.value.siteInput.isNotEmpty()) return
        _state.update { it.copy(siteInput = address) }
        submitSite()
    }

    fun submitCredentials() {
        val current = state.value
        val site = checkNotNull(current.site)
        when (current.method) {
            LoginMethod.Password -> launchStep {
                when (val result = ghost.signIn(site, current.email, current.password)) {
                    is SignInResult.SignedIn -> finish(site, result.credential)
                    is SignInResult.NeedsVerification -> {
                        pendingCookies = result.pendingCookies
                        _state.update { it.copy(step = LoginStep.Verify, password = "") }
                    }
                }
            }
            LoginMethod.StaffToken -> {
                val token = current.staffToken.trim()
                if (StaffTokenJwt.isValid(token)) {
                    launchStep { finish(site, Credential.StaffToken(token)) }
                } else {
                    _state.update { it.copy(error = LoginError.InvalidStaffToken) }
                }
            }
        }
    }

    fun submitCode() = launchStep {
        val site = checkNotNull(state.value.site)
        finish(site, ghost.verifySession(site, pendingCookies, state.value.code))
    }

    fun resendCode() = launchStep {
        ghost.resendVerificationCode(checkNotNull(state.value.site), pendingCookies)
        _state.update { it.copy(codeResent = true, code = "") }
    }

    /**
     * Confirms the social web works for this staff user, then stores the account and makes it the
     * active one; signing in to a site that is stored already replaces its credential.
     */
    private suspend fun finish(site: GhostSite, credential: Credential) {
        val account = activityPub.currentAccount(site, credential)
        accounts.add(
            StoredAccount(
                site = site,
                credential = credential,
                profile = Profile(account.name, account.handle, account.avatarUrl),
            ),
        )
        _state.update { it.copy(finished = true) }
    }

    private fun launchStep(block: suspend () -> Unit) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val error = try {
                block()
                null
            } catch (e: GhostException) {
                e.toLoginError()
            } catch (e: SocialWebException) {
                LoginError.SocialWebUnavailable(e.status)
            }
            _state.update { it.copy(busy = false, error = error) }
        }
    }

    private fun GhostException.toLoginError(): LoginError = when (this) {
        GhostException.NotAGhostSite -> LoginError.NotAGhostSite
        is GhostException.UnsupportedVersion -> LoginError.UnsupportedVersion(version)
        is GhostException.Network -> LoginError.Network
        is GhostException.Unauthorized -> LoginError.NotAllowed(ghostMessage)
        is GhostException.Rejected -> LoginError.Rejected(ghostMessage)
        is GhostException.TooManyAttempts -> LoginError.TooManyAttempts
        is GhostException.Unexpected -> LoginError.Unexpected(status)
    }
}

data class LoginUiState(
    val step: LoginStep = LoginStep.Site,
    val siteInput: String = "",
    val site: GhostSite? = null,
    val method: LoginMethod = LoginMethod.Password,
    val email: String = "",
    val password: String = "",
    val staffToken: String = "",
    val code: String = "",
    val codeResent: Boolean = false,
    val busy: Boolean = false,
    val error: LoginError? = null,
    /** The account was stored: a login shown on top of the app is done and can close. */
    val finished: Boolean = false,
)

enum class LoginStep { Site, Credentials, Verify }

enum class LoginMethod { Password, StaffToken }

sealed interface LoginError {
    data object NotAGhostSite : LoginError
    data class UnsupportedVersion(val version: String) : LoginError
    data object Network : LoginError
    data object InvalidStaffToken : LoginError
    /** Ghost refused the credential, or the user isn't the Owner or an Administrator. */
    data class NotAllowed(val ghostMessage: String?) : LoginError
    /** Wrong email, password or code; Ghost's message says which. */
    data class Rejected(val ghostMessage: String?) : LoginError
    data object TooManyAttempts : LoginError
    data class SocialWebUnavailable(val status: Int) : LoginError
    data class Unexpected(val status: Int) : LoginError
}
