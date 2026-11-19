package co.magicpages.socialweb.ui.notifications

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Notification
import co.magicpages.socialweb.data.activitypub.NotificationPost
import co.magicpages.socialweb.data.activitypub.NotificationsPage
import co.magicpages.socialweb.data.activitypub.Moderation
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.activitypub.UnreadNotifications
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.ui.components.parsePostHtml
import co.magicpages.socialweb.ui.components.splitQuote
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    accounts: AccountStore,
    private val activityPub: ActivityPubClient,
    private val unread: UnreadNotifications,
    private val moderation: Moderation,
) : ViewModel() {
    @Volatile private var loaded = false

    /**
     * Notifications as rows, see [groupNotifications]. Blocks made while the list is on screen are
     * applied on top of the loaded pages. The service names actors by their row number, so only
     * a blocked server can match a notification here; a blocked account's notifications go away
     * with the next refresh, when the service no longer sends them.
     */
    val notifications: Flow<PagingData<NotificationGroup>> = accounts.account
        .filterNotNull()
        .distinctUntilChangedBy { it.site to it.credential }
        .flatMapLatest { account ->
            Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
                NotificationsPagingSource(
                    loadPage = { next -> activityPub.notifications(account.site, account.credential, next) },
                    hides = { moderation.hidden.value.hides(it) },
                    // Looking at the newest notifications is what reading them means.
                    onRefreshed = {
                        loaded = true
                        viewModelScope.launch { unread.markRead() }
                    },
                )
            }.flow
        }
        .cachedIn(viewModelScope)
        .combine(moderation.hidden) { data, hidden ->
            if (hidden.isEmpty) {
                data
            } else {
                data.map { it.without(hidden::hides) }.filter { it.actors.isNotEmpty() }
            }
        }

    /**
     * Emits whenever notifications arrived that the loaded list doesn't show yet: on coming back to
     * the tab, or once the badge count is refreshed while the tab is open.
     */
    val unseen: Flow<Unit> = unread.count.filter { it > 0 && loaded }.map { }
}

/** What a row shows of a post, worked out off the main thread. */
@Immutable
data class Excerpt(
    /** The title of an article, else the post's text; for a [sensitive] post its content warning. */
    val text: String,
    val sensitive: Boolean,
) {
    val isEmpty: Boolean get() = text.isBlank() && !sensitive
}

@Immutable
data class NotificationItem(
    val id: String,
    val type: Notification.Type,
    val actor: Author,
    val createdAtMillis: Long?,
    /** The liked or reposted post, or the reply or mention itself; null for follows. */
    val post: Excerpt?,
    val postId: String?,
    val postUrl: String?,
    /** For replies: what was replied to. */
    val inReplyTo: Excerpt?,
)

/**
 * Pages through notifications with the server's opaque `next` cursor and folds each page into rows
 * with [groupNotifications]. Kinds this app doesn't know, and actors [hides] covers, are left out
 * before grouping so the counts only name people who are shown. [onRefreshed] runs after the first
 * page of a generation loaded.
 *
 * Groups are cut at page boundaries: a person's likes that arrive on either side of one appear as
 * two rows. A page that was handed to Paging can't be changed afterwards, and holding back a
 * page's last group until the next page arrives would leave the list short of what the user can
 * already read; two rows say the same thing and nothing is dropped or shown twice.
 */
class NotificationsPagingSource(
    private val onRefreshed: () -> Unit,
    private val hides: (Author) -> Boolean = { false },
    private val loadPage: suspend (next: String?) -> NotificationsPage,
) : PagingSource<String, NotificationGroup>() {
    // Newest-first lists shift while the user scrolls, so a notification can show up on two pages;
    // Paging and LazyColumn keys both need each one once per generation.
    private val seen = HashSet<String>()

    override suspend fun load(params: LoadParams<String>): LoadResult<String, NotificationGroup> = try {
        var key = params.key
        var groups: List<NotificationGroup>
        var next: String?
        // A page of only unknown kinds would leave Paging with nothing to scroll toward the next one.
        do {
            val page = loadPage(key)
            groups = withContext(Dispatchers.Default) {
                groupNotifications(
                    page.notifications
                        .filter { it.type != Notification.Type.Unknown && !hides(it.actor) && seen.add(it.id) }
                        .map { it.toItem() },
                )
            }
            next = page.next
            key = next
        } while (groups.isEmpty() && next != null)
        if (params is LoadParams.Refresh) onRefreshed()
        LoadResult.Page(data = groups, prevKey = null, nextKey = next)
    } catch (e: GhostException) {
        LoadResult.Error(e)
    } catch (e: SocialWebException) {
        LoadResult.Error(e)
    }

    // Cursor lists can't resume mid-way; refreshes start from the newest notification.
    override fun getRefreshKey(state: PagingState<String, NotificationGroup>): String? = null
}

/** Call off the main thread. */
internal fun Notification.toItem() = NotificationItem(
    id = id,
    type = type,
    actor = actor,
    createdAtMillis = createdAt?.let {
        try {
            Instant.parse(it).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    },
    post = post?.excerpt(),
    postId = post?.id,
    postUrl = post?.url?.ifBlank { null },
    inReplyTo = inReplyTo?.excerpt(),
)

private const val MaxExcerptLength = 400
private val whitespace = Regex("\\s+")

private fun NotificationPost.excerpt(): Excerpt {
    val text = when {
        sensitive -> contentWarning.orEmpty().trim()
        isArticle && title.isNotBlank() -> title.trim()
        else -> {
            val (body, _) = splitQuote(content)
            if (body.isBlank()) "" else parsePostHtml(body).text.replace(whitespace, " ").trim()
        }
    }
    return Excerpt(text.take(MaxExcerptLength), sensitive)
}
