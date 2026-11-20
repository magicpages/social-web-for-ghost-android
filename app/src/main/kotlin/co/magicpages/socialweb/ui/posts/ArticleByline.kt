package co.magicpages.socialweb.ui.posts

import android.icu.text.ListFormatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.GhostAuthor
import co.magicpages.socialweb.data.activitypub.Post

/**
 * The names a byline shows: the writers' names, trimmed and without repeats, or none when there
 * is nothing to add. A lone writer named like the site's account (a one-person site) would only
 * repeat the author line.
 */
internal fun bylineNames(writers: List<GhostAuthor>, authorName: String): List<String> {
    val names = writers.map { it.name.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    if (names.size == 1 && names.single().equals(authorName.trim(), ignoreCase = true)) return emptyList()
    return names
}

/** "By Ana and Ben" for an article of the reader's own site with named writers; null for anything else. */
@Composable
fun articleByline(post: Post): String? {
    val names = remember(post.metadata, post.author) { bylineNames(post.ghostAuthors, post.author.displayName) }
    if (names.isEmpty()) return null
    val locale = LocalConfiguration.current.locales[0]
    val list = remember(names, locale) { ListFormatter.getInstance(locale).format(names) }
    return stringResource(R.string.post_byline, list)
}
