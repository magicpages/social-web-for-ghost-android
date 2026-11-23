package co.magicpages.socialweb.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.ghost.isGhostActivityPubUrl
import co.magicpages.socialweb.data.settings.ReaderBackground
import co.magicpages.socialweb.data.translation.TranslationProblem
import co.magicpages.socialweb.ui.article.ArticleSiteAction
import co.magicpages.socialweb.ui.article.LocalArticleAudio
import co.magicpages.socialweb.ui.article.LocalReaderSettings
import co.magicpages.socialweb.ui.article.ReaderSettingsAction
import co.magicpages.socialweb.ui.article.ReaderSettingsViewModel
import co.magicpages.socialweb.ui.article.ReaderViewAction
import co.magicpages.socialweb.ui.article.articleItems
import co.magicpages.socialweb.ui.article.rememberArticleAudio
import co.magicpages.socialweb.ui.article.rememberArticleSite
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.CenteredLoading
import co.magicpages.socialweb.ui.components.browserUriHandler
import co.magicpages.socialweb.ui.components.EmojiText
import co.magicpages.socialweb.ui.components.emojiSourceId
import co.magicpages.socialweb.ui.components.FullScreenError
import co.magicpages.socialweb.ui.components.errorMessage
import co.magicpages.socialweb.ui.posts.ActionBar
import co.magicpages.socialweb.ui.posts.ArticleMoreMenu
import co.magicpages.socialweb.ui.posts.CollectMessages
import co.magicpages.socialweb.ui.posts.ExternalPostLink
import co.magicpages.socialweb.ui.posts.FeedItem
import co.magicpages.socialweb.ui.posts.FocusedPost
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.posts.PostCallbacks
import co.magicpages.socialweb.ui.posts.ReplyEditorHost
import co.magicpages.socialweb.ui.posts.PostRow
import co.magicpages.socialweb.ui.posts.PostTextInset
import co.magicpages.socialweb.ui.theme.Gutter
import co.magicpages.socialweb.ui.theme.ReaderTheme
import co.magicpages.socialweb.ui.posts.displayName
import co.magicpages.socialweb.ui.quote.LocalQuotePassage
import co.magicpages.socialweb.ui.translation.ArticleTranslation
import co.magicpages.socialweb.ui.translation.ArticleTranslationBanner
import co.magicpages.socialweb.ui.translation.ArticleTranslateAction
import co.magicpages.socialweb.ui.translation.ArticleTranslationViewModel
import co.magicpages.socialweb.ui.translation.LocalTranslatesAutomatically
import co.magicpages.socialweb.ui.translation.message
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ThreadScreen(
    postId: String,
    postUrl: String?,
    resolve: Boolean,
    onBack: () -> Unit,
    onOpenPost: (Post) -> Unit,
    onOpenProfile: (Author) -> Unit,
    onSignOut: () -> Unit,
    onUnresolved: () -> Unit,
    /** Opens how one of the site's own posts travelled, by its id. */
    onOpenReach: (String) -> Unit,
) {
    val viewModel = hiltViewModel<ThreadViewModel, ThreadViewModel.Factory>(key = postId) { it.create(postId, resolve) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Deleting the post, or blocking its author, leaves nothing to read here.
    val postHidden by viewModel.postHidden.collectAsStateWithLifecycle()
    if (postHidden) LaunchedEffect(Unit) { onBack() }
    // A web address that isn't a post leaves for the browser, so no error screen is left behind.
    val unresolved by viewModel.unresolved.collectAsStateWithLifecycle()
    if (unresolved) LaunchedEffect(Unit) { onUnresolved() }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    // Not [postId]: for a web address, the thread's post has the id the service resolved it to.
    val focusedId by rememberUpdatedState((state as? ThreadUiState.Loaded)?.content?.post?.post?.id ?: postId)
    val callbacks = remember(viewModel) {
        PostCallbacks(
            // Tapping the post the thread is about does nothing; others open their own thread.
            open = { if (it.id != focusedId) onOpenPost(it) },
            openProfile = onOpenProfile,
            reply = viewModel.composer::start,
            toggleLike = viewModel::toggleLike,
            toggleRepost = viewModel::toggleRepost,
        )
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val loadedContent = (state as? ThreadUiState.Loaded)?.content
    val focused = loadedContent?.post
    // Known before the thread loads, so the browser stays reachable when the service can't serve it.
    // Ghost notes have none: their URL is their ActivityPub ID, which serves JSON.
    val webUrl = (focused?.post?.url?.takeIf { it.isNotBlank() } ?: postUrl)?.takeUnless(::isGhostActivityPubUrl)
    val listState = rememberLazyListState()
    val articleAudio = rememberArticleAudio()
    // Selecting words in the writer's own article offers to post them as a quote; other people's articles keep plain reading.
    val quotePassage = LocalQuotePassage.current
    val ownArticle = focused?.post?.takeIf { it.authoredByMe && it.isArticle && it.webUrl != null }
    val onQuotePassage: ((String) -> Unit)? = remember(quotePassage, ownArticle) {
        if (quotePassage == null || ownArticle == null) null else { passage -> quotePassage(ownArticle, passage) }
    }
    // Translating the article is the reader's choice (the top bar's menu), or the site's last one.
    // Its own key: sharing the thread's would make each ViewModel replace (and cancel) the other.
    val translation = hiltViewModel<ArticleTranslationViewModel>(key = "translation:$postId")
    val articleTranslation by translation.state.collectAsStateWithLifecycle()
    val canTranslate = remember(translation) { translation.isAvailable }
    // A content-warned article waits behind its warning until the reader asks for it.
    var articleShown by rememberSaveable(postId) { mutableStateOf(false) }
    val articleOpen = loadedContent?.article != null && (articleShown || focused?.post?.isWarned != true)
    // An article's title moves into the top bar once its own heading has scrolled away.
    val articleTitle = ((articleTranslation as? ArticleTranslation.Translated)?.title ?: focused?.post?.title)
        ?.takeIf { articleOpen && it.isNotBlank() }
    val headerScrolledAway by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val loadedArticle = loadedContent?.article
    LaunchedEffect(translation, loadedArticle, articleOpen) {
        if (articleOpen && loadedArticle != null && focused != null) translation.show(focused.post, loadedArticle)
    }
    val resources = LocalResources.current
    LaunchedEffect(translation) {
        translation.failures.collect { failure ->
            val needsDownload = failure.problem == TranslationProblem.NeedsDownload
            val result = snackbar.showSnackbar(
                failure.message(resources, article = true),
                actionLabel = if (needsDownload) resources.getString(R.string.translation_download) else null,
                duration = if (needsDownload) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) translation.download()
        }
    }

    val readerSettingsModel = hiltViewModel<ReaderSettingsViewModel>()
    val readerSettings by readerSettingsModel.settings.collectAsStateWithLifecycle()
    // The Ghost site the open article comes from. Its member session is read here, so the menu opens with the right entry.
    val articleSite = rememberArticleSite(focused?.post?.takeIf { articleOpen })
    val browser = browserUriHandler()

    // The reader's page colour covers the whole screen while an article is open, replies included.
    ReaderTheme(if (articleOpen) readerSettings.background else ReaderBackground.Auto) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        when {
                            articleTitle == null -> Text(stringResource(R.string.thread_title))
                            headerScrolledAway -> Text(articleTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        if (articleOpen && focused != null) ReaderSettingsAction(readerSettings, readerSettingsModel::update)
                        OpenInBrowserAction(webUrl)
                        if (articleOpen && focused != null) {
                            ArticleMoreMenu(focused) {
                                ArticleSiteAction(articleSite)
                                // The page is the browser's: this screen is the reader view, which is left for it.
                                ReaderViewAction(focused.post) { page ->
                                    browser.openUri(page)
                                    onBack()
                                }
                                if (canTranslate) ArticleTranslateAction(articleTranslation, onToggle = translation::toggle)
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            bottomBar = {
                focused?.takeIf { !it.post.isDeleted }?.let { ReplyBar(it) { viewModel.composer.start(it) } }
            },
        ) { padding ->
            when (val current = state) {
                ThreadUiState.Loading -> CenteredLoading(Modifier.padding(padding))
                is ThreadUiState.Failed -> FullScreenError(
                    message = if ((current.error as? SocialWebException)?.status == 404) {
                        stringResource(R.string.thread_not_found)
                    } else {
                        errorMessage(current.error)
                    },
                    onRetry = viewModel::retry,
                    modifier = Modifier.padding(padding),
                    sessionEnded = current.error is GhostException.Unauthorized,
                    onSignOut = onSignOut,
                    secondary = {
                        // The service only has posts it has seen; others still open at their origin.
                        if (webUrl != null) {
                            val uriHandler = browserUriHandler()
                            OutlinedButton(onClick = { uriHandler.openUri(webUrl) }) { Text(stringResource(R.string.open_in_browser)) }
                        }
                    },
                )
                is ThreadUiState.Loaded -> Column(Modifier.fillMaxSize().padding(padding)) {
                    if (articleOpen) ArticleTranslationBanner(articleTranslation, onShowOriginal = translation::showOriginal)
                    val pullState = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = current.refreshing,
                        onRefresh = viewModel::refresh,
                        state = pullState,
                        indicator = {
                            PullToRefreshDefaults.LoadingIndicator(pullState, current.refreshing, Modifier.align(Alignment.TopCenter))
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        var scrolled by remember { mutableStateOf(false) }
                        LaunchedEffect(listState) {
                            snapshotFlow { listState.isScrollInProgress }.first { it }
                            scrolled = true
                        }
                        val hasParent = current.content.ancestors.isNotEmpty() || current.content.parentLink != null
                        // A parent found after the thread showed lands above the post, and the list keeps the
                        // post in place. Bring the parent into view unless the reader has moved on.
                        LaunchedEffect(hasParent) {
                            if (hasParent && !scrolled) listState.requestScrollToItem(0)
                        }
                        KeepAncestorsInView(listState, current.content)
                        // Audio belongs to the screen, so it keeps playing while its player scrolls away. An opened
                        // post and its replies are translated on their own when Settings says so.
                        CompositionLocalProvider(
                            LocalArticleAudio provides articleAudio,
                            LocalReaderSettings provides readerSettings,
                            LocalTranslatesAutomatically provides true,
                        ) {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                state = listState,
                                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                thread(
                                    current.content, articleShown, articleTranslation, callbacks, onOpenPost,
                                    viewModel::loadMore, viewModel::loadEarlier, onQuotePassage, onOpenReach,
                                ) { articleShown = true }
                            }
                        }
                    }
                }
            }
        }
    }
    ReplyEditorHost(viewModel.composer)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun LazyListScope.thread(
    content: ThreadContent,
    articleShown: Boolean,
    translation: ArticleTranslation,
    callbacks: PostCallbacks,
    onOpenPost: (Post) -> Unit,
    onLoadMore: () -> Unit,
    onLoadEarlier: () -> Unit,
    onQuotePassage: ((String) -> Unit)?,
    onOpenReach: (String) -> Unit,
    onShowArticle: () -> Unit,
) {
    // The conversation so far runs into the post itself, joined by the thread line.
    val column = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth()
    if (content.hasEarlier) {
        item(key = "earlier", contentType = "link") {
            // The progress takes the button's height, so nothing below it shifts when one swaps for the other.
            Box(
                // The button's own padding puts its label on the posts' text column.
                column.padding(start = PostTextInset - 12.dp, top = 4.dp).heightIn(min = 48.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (content.loadingEarlier) {
                    LoadingIndicator()
                } else {
                    TextButton(onClick = onLoadEarlier) { Text(stringResource(R.string.thread_earlier)) }
                }
            }
        }
    }
    content.parentLink?.let { link ->
        item(key = "parent-link", contentType = "parent-link") {
            ExternalPostLink(
                label = stringResource(R.string.thread_in_reply_to),
                url = link.url,
                title = link.title,
                modifier = column.padding(start = Gutter, end = Gutter, top = 8.dp),
            )
        }
    }
    itemsIndexed(content.ancestors, key = { _, it -> "a:" + it.post.id }, contentType = { _, _ -> "row" }) { index, item ->
        PostRow(item, callbacks, column, connectAbove = index > 0 || content.hasEarlier, connectBelow = true, showContext = false)
    }
    val article = content.article
    val post = content.post.post
    if (article != null && post.isWarned && !articleShown) {
        item(key = "article-warning", contentType = "warning") {
            Column(column.padding(horizontal = Gutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    post.contentWarning?.takeIf { it.isNotBlank() } ?: stringResource(R.string.post_sensitive),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                FilledTonalButton(onClick = onShowArticle) { Text(stringResource(R.string.post_show)) }
            }
        }
    } else if (article != null) {
        // Translated, the header and body show in the app's language; the post itself stays as it was.
        val translated = translation as? ArticleTranslation.Translated
        articleItems(
            if (translated != null) post.copy(title = translated.title, summary = translated.summary) else post,
            translated?.document ?: article,
            callbacks.openProfile,
            content.articleAccess,
            onQuotePassage,
        )
        item(key = "article-actions", contentType = "actions") {
            Box(
                column
                    // The buttons' own padding then lines their icons up with the article text.
                    .padding(horizontal = Gutter, vertical = 8.dp),
            ) {
                ActionBar(content.post, callbacks, large = true)
            }
        }
    } else {
        item(key = "focused:" + content.post.post.id, contentType = "focused") {
            FocusedPost(content.post, callbacks, connectAbove = content.ancestors.isNotEmpty(), modifier = column)
        }
    }
    if (post.authoredByMe) {
        item(key = "reach", contentType = "reach") {
            TextButton(
                onClick = { onOpenReach(post.id) },
                modifier = column.padding(horizontal = Gutter - 12.dp),
            ) {
                Icon(AppIcons.TrendingUp, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.reach_see), modifier = Modifier.weight(1f))
            }
        }
    }

    // Each reply with its follow-ups is one block, set off from the post and the next block by a divider.
    content.replies.forEach { group ->
        val rows = listOf(group.reply) + group.followUps
        rows.forEachIndexed { index, item ->
            item(key = "r:" + group.reply.post.id + ":" + item.post.id, contentType = "row") {
                Column(column) {
                    if (index == 0) HorizontalDivider()
                    PostRow(item, callbacks, connectAbove = index > 0, connectBelow = index < rows.lastIndex, showContext = false)
                }
            }
        }
        if (group.hasMore) {
            item(key = "more:" + group.reply.post.id, contentType = "link") {
                TextButton(
                    onClick = { onOpenPost(rows.last().post) },
                    modifier = column.padding(start = PostTextInset - 12.dp).wrapContentWidth(Alignment.Start),
                ) {
                    Text(stringResource(R.string.thread_more_replies))
                }
            }
        }
    }

    if (content.next != null) {
        item(key = "append", contentType = "append") {
            RepliesFooter(content.next, content.appending, content.appendFailed, onLoadMore)
        }
    }
}

/**
 * Earlier posts land above the first ancestor, but the list keeps the view on the button above that one, which
 * pushes the ancestor the reader was looking at down. This keeps it where it was. It runs while the new rows
 * compose, before they are laid out, so the layout info still holds the positions from before.
 */
@Composable
private fun KeepAncestorsInView(listState: LazyListState, content: ThreadContent) {
    val top = content.ancestors.firstOrNull()?.post?.id
    val shownTop = remember { arrayOfNulls<String>(1) }
    SideEffect {
        val before = shownTop[0]
        shownTop[0] = top
        if (before == null || top == null || before == top) return@SideEffect
        val ids = content.ancestors.map { it.post.id }
        val anchor = listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { info ->
            val index = ids.indexOf((info.key as? String)?.removePrefix("a:") ?: return@firstNotNullOfOrNull null)
            if (index < 0) null else index to info.offset
        } ?: return@SideEffect
        // The rows above the ancestors: the button and the link to a parent elsewhere.
        val rowsAbove = (if (content.hasEarlier) 1 else 0) + (if (content.parentLink != null) 1 else 0)
        listState.requestScrollToItem(rowsAbove + anchor.first, -anchor.second)
    }
}

/** Content-warned or sensitive: post rows hide these behind a tap, and so does the reader. */
private val Post.isWarned: Boolean get() = sensitive || !contentWarning.isNullOrBlank()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RepliesFooter(next: String, appending: Boolean, failed: Boolean, onLoadMore: () -> Unit) {
    // Reaching the footer is the cue to load the next page, and again for each new cursor while
    // the footer stays in view. A failure waits for a tap.
    LaunchedEffect(next) { onLoadMore() }
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            failed -> TextButton(onClick = onLoadMore) { Text(stringResource(R.string.load_more_failed)) }
            appending -> LoadingIndicator()
        }
    }
}

@Composable
private fun ReplyBar(post: FeedItem, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        // Same width cap as the posts, so it lines up with them in landscape and on tablets.
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Gutter, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Box(contentAlignment = Alignment.CenterStart) {
                    EmojiText(
                        stringResource(R.string.thread_reply_prompt, post.post.author.displayName),
                        post.post.author.emojiSourceId,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** Opens the original in a browser. Sharing it is the post's own action row's job. */
@Composable
private fun OpenInBrowserAction(webUrl: String?) {
    if (webUrl == null) return
    val uriHandler = browserUriHandler()
    IconButton(onClick = { uriHandler.openUri(webUrl) }) {
        Icon(AppIcons.OpenInNew, contentDescription = stringResource(R.string.open_in_browser))
    }
}
