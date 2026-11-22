package co.magicpages.socialweb.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.background.NotificationAnnouncer
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.Notification
import co.magicpages.socialweb.data.feed.FeedFilter
import co.magicpages.socialweb.data.fediverse.FediverseExplore
import co.magicpages.socialweb.data.settings.AppSettings
import co.magicpages.socialweb.data.translation.TranslationSettings
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.feed.FeedFiltersScreen
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.theme.Gutter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettings,
    private val announcer: NotificationAnnouncer,
    translation: TranslationSettings,
    accounts: AccountStore,
) : ViewModel() {
    /** Every signed-in site, each with a row that opens its own settings; empty until they've been read. */
    val sites: StateFlow<List<StoredAccount>> = accounts.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val autoplayVideos: StateFlow<Boolean> = settings.autoplayVideos
    val announcedTypes: StateFlow<Set<Notification.Type>> = settings.announcedTypes
    val feedFilter: StateFlow<FeedFilter> = settings.feedFilter
    val fediverseExplore: StateFlow<FediverseExplore> = settings.fediverseExplore

    /** The language posts are translated into, as a BCP 47 tag; null for the app's language. */
    val translationTarget: StateFlow<String?> = translation.target
    val translatesAutomatically: StateFlow<Boolean> = translation.automatic

    private val _notificationsAllowed = MutableStateFlow(announcer.canAnnounce())

    /** Whether Android would show a system notification right now. It changes outside the app, so see [refreshNotificationsAllowed]. */
    val notificationsAllowed: StateFlow<Boolean> = _notificationsAllowed.asStateFlow()

    /** The permission and channel are changed in Android's settings: look again when the screen is shown. */
    fun refreshNotificationsAllowed() {
        _notificationsAllowed.value = announcer.canAnnounce()
    }

    fun setAutoplayVideos(enabled: Boolean) = settings.setAutoplayVideos(enabled)
}

