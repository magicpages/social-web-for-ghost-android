package co.magicpages.socialweb.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.activitypub.Topic
import co.magicpages.socialweb.data.curator.TopTopicSlug
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.settings.AppSettings
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.explore.TopicChip
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.theme.Gutter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Ghost's topics for the Reader's pages, and which of them the account pinned there. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReaderTopicsViewModel @Inject constructor(
    accounts: AccountStore,
    private val activityPub: ActivityPubClient,
    private val settings: AppSettings,
) : ViewModel() {
    private val retries = MutableStateFlow(0)

    // Only a failed load is worth asking again; retrying one still under way would cancel and repeat it.
    @Volatile private var failed = false

    /**
     * The service's topics but "Top", in its order; empty until they have loaded, and when they can't
     * be, which leaves the Reader with its timeline alone. A failed reload keeps the topics already
     * shown. Kept while the Reader is out of sight, so coming back doesn't slide it to the timeline.
     */
    val topics: StateFlow<List<Topic>> = accounts.account
        .filterNotNull()
        .distinctUntilChangedBy { it.site to it.credential }
        .flatMapLatest { account ->
            retries
                .mapLatest {
                    try {
                        activityPub.topics(account.site, account.credential).also { failed = false }
                    } catch (_: SocialWebException) {
                        failed = true
                        null
                    } catch (_: GhostException) {
                        failed = true
                        null
                    }
                }
                .filterNotNull()
        }
        .map { loaded -> loaded.filter { it.slug != TopTopicSlug } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val activeId: StateFlow<String?> = accounts.activeId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The slugs the active account pinned; a slug the service no longer has is simply never shown. */
    val pinned: StateFlow<Set<String>> = combine(activeId, settings.pinnedTopics) { id, pins -> id?.let { pins[it] }.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Pins [slug] to the active account's Reader, or takes it off. */
    fun setPinned(slug: String, pinned: Boolean) {
        val id = activeId.value ?: return
        settings.setTopicPinned(id, slug, pinned)
    }

    /** Asks again for topics that couldn't be loaded. */
    fun retryIfFailed() {
        if (failed) retries.update { it + 1 }
    }
}

/**
 * The Reader's lists as pages that slide sideways under chips: the timeline of followed accounts
 * first, then one page for each topic the account pinned, in the service's order. The chip of the page
 * shown is the selected one, and picking a chip slides to its page; the last chip opens the choice of
 * topics. [page] draws one page: its topic (null for the timeline), whether it is the page shown, and
 * a callback for when its list has loaded.
 *
 * The pages beside the one shown are composed, and so load, only once the page shown has: a page that
 * can't load isn't made worse by its neighbours asking too, and the first list gets the connection to itself.
 */
@Composable
internal fun ReaderPager(
    modifier: Modifier = Modifier,
    page: @Composable (topic: Topic?, current: Boolean, onLoaded: () -> Unit) -> Unit,
) {
    val viewModel = hiltViewModel<ReaderTopicsViewModel>()
    val topics by viewModel.topics.collectAsStateWithLifecycle()
    val pinned by viewModel.pinned.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        viewModel.retryIfFailed()
        onPauseOrDispose {}
    }
    // Null is the timeline of followed accounts.
    val sections = remember(topics, pinned) { listOf<Topic?>(null) + topics.filter { it.slug in pinned } }
    val pagerState = rememberPagerState { sections.size }
    val scope = rememberCoroutineScope()
    // Slugs of the lists that have loaded; "" is the timeline.
    val loaded = remember { mutableStateListOf<String>() }
    val shown = pagerState.currentPage.coerceIn(sections.indices)
    val shownLoaded = sections[shown]?.slug.orEmpty() in loaded

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Without the service's topics there is nothing to pin, and the timeline needs no chip of its own.
        if (topics.isNotEmpty()) {
            val chips = rememberLazyListState()
            // The chip of a page swiped to comes into view.
            LaunchedEffect(shown) {
                val info = chips.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == shown }
                if (item == null || item.offset < info.viewportStartOffset || item.offset + item.size > info.viewportEndOffset) {
                    chips.animateScrollToItem(shown)
                }
            }
            LazyRow(
                modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
                state = chips,
                contentPadding = PaddingValues(horizontal = Gutter, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(sections, key = { _, topic -> topic?.slug ?: "" }) { index, topic ->
                    TopicChip(
                        label = topic?.name ?: stringResource(R.string.reader_following),
                        selected = index == shown,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    )
                }
                item(key = ChooseTopicsKey) {
                    AssistChip(
                        onClick = { choosing = true },
                        label = { Text(stringResource(R.string.reader_topics_choose)) },
                        leadingIcon = { Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    )
                }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = Gutter,
            beyondViewportPageCount = if (shownLoaded) 1 else 0,
            key = { sections.getOrNull(it)?.slug.orEmpty() },
        ) { index ->
            val topic = sections.getOrNull(index)
            val slug = topic?.slug.orEmpty()
            page(topic, index == pagerState.currentPage) { if (slug !in loaded) loaded += slug }
        }
    }

    if (choosing) {
        TopicsSheet(
            topics = topics,
            pinned = pinned,
            onToggle = { slug -> viewModel.setPinned(slug, slug !in pinned) },
            onDismiss = { choosing = false },
        )
    }
}

// No topic has an empty slug or this one, so the chip's key can't clash with a page's.
private const val ChooseTopicsKey = "+"

/** Picking which of Ghost's [topics] get a page in the Reader; each tap takes effect at once, so the sheet has no button. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TopicsSheet(topics: List<Topic>, pinned: Set<String>, onToggle: (slug: String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item(key = "header") {
                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.reader_topics_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.reader_topics_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(topics, key = { _, topic -> topic.slug }) { index, topic ->
                val checked = topic.slug in pinned
                SegmentedListItem(
                    onClick = { onToggle(topic.slug) },
                    shapes = ListItemDefaults.segmentedShapes(index, topics.size),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        role = Role.Checkbox
                        toggleableState = ToggleableState(checked)
                    },
                    leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                ) {
                    Text(topic.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
