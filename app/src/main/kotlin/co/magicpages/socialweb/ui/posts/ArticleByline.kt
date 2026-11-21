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
 * The writers a byline shows: names trimmed and without repeats, or none when there is nothing to add. A lone
 * writer named like the site's account (a one-person site) would only repeat the author line.
 */
internal fun bylineWriters(writers: List<GhostAuthor>, authorName: String): List<GhostAuthor> {
    val named = writers.map { it.copy(name = it.name.trim()) }.filter { it.name.isNotEmpty() }.distinctBy { it.name.lowercase() }
    if (named.size == 1 && named.single().name.equals(authorName.trim(), ignoreCase = true)) return emptyList()
    return named
}

/** The article's writers worth naming next to the site's account ([bylineWriters]). */
@Composable
fun articleWriters(post: Post): List<GhostAuthor> =
    remember(post.metadata, post.author) { bylineWriters(post.ghostAuthors, post.author.displayName) }

/** "Ana, Ben and Cem" in the app's language. */
@Composable
fun writerNames(writers: List<GhostAuthor>): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(writers, locale) { ListFormatter.getInstance(locale).format(writers.map { it.name }) }
}

/** "By Ana and Ben" for an article with named writers; null for anything else. */
@Composable
fun articleByline(post: Post): String? {
    val writers = articleWriters(post)
    if (writers.isEmpty()) return null
    return stringResource(R.string.post_byline, writerNames(writers))
}
