package co.magicpages.socialweb.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.ui.components.AppIcons

/** A group's heading in a settings screen. */
@Composable
internal fun SectionTitle(@StringRes title: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/** Quiet text that explains a group, above or below it. */
@Composable
internal fun Explainer(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/**
 * A row in a segmented group that opens another screen: [title], and under it [status], what is set
 * there right now. [index] of [count] gives the group its shape.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LinkRow(
    title: String,
    status: String,
    onClick: () -> Unit,
    index: Int,
    count: Int,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    statusColor: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = icon?.let { { Icon(it, contentDescription = null) } },
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier,
        leadingContent = leading,
        supportingContent = { Text(status, color = statusColor) },
    ) {
        Text(title)
    }
}

/**
 * A row in a segmented group that does something right away, like adding a site. [color] tints it
 * when it's destructive. [index] of [count] gives the group its shape.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    index: Int,
    count: Int,
    color: Color = Color.Unspecified,
    iconSize: Dp = 24.dp,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        // A box as wide as the leading content of its neighbours, so the titles line up.
        leadingContent = {
            Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = color.takeOrElse { LocalContentColor.current })
            }
        },
    ) {
        Text(title, color = color)
    }
}

/** A switch in a segmented group; the whole row toggles. [index] of [count] gives the group its shape. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    count: Int,
    summary: String? = null,
    icon: ImageVector? = null,
) {
    SegmentedListItem(
        onClick = { onCheckedChange(!checked) },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        // Reads as one switch to screen readers, not a button plus a separate switch.
        modifier = Modifier.semantics(mergeDescendants = true) {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = if (checked) {
                    { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                },
            )
        },
    ) {
        Text(title)
    }
}

/** Rows of one segmented group, with the small gaps between them. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SegmentedGroup(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) { content() }
}
