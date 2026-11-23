package co.magicpages.socialweb.ui.posts

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Hidden
import co.magicpages.socialweb.data.activitypub.Interactions
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.ghost.fediPubUrl
import co.magicpages.socialweb.data.ghost.isGhostActivityPubUrl
import co.magicpages.socialweb.data.linkpreview.isPreviewableUrl
import co.magicpages.socialweb.ui.components.parsePostHtml
import co.magicpages.socialweb.ui.components.splitQuote
import java.time.Instant
import java.time.format.DateTimeParseException

/** A post plus the parts that are too slow to work out on the main thread while scrolling. */
@Immutable
data class FeedItem(
    val post: Post,
    val content: AnnotatedString,
    val publishedAtMillis: Long?,
    /** The post this one quotes; its "RE: <link>" line is left out of [content]. */
    val quoteUrl: String? = null,
    /** Whom the post answers, for lists that show replies away from their thread. */
    val replyingTo: Author? = null,
    /** Everyone who reposted the post into this list, newest first; the first is [Post.repostedBy]. */
    val reposters: List<Author> = emptyList(),
    /** The page the text links to first, for a link card; null when the post has nothing to preview. */
    val previewUrl: String? = null,
) {
    fun with(overrides: Map<String, Interactions>): FeedItem =
        overrides[post.id]?.let { copy(post = it.applyTo(post)) } ?: this

    /** The item with the reposters that [RepostGroups] only found after it was built. */
    fun withLateReposters(late: Map<String, List<Author>>): FeedItem =
        late[post.id]?.let { copy(reposters = it) } ?: this

    /** Whether [hidden] takes this item out: its post was deleted, its author blocked, or everyone who reposted it into the list. */
    fun isHiddenBy(hidden: Hidden): Boolean =
        post.id in hidden.posts || hidden.hides(post.author) || (reposters.isNotEmpty() && reposters.all(hidden::hides))

    /**
     * What to share for a post with no link at all ([Post.shareUrl] is null): its text, its images'
     * links, and who wrote it with their page, so it reads well in a message or an email.
     */
    fun shareText(): String = buildList {
        content.text.trim().takeIf { it.isNotEmpty() }?.let(::add)
        post.images.takeIf { it.isNotEmpty() }?.let { images -> add(images.joinToString("\n") { it.url }) }
        val author = post.author
        val byline = if (author.name.isBlank()) "— ${author.handle}" else "— ${author.name} (${author.handle})"
        // A Ghost note's author is a Ghost account, whose page is on fedi.pub: Ghost has none of its own.
        val profilePage = if (isGhostActivityPubUrl(post.url)) fediPubUrl(author.handle) else author.url
        add(listOfNotNull(byline, profilePage?.takeIf { it.isNotBlank() }).joinToString("\n"))
    }.joinToString("\n\n")
}

/** Call off the main thread. */
fun Post.toFeedItem(): FeedItem {
    val (body, quoteUrl) = splitQuote(content)
    val text = if (body.isBlank()) AnnotatedString("") else parsePostHtml(body)
    return FeedItem(
        post = this,
        content = text,
        publishedAtMillis = publishedAt?.let {
            try {
                Instant.parse(it).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        },
        quoteUrl = quoteUrl,
        reposters = listOfNotNull(repostedBy),
        // Media, a quoted post and an article's own card already say what the post is about.
        previewUrl = if (isArticle || attachments.isNotEmpty() || quoteUrl != null) null else text.firstPreviewLink(this),
    )
}

/**
 * The first link in the text that leads to a page: mentions and hashtags are links too, and a link
 * back to the post itself would only preview what is already on screen.
 */
internal fun AnnotatedString.firstPreviewLink(post: Post): String? {
    for (range in getLinkAnnotations(0, length)) {
        val url = (range.item as? LinkAnnotation.Url)?.url?.trim() ?: continue
        val label = text.substring(range.start, range.end).trimStart()
        if (label.startsWith('@') || label.startsWith('#')) continue
        if (!isPreviewableUrl(url) || url.isSameLink(post.url) || url.isSameLink(post.id)) continue
        return url
    }
    return null
}

internal fun String.isSameLink(other: String) = other.isNotBlank() && trimEnd('/') == other.trim().trimEnd('/')
