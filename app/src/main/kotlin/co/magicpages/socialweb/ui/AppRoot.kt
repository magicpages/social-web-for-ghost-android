package co.magicpages.socialweb.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.Profile
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.FollowList
import co.magicpages.socialweb.data.activitypub.MentionDirectory
import co.magicpages.socialweb.data.activitypub.Moderation
import co.magicpages.socialweb.data.activitypub.SensitiveMediaPreference
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.ghost.GhostAdminClient
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.gif.Gifs
import co.magicpages.socialweb.data.linkpreview.LinkPreviews
import co.magicpages.socialweb.data.settings.AppSettings
import co.magicpages.socialweb.ui.about.AboutScreen
import co.magicpages.socialweb.ui.about.LicensesScreen
import co.magicpages.socialweb.ui.about.TranslationInfoScreen
import co.magicpages.socialweb.ui.components.CustomEmojiSource
import co.magicpages.socialweb.ui.components.ImageViewerHost
import co.magicpages.socialweb.ui.components.LocalBrowserUriHandler
import co.magicpages.socialweb.ui.components.LocalCustomEmojis
import co.magicpages.socialweb.ui.components.LocalGifs
import co.magicpages.socialweb.ui.components.LocalLinkPreviews
import co.magicpages.socialweb.ui.components.LocalMentionDirectory
import co.magicpages.socialweb.ui.components.LocalOwnProfile
import co.magicpages.socialweb.ui.components.PostLinks
import co.magicpages.socialweb.ui.components.ProfileLinks
import co.magicpages.socialweb.ui.home.HomeScreen
import co.magicpages.socialweb.ui.home.HomeTabRequests
import co.magicpages.socialweb.ui.login.LoginScreen
import co.magicpages.socialweb.ui.moderation.LocalModeration
import co.magicpages.socialweb.ui.moderation.LocalOpenReport
import co.magicpages.socialweb.ui.moderation.ReportKey
import co.magicpages.socialweb.ui.moderation.ReportScreen
import co.magicpages.socialweb.ui.onboarding.SignedOutFlow
import co.magicpages.socialweb.ui.onboarding.SilkMark
import co.magicpages.socialweb.ui.onboarding.WelcomeScreen
import co.magicpages.socialweb.ui.feed.NoteComposer
import co.magicpages.socialweb.ui.feed.NoteComposerScope
import co.magicpages.socialweb.ui.quote.SharedContentRequests
import co.magicpages.socialweb.ui.posts.LinkedPosts
import co.magicpages.socialweb.ui.posts.LocalLinkedPosts
import co.magicpages.socialweb.ui.posts.LocalShowSensitiveMedia
import co.magicpages.socialweb.ui.profile.FollowsScreen
import co.magicpages.socialweb.ui.profile.ProfileScreen
import co.magicpages.socialweb.ui.reach.PostReachScreen
import co.magicpages.socialweb.ui.reach.ReachScreen
import co.magicpages.socialweb.ui.settings.BlockedScreen
import co.magicpages.socialweb.ui.settings.FediverseSettingsScreen
import co.magicpages.socialweb.ui.settings.NotificationSettingsScreen
import co.magicpages.socialweb.ui.settings.SettingsScreen
import co.magicpages.socialweb.ui.settings.SiteSettingsScreen
import co.magicpages.socialweb.ui.settings.TranslationSettingsScreen
import co.magicpages.socialweb.ui.thread.ThreadScreen
import co.magicpages.socialweb.ui.translation.LocalPostTranslations
import co.magicpages.socialweb.ui.translation.PostTranslations
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** How long the predictive back motion lasts end to end; release plays the share the finger left. */
private const val PredictiveBackMillis = 400

/** The tabs: Reader, Notes, Notifications. */
@Serializable
data object HomeKey : NavKey

/**
 * The thread around one post. [postId] is the post's ActivityPub id; [url] is the browser fallback. With [resolve],
 * [postId] is a web address (a link in a post, or a shared one) that the service must resolve to the post first,
 * and the browser takes over, in place of this screen, when it isn't a post.
 */
