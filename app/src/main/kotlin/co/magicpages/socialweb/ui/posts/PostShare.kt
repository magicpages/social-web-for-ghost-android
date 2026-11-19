package co.magicpages.socialweb.ui.posts

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Opens the system share sheet for a post: its link (a Ghost note's is its fedi.pub page), or,
 * when it has none, its text with who wrote it.
 */
@Composable
internal fun rememberSharePost(): (FeedItem) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { item ->
            val text = item.post.shareUrl ?: item.shareText()
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, null))
        }
    }
}
