package co.magicpages.socialweb.ui.home

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.UnreadNotifications
import co.magicpages.socialweb.ui.components.AccountMenu
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.feed.Feed
import co.magicpages.socialweb.ui.explore.ExploreScreen
import co.magicpages.socialweb.ui.feed.FeedScreen
import co.magicpages.socialweb.ui.notifications.NotificationsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The top-level destinations, in navigation order. [Notes] is where the app starts. */
enum class HomeTab(@StringRes val label: Int) {
    Reader(R.string.nav_reader),
    Notes(R.string.nav_notes),
    Explore(R.string.nav_explore),
    Notifications(R.string.nav_notifications),
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    accountStore: AccountStore,
    private val unread: UnreadNotifications,
    private val tabRequests: HomeTabRequests,
) : ViewModel() {
    val account: StateFlow<StoredAccount?> =
        accountStore.account.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every signed-in account, for the account menu. */
    val accounts: StateFlow<List<StoredAccount>> =
        accountStore.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unreadCount: StateFlow<Int> = unread.count

    /** The other accounts' unread counts, for the account menu. */
    val otherUnreadCounts: StateFlow<Map<String, Int>> = unread.others

    /** The tab a tapped system notification asks for; null once [HomeScreen] has switched to it. */
    val requestedTab: StateFlow<HomeTabRequest?> = tabRequests.pending

    fun tabShown() = tabRequests.consume()

    fun refreshUnread() {
        viewModelScope.launch { unread.refresh() }
        refreshOtherUnread()
    }

    fun refreshOtherUnread() {
        viewModelScope.launch { unread.refreshOthers() }
    }
}

/**
 * The tabs and their navigation bar (a rail on wide windows). Each tab keeps its list, scroll
 * position and view model while another is shown: they all live in this one back stack entry.
 */
@Composable
fun HomeScreen(
    /** The account these screens are for; see [HomeTabRequest]. */
    accountId: String,
    /** A thread or profile is open in the detail pane beside the tabs. */
    detailOpen: Boolean,
    onOpenPost: (Post) -> Unit,
    onOpenPostById: (postId: String, url: String?) -> Unit,
    onOpenProfile: (Author) -> Unit,
    onOpenOwnProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFediverseSettings: () -> Unit,
    onAddSite: () -> Unit,
    onSwitchAccount: (accountId: String) -> Unit,
    onSignOut: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val unread by viewModel.unreadCount.collectAsStateWithLifecycle()
    val otherUnread by viewModel.otherUnreadCounts.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(HomeTab.Notes) }
    val requestedTab by viewModel.requestedTab.collectAsStateWithLifecycle()
    LaunchedEffect(requestedTab, accountId) {
        // Only the screens of the account the request names take it, whichever one is on screen first.
        requestedTab?.takeIf { it.isFor(accountId) }?.let {
            tab = it.tab
            viewModel.tabShown()
        }
    }
    // Taps on the already-selected tab, per tab; each asks that tab to scroll back to the top.
    var reselections by rememberSaveable { mutableStateOf(IntArray(HomeTab.entries.size)) }
    val tabStates = rememberSaveableStateHolder()

    // Coming back to the app, or from a post, is when new notifications are most likely.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshUnread()
        onPauseOrDispose {}
    }
    // Back from another tab returns to the start tab before it leaves the app. With a screen open beside
    // the tabs on a wide window, back closes that first.
    BackHandler(enabled = tab != HomeTab.Notes && !detailOpen) { tab = HomeTab.Notes }

    val accountMenu: @Composable () -> Unit = {
        account?.let {
            AccountMenu(
                active = it,
                accounts = accounts,
                otherUnread = otherUnread,
                onOpen = viewModel::refreshOtherUnread,
                onSwitch = onSwitchAccount,
                onAddSite = onAddSite,
                onOpenProfile = onOpenOwnProfile,
                onOpenSettings = onOpenSettings,
                onSignOut = onSignOut,
            )
        }
    }
    // Drawn over the whole screen, navigation included; Explore covers it with a copy of its search.
    var cover by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    Box(Modifier.fillMaxSize()) {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                HomeTab.entries.forEach { entry ->
                    val selected = entry == tab
                    item(
                        selected = selected,
                        // Decided on the tab selected at the tap, not when the bar was last built.
                        onClick = {
                            if (tab == entry) {
                                reselections = reselections.copyOf().also { it[entry.ordinal]++ }
                            } else {
                                tab = entry
                            }
                        },
                        icon = { Icon(entry.icon(selected), contentDescription = null) },
                        label = { TabLabel(stringResource(entry.label)) },
                        badge = if (entry == HomeTab.Notifications && unread > 0) {
                            {
                                val description = pluralStringResource(R.plurals.nav_unread, unread, unread)
                                Badge(Modifier.semantics { contentDescription = description }) {
                                    Text(if (unread > 99) "99+" else unread.toString())
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            },
        ) {
            tabStates.SaveableStateProvider(tab.name) {
                val count = reselections[tab.ordinal]
                when (tab) {
                    HomeTab.Reader, HomeTab.Notes -> FeedScreen(
                        feed = if (tab == HomeTab.Reader) Feed.Reader else Feed.Notes,
                        account = account,
                        reselections = count,
                        onOpenPost = onOpenPost,
                        onOpenProfile = onOpenProfile,
                        onSignOut = onSignOut,
                        accountMenu = accountMenu,
                    )
                    HomeTab.Explore -> ExploreScreen(
                        reselections = count,
                        onOpenPost = onOpenPost,
                        onOpenProfile = onOpenProfile,
                        onSignOut = onSignOut,
                        accountMenu = accountMenu,
                        onCover = { cover = it },
                        onChangeFediverseSource = onOpenFediverseSettings,
                    )
                    HomeTab.Notifications -> NotificationsScreen(
                        account = account,
                        reselections = count,
                        onOpenPost = onOpenPostById,
                        onOpenProfile = onOpenProfile,
                        onSignOut = onSignOut,
                        accountMenu = accountMenu,
                    )
                }
            }
        }
        cover?.invoke()
    }
}

private fun HomeTab.icon(selected: Boolean): ImageVector = when (this) {
    HomeTab.Reader -> if (selected) AppIcons.ReaderFilled else AppIcons.Reader
    HomeTab.Notes -> AppIcons.Notes
    HomeTab.Notifications -> if (selected) AppIcons.NotificationsFilled else AppIcons.Notifications
    HomeTab.Explore -> if (selected) AppIcons.ExploreFilled else AppIcons.Explore
}

/**
 * A tab's label on one line. With a larger font it shrinks to fit, down to its size at 100%, then
 * ends in an ellipsis; wrapped, "Notifications" would break mid-word.
 */
@Composable
private fun TabLabel(text: String) {
    val size = LocalTextStyle.current.fontSize
    val fontScale = LocalDensity.current.fontScale
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = if (fontScale > 1f) TextAutoSize.StepBased(minFontSize = size / fontScale, maxFontSize = size) else null,
    )
}