@Serializable
data class PostKey(val postId: String, val url: String? = null, val resolve: Boolean = false) : NavKey

/** A profile by `@user@domain` handle, or `me`; [url] is the browser fallback if it won't load. */
@Serializable
data class ProfileKey(val handle: String, val url: String? = null) : NavKey

/** Followers | Following of a profile, opened on [list]; [name] titles the screen. */
@Serializable
data class FollowsKey(val handle: String, val name: String, val list: FollowList) : NavKey

/** Reach: how far the site's own posts travelled lately. */
@Serializable
data object ReachKey : NavKey

/** How the site's own post [postId] travelled: who passed it on, and how far it could have gone. */
@Serializable
data class PostReachKey(val postId: String) : NavKey

/** Settings: a row for each signed-in site's own settings, then what applies to every site. */
@Serializable
data object SettingsKey : NavKey

/** What the signed-in site [accountId] keeps for itself: colors, blocks, sensitive media, Bluesky. */
@Serializable
data class SiteSettingsKey(val accountId: String) : NavKey

/** Which kinds of activity become system notifications, for every site. */
@Serializable
data object NotificationSettingsKey : NavKey

/** The accounts and servers the signed-in site [accountId] has blocked, with a way to unblock each. */
@Serializable
data class BlockedKey(val accountId: String) : NavKey

/** About: the app's version, links, and licenses. */
@Serializable
data object AboutKey : NavKey

/** The open-source licenses of the libraries the app ships with. */
@Serializable
data object LicensesKey : NavKey

/** How translation works and why it's private. */
@Serializable
data object TranslationInfoKey : NavKey

/** Translation settings: the language to translate into, translating on its own, downloads. */
@Serializable
data object TranslationSettingsKey : NavKey

/** The experimental Fediverse source of Explore: on or off, and which server's trends it shows. */
@Serializable
data object FediverseSettingsKey : NavKey

/** The welcome pages, as a tour from About. */
@Serializable
data object WelcomeTourKey : NavKey

/**
 * Signing in to a Ghost site, on top of the screens of the active account: another site, or with
 * [siteAddress] a stored one again.
 */
@Serializable
data class AddSiteKey(val siteAddress: String? = null) : NavKey

@HiltViewModel
class AppViewModel @Inject constructor(
    private val accounts: AccountStore,
    private val ghost: GhostAdminClient,
    private val settings: AppSettings,
    /** Shared by every post card through [LocalLinkedPosts]. */
    val linkedPosts: LinkedPosts,
    /** Shared by every post card through [LocalPostTranslations]. */
    val postTranslations: PostTranslations,
    /** Tab requests from tapped system notifications; HomeScreen shows the tab. */
    val homeTabRequests: HomeTabRequests,
    /** Shared by every post and name through [LocalCustomEmojis]. */
    val customEmojis: CustomEmojiSource,
    /** The one note composer: quoting from any screen, and shares from other apps. */
    val noteComposer: NoteComposer,
    val sharedContent: SharedContentRequests,
    /** Links other apps asked to open here ("Open in Social Web"). */
    val openLinks: OpenLinkRequests,
    /** People to mention in composers, through [LocalMentionDirectory]. */
    val mentionDirectory: MentionDirectory,
    /** Deleting and blocking from post and profile menus, through [LocalModeration]. */
    val moderation: Moderation,
    /** Link cards in posts and the composer, through [LocalLinkPreviews]. */
    val linkPreviews: LinkPreviews,
    /** GIF search in composers, through [LocalGifs]. */
    val gifs: Gifs,
    /** Whether sensitive media shows without a tap, through [LocalShowSensitiveMedia]. */
    val sensitiveMedia: SensitiveMediaPreference,
) : ViewModel() {
    private var generation = 0

    /** What the app shows: loading until the stored accounts have been read, then login or the active account's screens. */
    val state: StateFlow<AppState> = accounts.activeId
        .map { id -> if (id == null) AppState.SignedOut else AppState.SignedIn(id, ++generation) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppState.Loading)

    /** The writer's avatar in composers, through [LocalOwnProfile]. */
    val ownProfile: StateFlow<Profile?> = accounts.account
        .map { it?.profile }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // A site's accent colour themes the app; it can be changed in Ghost Admin at any time.
        viewModelScope.launch {
            accounts.activeId.filterNotNull().collectLatest {
                val site = accounts.account.first()?.site ?: return@collectLatest
                try {
                    accounts.updateAccentColor(site.siteUrl, ghost.accentColor(site))
                } catch (_: GhostException) {
                    // Offline or the site is unwell: the colour stored last time stays.
                }
            }
        }
    }

    /** Makes the account with [id] the active one; every screen starts over for it. */
    fun switchTo(id: String) {
        viewModelScope.launch {
            withContext(NonCancellable) { accounts.switchTo(id) }
        }
    }

    /**
     * Ends the active account's Ghost session (best effort) and forgets it. Another stored account
     * becomes active; with none left the login shows.
     */
    fun signOut() {
        viewModelScope.launch {
            withContext(NonCancellable) { accounts.account.first()?.let { forget(it) } }
        }
    }

    /** Like [signOut], for any stored site: settings remove one without switching to it first. */
    fun removeSite(accountId: String) {
        viewModelScope.launch {
            withContext(NonCancellable) { accounts.signedIn(accountId)?.let { forget(it) } }
        }
    }

    private suspend fun forget(account: StoredAccount) {
        try {
            ghost.signOut(account.site, account.credential)
        } catch (_: GhostException) {
            // Already invalid or offline: forgetting it locally is what matters.
        }
        accounts.remove(account.id)
        settings.forgetAccount(account.id)
    }
}

