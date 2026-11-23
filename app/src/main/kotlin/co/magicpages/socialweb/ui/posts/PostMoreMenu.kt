package co.magicpages.socialweb.ui.posts

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Hidden
import co.magicpages.socialweb.data.activitypub.Moderation.Outcome
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.serverOf
import co.magicpages.socialweb.ui.article.ReaderViewAction
import co.magicpages.socialweb.ui.components.ActionSheet
import co.magicpages.socialweb.ui.components.ActionSheetScope
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.SheetAction
import co.magicpages.socialweb.ui.moderation.BlockDialog
import co.magicpages.socialweb.ui.moderation.BlockTarget
import co.magicpages.socialweb.ui.moderation.LocalModeration
import co.magicpages.socialweb.ui.moderation.LocalOpenReport
import co.magicpages.socialweb.ui.moderation.ReportKey
import co.magicpages.socialweb.ui.translation.TranslateAction
import kotlinx.coroutines.launch

/** Which confirmation the more menu shows once its sheet has closed. */
private sealed interface PostMenuDialog {
    data object Delete : PostMenuDialog
    data object ShareAgain : PostMenuDialog
    data class Block(val target: BlockTarget) : PostMenuDialog
}

/**
 * The "more" button at the end of a post's actions, with what isn't for every tap: Show reader (an article of a
 * site whose reader view is hidden), Translate, Copy link (or a Ghost note's fediverse link), Copy text for a note, and
 * for the site's own posts Remind me to share again (articles) and Delete, or Report and Block for everyone else's.
 * [shareInMenu] adds Share, for a row too narrow to show its own button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PostMoreMenu(item: FeedItem, height: Dp, shareInMenu: Boolean = false) {
    if (item.post.isDeleted) return
    var sheetOpen by remember { mutableStateOf(false) }
    TextButton(
        onClick = { sheetOpen = true },
        shapes = ButtonDefaults.shapesFor(height),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        contentPadding = ButtonDefaults.contentPaddingFor(height),
        modifier = Modifier.heightIn(min = height).widthIn(min = ActionMinWidth),
    ) {
        Icon(
            AppIcons.MoreVert,
            contentDescription = stringResource(R.string.action_more),
            modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
        )
    }
    PostMenu(item, sheetOpen, onClose = { sheetOpen = false }, shareInMenu = shareInMenu) {
        ReaderViewAction(item.post)
        TranslateAction(item)
    }
}

/**
 * The article screen's "more" button in its top bar: what a post's menu offers, with [translate]
 * (the whole article's, not the note text's) in place of the post's own Translate.
 */