/** Settings, with the feed filters laid over it when opened from its row: they have no screen of their own to navigate to. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSite: (accountId: String) -> Unit,
    onAddSite: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenTranslation: () -> Unit,
    onOpenFediverse: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var showFeedFilters by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        SettingsContent(
            onBack,
            onOpenSite,
            onAddSite,
            onOpenNotifications,
            onOpenTranslation,
            onOpenFediverse,
            onOpenAbout,
            onOpenFeedFilters = { showFeedFilters = true },
            viewModel = viewModel,
        )
        if (showFeedFilters) FeedFiltersScreen(onBack = { showFeedFilters = false })
    }
}

/**
 * A row for each signed-in site, which opens what that site keeps for itself, then what applies to
 * every site, each row saying how it's set, then experiments and About.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsContent(
    onBack: () -> Unit,
    onOpenSite: (accountId: String) -> Unit,
    onAddSite: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenTranslation: () -> Unit,
    onOpenFediverse: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenFeedFilters: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val sites by viewModel.sites.collectAsStateWithLifecycle()
    val autoplay by viewModel.autoplayVideos.collectAsStateWithLifecycle()
    val announced by viewModel.announcedTypes.collectAsStateWithLifecycle()
    val notificationsAllowed by viewModel.notificationsAllowed.collectAsStateWithLifecycle()
    val feedFilter by viewModel.feedFilter.collectAsStateWithLifecycle()
    val target by viewModel.translationTarget.collectAsStateWithLifecycle()
    val automatic by viewModel.translatesAutomatically.collectAsStateWithLifecycle()
    val fediverse by viewModel.fediverseExplore.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshNotificationsAllowed()
        onPauseOrDispose {}
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                if (sites.isNotEmpty()) {
                    SectionTitle(R.string.settings_sites)
                    SegmentedGroup {
                        sites.forEachIndexed { index, site ->
                            LinkRow(
                                title = site.site.title,
                                status = site.profile.handle,
                                onClick = { onOpenSite(site.id) },
                                index = index,
                                count = sites.size + 1,
                                leading = { Avatar(site.profile.avatarUrl, site.site.title, 40.dp) },
                            )
                        }
                        ActionRow(
                            title = stringResource(R.string.account_add_site),
                            icon = AppIcons.Add,
                            onClick = onAddSite,
                            index = sites.size,
                            count = sites.size + 1,
                            iconSize = 40.dp,
                        )
                    }
                    Explainer(stringResource(R.string.settings_sites_footer))
                }

                SectionTitle(R.string.settings_all_sites, Modifier.padding(top = if (sites.isNotEmpty()) 16.dp else 0.dp))
                SegmentedGroup {
                    LinkRow(
                        title = stringResource(R.string.settings_notifications),
                        status = notificationsStatus(notificationsAllowed, announced),
                        statusColor = if (notificationsAllowed) Color.Unspecified else MaterialTheme.colorScheme.error,
                        onClick = onOpenNotifications,
                        index = 0,
                        count = 4,
                        icon = AppIcons.Notifications,
                    )
                    LinkRow(
                        title = stringResource(R.string.feed_filters_title),
                        status = feedFilterStatus(feedFilter),
                        onClick = onOpenFeedFilters,
                        index = 1,
                        count = 4,
                        icon = AppIcons.VisibilityOff,
                    )
                    val language = languageName(target ?: appLanguageTag())
                    LinkRow(
                        title = stringResource(R.string.settings_translation),
                        status = stringResource(if (automatic) R.string.settings_translation_status_automatic else R.string.settings_translation_status, language),
                        onClick = onOpenTranslation,
                        index = 2,
                        count = 4,
                        icon = AppIcons.Translate,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_autoplay_videos),
                        summary = stringResource(R.string.settings_autoplay_videos_summary),
                        checked = autoplay,
                        onCheckedChange = viewModel::setAutoplayVideos,
                        index = 3,
                        count = 4,
                        icon = AppIcons.PlayCircle,
                    )
                }
                Explainer(stringResource(R.string.settings_all_sites_footer))

                SectionTitle(R.string.settings_experimental, Modifier.padding(top = 16.dp))
                LinkRow(
                    title = stringResource(R.string.settings_fediverse_explore),
                    status = if (fediverse.enabled) {
                        stringResource(R.string.explore_fediverse_trending, fediverse.server)
                    } else {
                        stringResource(R.string.settings_fediverse_explore_off)
                    },
                    onClick = onOpenFediverse,
                    index = 0,
                    count = 1,
                    icon = AppIcons.Public,
                )
                Explainer(stringResource(R.string.settings_experimental_footer))

                LinkRow(
                    title = stringResource(R.string.settings_about),
                    status = stringResource(R.string.settings_about_summary),
                    onClick = onOpenAbout,
                    index = 0,
                    count = 1,
                    icon = AppIcons.Info,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

/** "All on", "3 of 5 on" or "Off"; that Android blocks them, when it does, as nothing else matters then. */
@Composable
private fun notificationsStatus(allowed: Boolean, announced: Set<Notification.Type>): String {
    val on = AnnouncedTypeLabels.count { (type, _) -> type in announced }
    val all = AnnouncedTypeLabels.size
    return when {
        !allowed -> stringResource(R.string.settings_notifications_android_off)
        on == all -> stringResource(R.string.settings_notifications_all_on)
        on == 0 -> stringResource(R.string.settings_notifications_off)
        else -> stringResource(R.string.settings_notifications_some_on, on, all)
    }
}

/** What the feed filters leave out: "Reposts hidden · 2 muted words", or that nothing is. */
@Composable
private fun feedFilterStatus(filter: FeedFilter): String {
    val parts = buildList {
        if (filter.hideReposts) add(stringResource(R.string.feed_filters_status_reposts))
        val words = filter.mutedWords.size
        if (words > 0) add(pluralStringResource(R.plurals.feed_filters_status_words, words, words))
    }
    return parts.ifEmpty { listOf(stringResource(R.string.feed_filters_status_none)) }.joinToString(" · ")
}
