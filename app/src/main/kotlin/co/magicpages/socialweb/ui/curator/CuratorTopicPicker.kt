package co.magicpages.socialweb.ui.curator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Topic
import co.magicpages.socialweb.data.curator.CuratorMaxTopics
import co.magicpages.socialweb.ui.explore.TopicChip

/** [selected] with [slug] taken off, or put on while there is room: a writer has at most [CuratorMaxTopics] topics. */
internal fun toggledTopics(selected: Set<String>, slug: String): Set<String> = when {
    slug in selected -> selected - slug
    selected.size < CuratorMaxTopics -> selected + slug
    else -> selected
}

/** [topics]' chips, filled for the [selected] ones; [onChange] gets the selection a tap leads to. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CuratorTopicChips(
    topics: List<Topic>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (topic in topics) {
            TopicChip(topic.name, selected = topic.slug in selected, onClick = { onChange(toggledTopics(selected, topic.slug)) })
        }
    }
}

/**
 * The choice of the topics a site's writer writes about, as a dialog: from Site settings, where the card isn't.
 * [topics] are what the service offers and [selected] the slugs chosen now; [onSave] gets the chosen topics in the
 * service's order.
 */
@Composable
internal fun CuratorTopicsDialog(
    topics: List<Topic>,
    selected: Set<String>,
    onSave: (List<Topic>) -> Unit,
    onDismiss: () -> Unit,
) {
    // A topic the service no longer has can't stay chosen.
    var picked by rememberSaveable(selected) { mutableStateOf<Set<String>>(selected.filterTo(linkedSetOf()) { slug -> topics.any { it.slug == slug } }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.curator_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.curator_choose_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CuratorTopicChips(topics, picked, onChange = { picked = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(topics.filter { it.slug in picked }) }, enabled = picked.isNotEmpty()) {
                Text(stringResource(R.string.curator_topics_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}
