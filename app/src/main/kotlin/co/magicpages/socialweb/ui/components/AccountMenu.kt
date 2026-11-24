package co.magicpages.socialweb.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.account.StoredAccount

/** The name an account goes by in lists and menus. */
val StoredAccount.displayName: String
    get() = profile.name.ifBlank { site.title }

/**
 * The active account's avatar in the top bar of every tab. It opens a sheet listing every
 * signed-in account (tap one to switch to it, with how many unread notifications the others have)
 * and adding a site, then the active account's profile, the settings, and signing the active account out.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccountMenu(
    active: StoredAccount,
    accounts: List<StoredAccount>,
    /** The other accounts' unread notification counts, by account id. */
    otherUnread: Map<String, Int>,
    /** The sheet opened: a moment to ask for [otherUnread] again. */
    onOpen: () -> Unit,
    onSwitch: (accountId: String) -> Unit,
    onAddSite: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.account_menu)
    IconButton(
        onClick = {
            open = true
            onOpen()
        },
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Avatar(active.profile.avatarUrl, active.displayName, 32.dp)
    }
    if (!open) return
    ActionSheet(onDismiss = { open = false }) {
        // Always lists the active account, even in the moment before the full list has loaded.
        for (account in accounts.ifEmpty { listOf(active) }) {
            val isActive = account.id == active.id
            ListItem(
                selected = isActive,
                onClick = { closeThen { if (!isActive) onSwitch(account.id) } },
                leadingContent = { Avatar(account.profile.avatarUrl, account.displayName, 40.dp) },
                supportingContent = {
                    HandleText(
                        account.profile.handle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = if (isActive) {
                    { Icon(AppIcons.Check, contentDescription = null) }
                } else {
                    otherUnread[account.id]?.takeIf { it > 0 }?.let { count -> { UnreadBadge(count) } }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            ) {
                Text(account.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SheetAction(stringResource(R.string.account_add_site), AppIcons.Add, onClick = onAddSite)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SheetAction(stringResource(R.string.your_profile), AppIcons.Person, onClick = onOpenProfile)
        SheetAction(stringResource(R.string.settings_title), AppIcons.Settings, onClick = onOpenSettings)
        SheetAction(stringResource(R.string.sign_out), AppIcons.Logout, onClick = onSignOut)
    }
}

/** How many notifications an account hasn't seen, like the badge on the Notifications tab. */
@Composable
private fun UnreadBadge(count: Int) {
    val description = pluralStringResource(R.plurals.nav_unread, count, count)
    Badge(Modifier.semantics { contentDescription = description }) {
        Text(if (count > 99) "99+" else count.toString())
    }
}
