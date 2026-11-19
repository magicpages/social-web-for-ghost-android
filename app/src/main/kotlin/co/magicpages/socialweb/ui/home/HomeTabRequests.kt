package co.magicpages.socialweb.ui.home

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request from outside the UI (a tapped system notification) to show one of the tabs.
 *
 * @property accountId the account whose screens must show the tab; null for whichever is active.
 * The screens of any other account ignore the request, so it still reaches the right ones when it
 * arrives while the app is on another account and about to switch.
 */
data class HomeTabRequest(val tab: HomeTab, val accountId: String? = null) {
    fun isFor(activeAccountId: String): Boolean = accountId == null || accountId == activeAccountId
}

/**
 * Stays pending until the tabs screen of the right account exists to take it, which may be after a
 * cold start or an account switch.
 */
@Singleton
class HomeTabRequests @Inject constructor() {
    private val _pending = MutableStateFlow<HomeTabRequest?>(null)
    val pending: StateFlow<HomeTabRequest?> = _pending.asStateFlow()

    fun request(tab: HomeTab, accountId: String? = null) {
        _pending.value = HomeTabRequest(tab, accountId)
    }

    fun consume() {
        _pending.value = null
    }
}
