package co.magicpages.socialweb.ui.feed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.feed.FeedFilter
import co.magicpages.socialweb.data.settings.AppSettings
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.posts.MaxContentWidth
import co.magicpages.socialweb.ui.theme.Gutter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class FeedFiltersViewModel @Inject constructor(private val settings: AppSettings) : ViewModel() {
    val filter: StateFlow<FeedFilter> = settings.feedFilter

    fun setHideReposts(hide: Boolean) = settings.setHideReposts(hide)

    fun addWord(word: String): Boolean = settings.addMutedWord(word)

    fun removeWord(word: String) = settings.removeMutedWord(word)
}

/** What Notes and Reader leave out: reposts, and posts with words the user muted. Shown over Settings; [onBack] puts it away. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FeedFiltersScreen(onBack: () -> Unit, viewModel: FeedFiltersViewModel = hiltViewModel()) {
    BackHandler(onBack = onBack)
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var draft by rememberSaveable { mutableStateOf("") }
    val add = {
        if (viewModel.addWord(draft)) draft = ""
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.feed_filters_title)) },
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
            LazyColumn(
                Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.feed_filters_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.feed_filters_hide_reposts)) },
                        supportingContent = { Text(stringResource(R.string.feed_filters_hide_reposts_summary)) },
                        trailingContent = { Switch(checked = filter.hideReposts, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.toggleable(
                            value = filter.hideReposts,
                            role = Role.Switch,
                            onValueChange = viewModel::setHideReposts,
                        ),
                    )
                }
                item {
                    Text(
                        stringResource(R.string.feed_filters_muted_words),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = Gutter, end = Gutter, top = 16.dp),
                    )
                    Text(
                        stringResource(R.string.feed_filters_muted_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it.take(FeedFilter.MaxWordLength) },
                            label = { Text(stringResource(R.string.feed_filters_word_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { add() }),
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { add() }, enabled = draft.isNotBlank()) { Text(stringResource(R.string.feed_filters_add)) }
                    }
                }
                if (filter.mutedWords.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.feed_filters_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
                        )
                    }
                } else {
                    items(filter.mutedWords, key = { it }) { word ->
                        ListItem(
                            headlineContent = { Text(word) },
                            trailingContent = {
                                IconButton(onClick = { viewModel.removeWord(word) }) {
                                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.feed_filters_remove_word, word))
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        )
                    }
                }
            }
        }
    }
}
