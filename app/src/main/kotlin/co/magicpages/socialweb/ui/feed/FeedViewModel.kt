package co.magicpages.socialweb.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.paging.filter
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.Moderation
import co.magicpages.socialweb.data.activitypub.FreshAvatars
import co.magicpages.socialweb.data.activitypub.PostInteractions
import co.magicpages.socialweb.ui.posts.FeedItem
import co.magicpages.socialweb.ui.posts.PostsPagingSource
import co.magicpages.socialweb.ui.posts.RepostGroups
import co.magicpages.socialweb.ui.posts.ReplyComposer
import co.magicpages.socialweb.ui.posts.postMessages
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** The two timelines of followed accounts. */
enum class Feed {
    /** Reader: articles, long-form posts. */
    Reader,

    /** Notes: short posts. */
    Notes,
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = FeedViewModel.Factory::class)
class FeedViewModel @AssistedInject constructor(
    @Assisted feed: Feed,
    accounts: AccountStore,
    private val activityPub: ActivityPubClient,
    private val interactions: PostInteractions,
    private val freshAvatars: FreshAvatars,
    private val moderation: Moderation,
    /** The app's one note composer, hosted above the navigation. */
    val noteComposer: NoteComposer,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(feed: Feed): FeedViewModel
    }

    // Reposts of one post are folded into one item per load of the list: a refresh starts anew.
    private val repostGroups = MutableStateFlow(RepostGroups())

    val posts: Flow<PagingData<FeedItem>> = accounts.account
        .filterNotNull()
        .distinctUntilChangedBy { it.site to it.credential }
        .flatMapLatest { account ->
            Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
                val groups = RepostGroups().also { repostGroups.value = it }
                PostsPagingSource(interactions, groups = groups) { next ->
                    when (feed) {
                        Feed.Reader -> activityPub.readerFeed(account.site, account.credential, next)
                        Feed.Notes -> activityPub.notesFeed(account.site, account.credential, next)
                    }.also { freshAvatars.track(it.posts) }
                }
            }.flow
        }
        .cachedIn(viewModelScope)
        .combine(interactions.overrides) { data, overrides ->
            if (overrides.isEmpty()) data else data.map { it.with(overrides) }
        }
        .combine(repostGroups.flatMapLatest { it.late }) { data, late ->
            if (late.isEmpty()) data else data.map { it.withLateReposters(late) }
        }
        // What was deleted or blocked just now leaves at once; the service leaves it out of the next load.
        .combine(moderation.hidden) { data, hidden ->
            if (hidden.isEmpty) data else data.filter { !it.isHiddenBy(hidden) }
        }

    val composer = ReplyComposer(viewModelScope, interactions)
    val messages: Flow<Int> = merge(
        postMessages(interactions, composer, moderation),
        // The composer is the app's only one: only the Notes list reports on notes.
        if (feed == Feed.Notes) noteComposer.posted.map { R.string.note_posted } else emptyFlow(),
    )

    fun toggleLike(post: Post) = interactions.toggleLike(post)
    fun toggleRepost(post: Post) = interactions.toggleRepost(post)
}