@Composable
fun ArticleMoreMenu(item: FeedItem, translate: @Composable ActionSheetScope.() -> Unit) {
    if (item.post.isDeleted) return
    var sheetOpen by remember { mutableStateOf(false) }
    IconButton(onClick = { sheetOpen = true }) {
        Icon(AppIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
    }
    PostMenu(item, sheetOpen, onClose = { sheetOpen = false }, translate = translate)
}

/** The more menu's sheet while [sheetOpen], and the confirmation one of its actions asks for after it closes. */
@Composable
private fun PostMenu(
    item: FeedItem,
    sheetOpen: Boolean,
    onClose: () -> Unit,
    shareInMenu: Boolean = false,
    translate: @Composable ActionSheetScope.() -> Unit,
) {
    val post = item.post
    var dialog by remember { mutableStateOf<PostMenuDialog?>(null) }
    val moderation = LocalModeration.current
    val openReport = LocalOpenReport.current
    val copyLink = rememberCopyLink()
    val copyText = rememberCopyToClipboard(stringResource(R.string.action_copy_text), stringResource(R.string.text_copied))
    val share = rememberSharePost()
    if (sheetOpen) {
        ActionSheet(onDismiss = onClose) {
            translate()
            val shareUrl = post.shareUrl
            if (shareUrl != null) {
                SheetAction(stringResource(R.string.action_copy_link), AppIcons.ContentCopy) { copyLink(shareUrl) }
            } else if (post.url.isNotBlank()) {
                // A Ghost post with no page anywhere; its ActivityPub ID opens it from any fediverse app's search.
                SheetAction(stringResource(R.string.action_copy_fediverse_link), AppIcons.ContentCopy) { copyLink(post.url) }
            }
            // Only a note's own words: an article's text is the whole article, which has its link and its screen.
            val text = item.content.text.trim()
            if (!post.isArticle && text.isNotEmpty()) {
                SheetAction(stringResource(R.string.action_copy_text), AppIcons.ContentCopy) { copyText(text) }
            }
            if (shareInMenu) {
                SheetAction(stringResource(R.string.share), AppIcons.Share) { share(item) }
            }
            if (post.authoredByMe) {
                if (post.canRemindToShare) {
                    SheetAction(stringResource(R.string.action_remind_share_again), AppIcons.Notifications) { dialog = PostMenuDialog.ShareAgain }
                }
                if (moderation != null) {
                    SheetAction(stringResource(R.string.action_delete_post), AppIcons.Delete) { dialog = PostMenuDialog.Delete }
                }
                return@ActionSheet
            }
            val author = post.author
            if (openReport != null) {
                SheetAction(stringResource(R.string.action_report_post), AppIcons.Flag) {
                    openReport(ReportKey(author.id, author.handle, author.name.ifBlank { author.handle }, post.id, post.url.ifBlank { null }))
                }
            }
            if (moderation != null) {
                SheetAction(stringResource(R.string.action_block_account, author.handle), AppIcons.Block) {
                    dialog = PostMenuDialog.Block(BlockTarget.Account(author.handle))
                }
                val server = serverOf(author.handle)
                SheetAction(stringResource(R.string.action_block_server, server), AppIcons.Block) {
                    dialog = PostMenuDialog.Block(BlockTarget.Server(server))
                }
            }
        }
    }
    val dismiss = { dialog = null }
    when (val shown = dialog) {
        null -> Unit
        PostMenuDialog.Delete -> DeletePostDialog(
            onConfirm = {
                dismiss()
                moderation?.launch({ delete(post) }, Outcome.Deleted, Outcome.DeleteFailed, Hidden(posts = setOf(post.id)))
            },
            onDismiss = dismiss,
        )
        PostMenuDialog.ShareAgain -> ShareAgainSheet(post, onFinished = dismiss)
        is PostMenuDialog.Block -> BlockDialog(
            shown.target,
            onConfirm = {
                dismiss()
                moderation?.block(shown.target, post)
            },
            onDismiss = dismiss,
        )
    }
}

/** Blocks [target], which [post]'s menu offered: its author, or its author's server. */
private fun co.magicpages.socialweb.data.activitypub.Moderation.block(target: BlockTarget, post: Post) = when (target) {
    is BlockTarget.Account -> launch(
        { block(post.author.handle, post.author.id) },
        Outcome.Blocked,
        Outcome.BlockFailed,
        Hidden(accounts = setOf(post.author.handle.lowercase())),
    )
    is BlockTarget.Server -> launch(
        { blockServer(target.host) },
        Outcome.ServerBlocked,
        Outcome.ServerBlockFailed,
        Hidden(servers = setOf(target.host.lowercase())),
    )
}

/** Asks before deleting one of the site's own posts, which can't be undone. */
@Composable
private fun DeletePostDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Delete, contentDescription = null) },
        title = { Text(stringResource(R.string.delete_post_title)) },
        text = { Text(stringResource(R.string.delete_post_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

/**
 * Puts text on the clipboard under [label]. Android 13 and later confirm that themselves; before,
 * a toast saying [confirmation] does, as the platform guidance asks.
 */
@Composable
internal fun rememberCopyToClipboard(label: String, confirmation: String): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, context, scope, label, confirmation) {
        { text ->
            scope.launch {
                clipboard.setClipEntry(ClipData.newPlainText(label, text).toClipEntry())
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** Puts a link on the clipboard. */
@Composable
internal fun rememberCopyLink(): (String) -> Unit =
    rememberCopyToClipboard(stringResource(R.string.action_copy_link), stringResource(R.string.link_copied))