/** What [AppRoot] shows. */
sealed interface AppState {
    /** The stored accounts haven't been read yet. */
    data object Loading : AppState

    data object SignedOut : AppState

    /**
     * Signed in as [accountId]. [generation] counts the account changes of this run, so the screens
     * of one account are never confused with those of another, even when it is switched back to.
     */
    data class SignedIn(val accountId: String, val generation: Int) : AppState
}

@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ownProfile by viewModel.ownProfile.collectAsStateWithLifecycle()
    val showSensitiveMedia by viewModel.sensitiveMedia.preference.collectAsStateWithLifecycle()
    when (val current = state) {
        AppState.Loading -> Surface(Modifier.fillMaxSize()) {}
        AppState.SignedOut -> SignedOutFlow()
        is AppState.SignedIn -> CompositionLocalProvider(
            LocalLinkedPosts provides viewModel.linkedPosts,
            LocalPostTranslations provides viewModel.postTranslations,
            LocalCustomEmojis provides viewModel.customEmojis,
            LocalOwnProfile provides ownProfile,
            LocalMentionDirectory provides viewModel.mentionDirectory,
            LocalModeration provides viewModel.moderation,
            LocalLinkPreviews provides viewModel.linkPreviews,
            LocalGifs provides viewModel.gifs,
            LocalShowSensitiveMedia provides (showSensitiveMedia == true),
        ) {
            ImageViewerHost {
                NoteComposerScope(viewModel.noteComposer, viewModel.sharedContent) {
                    // Another account starts over with fresh screens: state that lives in the composition,
                    // such as a list's scroll position, must not carry over to the other account's lists.
                    key(current.generation) {
                        SignedInNavigation(
                            session = current,
                            onSignOut = viewModel::signOut,
                            onRemoveSite = viewModel::removeSite,
                            onSwitchAccount = viewModel::switchTo,
                            tabRequests = viewModel.homeTabRequests,
                            openLinks = viewModel.openLinks,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun SignedInNavigation(
    session: AppState.SignedIn,
    onSignOut: () -> Unit,
    onRemoveSite: (accountId: String) -> Unit,
    onSwitchAccount: (accountId: String) -> Unit,
    tabRequests: HomeTabRequests,
    openLinks: OpenLinkRequests,
) {
    // Another account starts over at Home (the caller recreates this per session): nothing open belongs to the one before.
    val backStack = rememberNavBackStack(HomeKey)
    // Entries (and their view models and saved state) are told apart per account session, so a
    // screen of one account is never reused for another, not even by the same key coming back later.
    val contentKey: (NavKey) -> Any = remember(session.generation) { { key: NavKey -> "${session.generation}/$key" } }
    // A tab can only be shown from the tabs screen: drop whatever is open above it.
    LaunchedEffect(tabRequests, backStack) {
        tabRequests.pending.filterNotNull().collect { request ->
            if (request.isFor(session.accountId)) while (backStack.size > 1) backStack.removeLastOrNull()
        }
    }
    // A double tap must not stack the same screen twice.
    val push: (NavKey) -> Unit = { key -> if (backStack.lastOrNull() != key) backStack.add(key) }
    val openPost: (Post) -> Unit = { push(PostKey(it.id, it.url.ifBlank { null })) }
    val openProfile: (Author) -> Unit = { push(ProfileKey(it.handle, it.url)) }
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() }
    // Taps in the tabs (the list pane) swap what the detail pane shows instead of stacking on it. On a phone
    // the tabs are only tapped with nothing above them, so this is a plain push there.
    val openFromList: (NavKey) -> Unit = { key ->
        if (backStack.size != 2 || backStack.last() != key) {
            while (backStack.size > 1) backStack.removeLastOrNull()
            backStack.add(key)
        }
    }

    // A link from another app: a profile opens as one, anything else is tried as a post. Like a tapped notification, it starts from the tabs.
    LaunchedEffect(openLinks, backStack) {
        openLinks.pending.filterNotNull().collect { url ->
            val handle = ProfileLinks.handleOf(url)
            openFromList(if (handle != null) ProfileKey(handle, url) else PostKey(url, url, resolve = true))
            openLinks.consume(url)
        }
    }

    // Mention links in post text open the profile in the app, and links to a post open its thread; every other link goes to the browser.
    val browser = LocalUriHandler.current
    val uriHandler = remember(browser, backStack) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val profile = ProfileLinks.parse(uri)
                when {
                    profile != null -> push(ProfileKey(profile.first, profile.second))
                    PostLinks.matches(uri) -> push(PostKey(uri, uri, resolve = true))
                    else -> browser.openUri(uri)
                }
            }
        }
    }

    val motion = MaterialTheme.motionScheme
    // A factory, not a shared instance: NavDisplay may adjust a ContentTransform's z-index.
    val popMotion = remember(motion) {
        {
            (slideInHorizontally(motion.defaultSpatialSpec()) { -it / 5 } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                (slideOutHorizontally(motion.defaultSpatialSpec()) { it / 5 } + fadeOut(motion.fastEffectsSpec()))
        }
    }
    // Android's predictive back for full-screen surfaces: as the finger moves, the screen shrinks to
    // 90% and fades out by 35% of the gesture, then the previous one fades in, settling from 110%.
    // NavDisplay seeks this by gesture progress as a share of its length, so all tweens end together
    // and the fades are linear; on release it plays the rest (or back, on cancel).
    val predictivePopMotion = remember {
        {
            val scale = tween<Float>(PredictiveBackMillis, easing = LinearOutSlowInEasing)
            val threshold = PredictiveBackMillis * 35 / 100
            (fadeIn(tween(PredictiveBackMillis - threshold, delayMillis = threshold, easing = LinearEasing)) + scaleIn(scale, initialScale = 1.1f)) togetherWith
                (fadeOut(tween(threshold, easing = LinearEasing)) + scaleOut(scale, targetScale = 0.9f))
        }
    }
    // Provided through a static local: a new lambda each recomposition would recompose every screen below.
    val openReport: (ReportKey) -> Unit = remember(backStack) { { key: ReportKey -> if (backStack.lastOrNull() != key) backStack.add(key) } }
    // The tabs are the list pane, threads and profiles the detail pane; settings and the like stay full-screen.
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(
        // Back steps through what was opened in the detail pane one screen at a time, as on a phone.
        backNavigationBehavior = BackNavigationBehavior.PopLatest,
    )
    // Remembered: entries with equal metadata are equal, so scenes aren't rebuilt on every recomposition.
    val homeMetadata = remember {
        ListDetailSceneStrategy.listPane(detailPlaceholder = { DetailPlaceholder() }) +
            ListDetailSceneStrategy.preferredPaneSize(width = ListPaneWidth)
    }
    val detailMetadata = remember { ListDetailSceneStrategy.detailPane() }
    CompositionLocalProvider(LocalUriHandler provides uriHandler, LocalBrowserUriHandler provides browser, LocalOpenReport provides openReport) {
        NavDisplay(
            backStack = backStack,
            onBack = back,
            // Home beside a thread or profile on wide windows; on compact widths the strategy yields and every
            // screen is full-screen as before, with the transitions below.
            sceneStrategies = listOf(listDetailStrategy),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            // Shared-axis motion: forward slides in from the end, back from the start.
            transitionSpec = {
                (slideInHorizontally(motion.defaultSpatialSpec()) { it / 5 } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                    (slideOutHorizontally(motion.defaultSpatialSpec()) { -it / 5 } + fadeOut(motion.fastEffectsSpec()))
            },
            popTransitionSpec = { popMotion() },
            predictivePopTransitionSpec = { predictivePopMotion() },
            entryProvider = entryProvider {
                entry<HomeKey>(clazzContentKey = contentKey, metadata = homeMetadata) {
                    SharedEdgeInsets(WindowInsetsSides.End) {
                        HomeScreen(
                            accountId = session.accountId,
                            detailOpen = backStack.size > 1,
                            onOpenPost = { openFromList(PostKey(it.id, it.url.ifBlank { null })) },
                            onOpenPostById = { id, url -> openFromList(PostKey(id, url?.ifBlank { null })) },
                            onOpenProfile = { openFromList(ProfileKey(it.handle, it.url)) },
                            onOpenOwnProfile = { openFromList(ProfileKey("me")) },
                            onOpenSettings = { push(SettingsKey) },
                            onOpenFediverseSettings = { push(FediverseSettingsKey) },
                            onAddSite = { push(AddSiteKey()) },
                            onSwitchAccount = onSwitchAccount,
                            onSignOut = onSignOut,
                        )
                    }
                }
                entry<PostKey>(clazzContentKey = contentKey, metadata = detailMetadata) { key ->
                    SharedEdgeInsets(WindowInsetsSides.Start) {
                        ThreadScreen(
                            key.postId,
                            key.url,
                            key.resolve,
                            onBack = back,
                            onOpenPost = openPost,
                            onOpenProfile = openProfile,
                            onSignOut = onSignOut,
                            onOpenReach = { push(PostReachKey(it)) },
                            // Not a post the service can show: the browser takes the address, and this screen goes, so back doesn't land on a dead end.
                            onUnresolved = {
                                backStack.remove(key)
                                browser.openUri(key.postId)
                            },
                        )
                    }
                }
                entry<ProfileKey>(clazzContentKey = contentKey, metadata = detailMetadata) { key ->
                    SharedEdgeInsets(WindowInsetsSides.Start) {
                        ProfileScreen(
                            key.handle,
                            key.url,
                            onBack = back,
                            onOpenPost = openPost,
                            onOpenProfile = openProfile,
                            onOpenFollows = { name, list -> push(FollowsKey(key.handle, name, list)) },
                            onOpenReach = { push(ReachKey) },
                            onSignOut = onSignOut,
                        )
                    }
                }
                entry<FollowsKey>(clazzContentKey = contentKey, metadata = detailMetadata) { key ->
                    SharedEdgeInsets(WindowInsetsSides.Start) {
                        FollowsScreen(
                            key.handle,
                            key.name,
                            key.list,
                            onBack = back,
                            onOpenProfile = openProfile,
                            onSignOut = onSignOut,
                        )
                    }
                }
                entry<ReachKey>(clazzContentKey = contentKey, metadata = detailMetadata) {
                    SharedEdgeInsets(WindowInsetsSides.Start) {
                        ReachScreen(onBack = back, onOpenPostReach = { push(PostReachKey(it)) }, onOpenProfile = openProfile, onSignOut = onSignOut)
                    }
                }
                entry<PostReachKey>(clazzContentKey = contentKey, metadata = detailMetadata) { key ->
                    SharedEdgeInsets(WindowInsetsSides.Start) {
                        PostReachScreen(
                            key.postId,
                            onBack = back,
                            onOpenPost = { push(PostKey(it)) },
                            onOpenProfile = openProfile,
                            onSignOut = onSignOut,
                        )
                    }
                }
                entry<SettingsKey>(clazzContentKey = contentKey) {
                    SettingsScreen(
                        onBack = back,
                        onOpenSite = { push(SiteSettingsKey(it)) },
                        onAddSite = { push(AddSiteKey()) },
                        onOpenNotifications = { push(NotificationSettingsKey) },
                        onOpenTranslation = { push(TranslationSettingsKey) },
                        onOpenFediverse = { push(FediverseSettingsKey) },
                        onOpenAbout = { push(AboutKey) },
                    )
                }
                entry<SiteSettingsKey>(clazzContentKey = contentKey) { key ->
                    SiteSettingsScreen(
                        accountId = key.accountId,
                        onBack = back,
                        onOpenBlocked = { push(BlockedKey(key.accountId)) },
                        onSignInAgain = { push(AddSiteKey(it)) },
                        onRemove = {
                            back()
                            onRemoveSite(key.accountId)
                        },
                    )
                }
                entry<NotificationSettingsKey>(clazzContentKey = contentKey) { NotificationSettingsScreen(onBack = back) }
                entry<TranslationSettingsKey>(clazzContentKey = contentKey) {
                    TranslationSettingsScreen(onBack = back, onOpenInfo = { push(TranslationInfoKey) })
                }
                entry<FediverseSettingsKey>(clazzContentKey = contentKey) { FediverseSettingsScreen(onBack = back) }
                entry<AboutKey>(clazzContentKey = contentKey) {
                    AboutScreen(
                        onBack = back,
                        onOpenLicenses = { push(LicensesKey) },
                        onOpenTranslation = { push(TranslationInfoKey) },
                        onOpenWelcome = { push(WelcomeTourKey) },
                    )
                }
                entry<LicensesKey>(clazzContentKey = contentKey) { LicensesScreen(onBack = back) }
                entry<TranslationInfoKey>(clazzContentKey = contentKey) { TranslationInfoScreen(onBack = back) }
                // The welcome pages again; their button closes the tour, back to About.
                entry<WelcomeTourKey>(clazzContentKey = contentKey) {
                    WelcomeScreen(onContinue = back, continueLabel = R.string.about_done)
                }
                entry<BlockedKey>(clazzContentKey = contentKey) { key ->
                    // Profiles open as the active account, and signing in again signs it out: neither fits another site's list.
                    val active = key.accountId == session.accountId
                    BlockedScreen(
                        accountId = key.accountId,
                        onBack = back,
                        onOpenProfile = if (active) openProfile else null,
                        onSignOut = if (active) onSignOut else null,
                    )
                }
                // A successful sign-in switches to that account, which starts over at Home; closing it covers signing in again to the active one.
                entry<AddSiteKey>(clazzContentKey = contentKey) { key -> LoginScreen(onClose = back, siteAddress = key.siteAddress) }
                entry<ReportKey>(clazzContentKey = contentKey) { key -> ReportScreen(key, onBack = back) }
            },
        )
    }
}

/** Quiet stand-in for the detail pane while nothing is open beside the tabs. */
@Composable
private fun DetailPlaceholder() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center) { SilkMark(PlaceholderMarkSize, Modifier.alpha(0.5f)) }
    }
}

/** The tabs' pane on wide windows: their rail (up to 96 dp) plus a 360 dp feed column. */
private val ListPaneWidth = 456.dp
private val PlaceholderMarkSize = 96.dp

/**
 * Beside another pane, the window's insets on the edge the two share (the middle of the screen) are not
 * for this pane's content: the list keeps its start side, the detail its end side. Alone, nothing changes.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun SharedEdgeInsets(sharedEdge: WindowInsetsSides, content: @Composable () -> Unit) {
    val sideBySide = LocalListDetailSceneScope.current != null
    Box(
        if (sideBySide) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(sharedEdge)) else Modifier,
        propagateMinConstraints = true,
    ) { content() }
}
