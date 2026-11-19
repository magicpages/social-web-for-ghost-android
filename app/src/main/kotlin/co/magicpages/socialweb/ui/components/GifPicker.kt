package co.magicpages.socialweb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.ImageRejectedException
import co.magicpages.socialweb.data.gif.Gifs
import co.magicpages.socialweb.data.gif.KlipyConfig
import co.magicpages.socialweb.data.gif.KlipyException
import co.magicpages.socialweb.data.gif.KlipyGif
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The composers' GIFs: KLIPY search for the signed-in site. Null outside a signed-in session. */
val LocalGifs = staticCompositionLocalOf<Gifs?> { null }

/**
 * The composer bar's GIF button. It shows only once the signed-in site turns out to have GIFs (a
 * KLIPY key in its Ghost config), as its editor's GIF card does. A picked GIF arrives as a staged
 * image Uri with what it shows, for the alt text; like a picked image, it replaces the attachment.
 */
@Composable
fun AddGifButton(enabled: Boolean, onPick: (uri: String, description: String) -> Unit) {
    val gifs = LocalGifs.current ?: return
    val config by produceState<KlipyConfig?>(null, gifs) { value = gifs.config() }
    val ready = config ?: return
    var choosing by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { choosing = true }, enabled = enabled) {
        Icon(AppIcons.GifBox, contentDescription = stringResource(R.string.gif_add))
    }
    if (choosing) {
        GifPicker(
            gifs = gifs,
            config = ready,
            onDismiss = { choosing = false },
            onPick = { uri, description ->
                choosing = false
                onPick(uri, description)
            },
        )
    }
}

/**
 * Searching KLIPY like Ghost Admin's GIF card: featured GIFs until something is typed, then results
 * once typing pauses. Picking one downloads it in the largest size the site takes, so it stays animated.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GifPicker(gifs: Gifs, config: KlipyConfig, onDismiss: () -> Unit, onPick: (uri: String, description: String) -> Unit) {
    var term by rememberSaveable { mutableStateOf("") }
    val results = remember(config) { GifResults(gifs, config) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(results, term) {
        if (results.loadedTerm != null) delay(SearchPauseMillis)
        results.load(term)
    }
    val close = { if (results.downloading == null) onDismiss() }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogSystemBars()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.gif_picker_title)) },
                    navigationIcon = {
                        IconButton(onClick = close, enabled = results.downloading == null) {
                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.composer_close))
                        }
                    },
                )
            },
            bottomBar = {
                // KLIPY's attribution, as in Ghost Admin.
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(
                        "Powered by KLIPY",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                            .padding(vertical = 8.dp),
                    )
                }
            },
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    placeholder = { Text(stringResource(R.string.gif_search)) },
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    trailingIcon = if (term.isEmpty()) {
                        null
                    } else {
                        {
                            IconButton(onClick = { term = "" }) {
                                Icon(AppIcons.Close, contentDescription = stringResource(R.string.explore_search_clear))
                            }
                        }
                    },
                    singleLine = true,
                    enabled = results.downloading == null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                // A failed pick shows here, in view of the GIF tapped, whatever the scroll position.
                results.pickFailure?.let { error ->
                    Text(
                        gifFailureMessage(error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(results.items, key = { it.id }) { gif ->
                        GifCell(gif, downloading = results.downloading == gif.id, enabled = results.downloading == null) {
                            keyboard?.hide()
                            scope.launch { results.pick(gif)?.let { uri -> onPick(uri, gif.title) } }
                        }
                        if (gif.id == results.items.last().id) LaunchedEffect(gif.id) { results.loadMore() }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        GifStatus(results, onRetry = { scope.launch { results.load(term) } })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GifCell(gif: KlipyGif, downloading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        AsyncImage(
            model = gif.preview.url,
            contentDescription = gif.title.ifEmpty { "GIF" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (downloading) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                LoadingIndicator(color = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GifStatus(results: GifResults, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val failure = results.loadFailure
        when {
            results.loading -> LoadingIndicator()
            failure != null -> {
                Text(
                    gifFailureMessage(failure),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            results.items.isEmpty() && results.loadedTerm != null ->
                Text(stringResource(R.string.gif_none_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun gifFailureMessage(error: Exception): String = when (error) {
    is KlipyException.Refused -> error.klipyMessage
    is KlipyException.Failed -> stringResource(R.string.gif_error_status, error.status)
    is KlipyException.Network -> stringResource(R.string.gif_error_network)
    KlipyException.TooLarge -> stringResource(R.string.gif_error_too_large)
    else -> stringResource(R.string.gif_error_save)
}

/** The picker's search: the GIFs for the latest term, and the one being downloaded. */
@Stable
private class GifResults(private val gifs: Gifs, private val config: KlipyConfig) {
    /** Why the search couldn't be loaded; it can be tried again. */
    var loadFailure by mutableStateOf<Exception?>(null)
        private set

    /** Why the GIF tapped last couldn't be attached; the results stay usable. */
    var pickFailure by mutableStateOf<Exception?>(null)
        private set

    var items by mutableStateOf(emptyList<KlipyGif>())
        private set

    /** The term [items] are for; null until the first answer. */
    var loadedTerm by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    /** The id of the GIF being downloaded. */
    var downloading by mutableStateOf<String?>(null)
        private set

    private var requested: String? = null
    private var next: String? = null
    private var loadingMore = false

    /** The first page for [term] (featured GIFs when it's blank). A later call wins over an earlier one still running. */
    suspend fun load(term: String) {
        requested = term
        loading = true
        loadFailure = null
        pickFailure = null
        try {
            val page = gifs.page(config, term, next = null)
            if (requested != term) return
            items = page.gifs
            next = page.next
        } catch (e: KlipyException) {
            if (requested != term) return
            items = emptyList()
            next = null
            loadFailure = e
        } finally {
            if (requested == term) {
                loadedTerm = term
                loading = false
            }
        }
    }

    /** The next page of the loaded term; a failure just ends the list. */
    suspend fun loadMore() {
        val term = loadedTerm ?: return
        val after = next ?: return
        if (loading || loadingMore || requested != term) return
        loadingMore = true
        try {
            val page = gifs.page(config, term, after)
            if (requested != term || next != after) return
            val known = items.mapTo(HashSet()) { it.id }
            items = items + page.gifs.filter { it.id !in known }
            next = page.next
        } catch (_: KlipyException) {
            next = null
        } finally {
            loadingMore = false
        }
    }

    /** Downloads and stages [gif]; null when that failed, with the reason in [pickFailure]. */
    suspend fun pick(gif: KlipyGif): String? {
        downloading = gif.id
        pickFailure = null
        return try {
            gifs.attach(gif)
        } catch (e: KlipyException) {
            pickFailure = e
            null
        } catch (e: ImageRejectedException) {
            pickFailure = e
            null
        } finally {
            downloading = null
        }
    }
}

/** Ghost's editor waits as long for typing to pause before searching. */
private const val SearchPauseMillis = 450L
