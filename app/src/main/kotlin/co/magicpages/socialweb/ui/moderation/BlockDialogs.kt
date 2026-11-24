package co.magicpages.socialweb.ui.moderation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.components.AppIcons

/** Who or what a block is about: one account by its `@user@host` [Account.handle], or a whole server. */
sealed interface BlockTarget {
    data class Account(val handle: String) : BlockTarget
    data class Server(val host: String) : BlockTarget
}

/**
 * Asks before blocking (or, with [unblock], unblocking) [target]: the service also ends follows
 * both ways, which unblocking doesn't bring back, so the dialog says so.
 */
@Composable
fun BlockDialog(target: BlockTarget, unblock: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, text, confirm) = when (target) {
        is BlockTarget.Account -> if (unblock) {
            Triple(R.string.unblock_account_title, R.string.unblock_account_text, R.string.action_unblock)
        } else {
            Triple(R.string.block_account_title, R.string.block_account_text, R.string.action_block)
        }
        is BlockTarget.Server -> if (unblock) {
            Triple(R.string.unblock_server_title, R.string.unblock_server_text, R.string.action_unblock)
        } else {
            Triple(R.string.block_server_title, R.string.block_server_text, R.string.action_block)
        }
    }
    val name = when (target) {
        is BlockTarget.Account -> target.handle
        is BlockTarget.Server -> target.host
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Block, contentDescription = null) },
        title = { Text(stringResource(title, name)) },
        text = { Text(stringResource(text)) },
        confirmButton = {
            // Unblocking undoes harm; blocking is the one that cuts ties, so only it is marked.
            TextButton(
                onClick = onConfirm,
                colors = if (unblock) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}
