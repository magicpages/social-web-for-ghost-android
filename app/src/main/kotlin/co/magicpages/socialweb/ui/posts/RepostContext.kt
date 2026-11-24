package co.magicpages.socialweb.ui.posts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.ui.components.ActionSheet
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.Avatar
import co.magicpages.socialweb.ui.components.EmojiText
import co.magicpages.socialweb.ui.components.emojiSourceId
import co.magicpages.socialweb.ui.components.LocalOwnProfile
import co.magicpages.socialweb.ui.components.SheetAction

/**
 * The line above a post naming who reposted it: "Ana reposted", "Ana and Ben reposted", "Ana and
 * 3 others reposted", with the signed-in account first as "You". One reposter's name opens their
 * profile; several open a sheet to pick one. [color] is for the line over a Reader card's cover.
 */
@Composable
fun RepostContext(reposters: List<Author>, openProfile: (Author) -> Unit, color: Color = MaterialTheme.colorScheme.secondary) {
    val summary = summarizeReposters(reposters, LocalOwnProfile.current?.handle) ?: return
    val first = summary.first
    val text = when (summary) {
        is RepostSummary.One ->
            if (summary.firstIsYou) stringResource(R.string.post_reposted_by_you)
            else stringResource(R.string.post_reposted_by, first.displayName)
        is RepostSummary.Two ->
            if (summary.firstIsYou) stringResource(R.string.post_reposted_by_you_and, summary.second.displayName)
            else stringResource(R.string.post_reposted_by_two, first.displayName, summary.second.displayName)
        is RepostSummary.Many ->
            if (summary.firstIsYou) pluralStringResource(R.plurals.post_reposted_by_you_and_many, summary.others, summary.others)
            else pluralStringResource(R.plurals.post_reposted_by_many, summary.others, first.displayName, summary.others)
    }
    var sheetOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(
                onClickLabel = if (summary is RepostSummary.One) null else stringResource(R.string.post_reposters_show),
                role = Role.Button,
            ) { if (summary is RepostSummary.One) openProfile(first) else sheetOpen = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            AppIcons.Repost,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color,
        )
        EmojiText(
            text,
            emojiSourceId = first.emojiSourceId,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (sheetOpen) {
        ActionSheet(onDismiss = { sheetOpen = false }) {
            for (reposter in reposters) {
                SheetAction(
                    reposter.displayName,
                    leadingContent = { Avatar(reposter.avatarUrl, reposter.displayName, 40.dp) },
                ) { openProfile(reposter) }
            }
        }
    }
}
