package co.magicpages.socialweb.data.feed

import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.PostsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * What the user doesn't want in the Notes and Reader feeds. The service has no filter of its own, so
 * posts are dropped here after they load. [mutedWords] match whole words, ignoring case, in a post's
 * text, title and summary; hashtags are part of the text, so `#cats` and `cats` both catch "#cats".
 * Replies never reach these feeds (the service leaves them out of every timeline), so there is no
 * switch for them.
 */
data class FeedFilter(
    val hideReposts: Boolean = false,
    val mutedWords: List<String> = emptyList(),
) {
    private val patterns: List<Regex> = mutedWords.mapNotNull(::mutedWordPattern)

    /** Whether anything is filtered at all. */
    val isActive: Boolean get() = hideReposts || patterns.isNotEmpty()

    /** Whether [post] is to be left out. Parses the post's HTML when words are muted: call off the main thread. */
    fun hides(post: Post): Boolean {
        if (hideReposts && post.repostedBy != null) return true
        if (patterns.isEmpty()) return false
        val text = searchableText(post)
        return patterns.any { it.containsMatchIn(text) }
    }

    companion object {
        /** The longest word or phrase that can be muted. */
        const val MaxWordLength = 80

        /** A word as it is kept: trimmed, one line, not longer than [MaxWordLength]; null when nothing is left. */
        fun normalizeWord(input: String): String? =
            input.trim().replace(Regex("\\s+"), " ").take(MaxWordLength).trim().takeIf { it.isNotEmpty() }
    }
}

/** The text a muted word is looked for in: title, summary, content warning and the plain text of the content. */
internal fun searchableText(post: Post): String = buildString {
    append(post.title).append('\n')
    post.summary?.let { append(it).append('\n') }
    post.contentWarning?.let { append(it).append('\n') }
    if (post.content.isNotBlank()) append(htmlToPlainText(post.content))
}

/** The text of an HTML fragment; jsoup puts a space where blocks and line breaks were, so neighbouring words don't fuse. */
internal fun htmlToPlainText(html: String): String = Jsoup.parseBodyFragment(html).text()

/**
 * A pattern for one muted word: case-insensitive, and only where the word isn't part of a longer one.
 * An edge that isn't a letter or digit (the `#` of a hashtag) needs no boundary, and neither does one in
 * a script that is written without spaces, where a "word" can't be told apart from its neighbours.
 */
internal fun mutedWordPattern(word: String): Regex? {
    val clean = FeedFilter.normalizeWord(word) ?: return null
    val first = clean.codePointAt(0)
    val last = clean.codePointBefore(clean.length)
    val before = if (needsBoundary(first)) "(?<![\\p{L}\\p{N}_])" else ""
    val after = if (needsBoundary(last)) "(?![\\p{L}\\p{N}_])" else ""
    return Regex(before + Regex.escape(clean) + after, setOf(RegexOption.IGNORE_CASE))
}

private fun needsBoundary(codePoint: Int): Boolean {
    if (!Character.isLetterOrDigit(codePoint) && codePoint != '_'.code) return false
    return when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
        -> false
        else -> true
    }
}

/**
 * One page of a feed with [filter] applied. A page whose posts are all filtered out would leave the
 * list with nothing on screen to scroll and so nothing to ask for the next page, so this keeps
 * loading until a page keeps something, the feed ends, or [maxRequests] pages have been read.
 */
suspend fun loadFiltered(
    filter: FeedFilter,
    next: String?,
    maxRequests: Int = MaxFilteredRequests,
    load: suspend (next: String?) -> PostsPage,
): PostsPage {
    if (!filter.isActive) return load(next)
    var cursor = next
    var requests = 0
    while (true) {
        val page = load(cursor)
        requests++
        val kept = withContext(Dispatchers.Default) { page.posts.filterNot(filter::hides) }
        if (kept.isNotEmpty() || page.next == null || requests >= maxRequests) return PostsPage(kept, page.next)
        cursor = page.next
    }
}

private const val MaxFilteredRequests = 25
