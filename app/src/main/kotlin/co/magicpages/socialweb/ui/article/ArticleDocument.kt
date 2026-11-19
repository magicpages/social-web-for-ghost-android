package co.magicpages.socialweb.ui.article

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString

/**
 * A long-form post's body, split into the blocks the reader lays out one by one. Built off the
 * main thread by [ArticleParser] from the HTML Ghost (and other servers) publish, Koenig cards
 * included. Inline text carries bold, italic, code and `LinkAnnotation.Url` links; the reader
 * applies the theme's link colour when it draws them.
 */
@Immutable
data class ArticleDocument(val blocks: List<ArticleBlock>) {
    /** Whether the post stops at a members-only gate: only its public part is here. */
    val isLocked: Boolean get() = blocks.any { it is ArticleBlock.MembersOnly }

    /**
     * The [summary] to show under the title, or null when there is none or the body opens with
     * it anyway: servers fill the summary with the article's first words when the author wrote no
     * excerpt, and showing them twice reads like a glitch.
     */
    fun dek(summary: String?): String? {
        val dek = summary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val opening = blocks.asSequence()
            .mapNotNull { (it as? ArticleBlock.Paragraph)?.text?.text }
            .take(3)
            .joinToString(" ")
        return dek.takeUnless { opening.squashed().startsWith(dek.removeSuffix("…").removeSuffix("...").squashed()) }
    }
}

/** Letters and digits only, so spacing, punctuation and case don't hide a repeat. */
private fun String.squashed(): String = filter(Char::isLetterOrDigit).lowercase()

@Immutable
data class ArticleImage(val url: String, val alt: String?, val width: Int?, val height: Int?) {
    /** Width over height, when the HTML gave both. */
    val aspectRatio: Float? get() = if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else null
}

/** How far an image may reach: Ghost's `kg-width-wide` and `kg-width-full`. */
enum class ImageWidth { Regular, Wide, Full }

@Immutable
sealed interface ArticleBlock {
    data class Paragraph(val text: AnnotatedString) : ArticleBlock

    /** [level] 1 to 6, as in `<h1>` to `<h6>`. */
    data class Heading(val level: Int, val text: AnnotatedString) : ArticleBlock

    /** [alternative] is Ghost's centred pull-quote style (`kg-blockquote-alt`). */
    data class Quote(val text: AnnotatedString, val alternative: Boolean) : ArticleBlock

    /** A list flattened in reading order; nested items carry a greater [ListEntry.depth]. */
    data class BulletList(val entries: List<ListEntry>) : ArticleBlock

    data class Code(val code: String, val language: String?) : ArticleBlock

    data object Divider : ArticleBlock

    data class Image(val image: ArticleImage, val caption: AnnotatedString?, val width: ImageWidth, val link: String?) : ArticleBlock

    /** Ghost's gallery card: rows of images, each row laid out by the images' aspect ratios. */
    data class Gallery(val rows: List<List<ArticleImage>>, val caption: AnnotatedString?) : ArticleBlock

    data class Bookmark(
        val url: String,
        val title: String,
        val description: String?,
        val publisher: String?,
        val author: String?,
        val iconUrl: String?,
        val thumbnailUrl: String?,
    ) : ArticleBlock

    /** [color] is Ghost's callout colour name (`grey`, `blue`, `accent`, …), if any. */
    data class Callout(val emoji: String?, val text: AnnotatedString, val color: String?) : ArticleBlock

    data class Button(val text: String, val url: String) : ArticleBlock

    /** Ghost's toggle card or a `<details>` element: [content] shows when expanded. */
    data class Toggle(val heading: AnnotatedString, val content: List<ArticleBlock>) : ArticleBlock

    data class Audio(val url: String, val title: String?) : ArticleBlock

    data class Video(val url: String, val posterUrl: String?, val caption: AnnotatedString?) : ArticleBlock

    /**
     * Embedded third-party content (an `<iframe>`), which the reader shows as a link to [url].
     * YouTube and Vimeo embeds point at their watch page and carry a [thumbnailUrl] when known.
     */
    data class Embed(val url: String, val provider: String?, val thumbnailUrl: String?, val caption: AnnotatedString?) : ArticleBlock

    data class File(val url: String, val name: String, val caption: String?, val size: String?) : ArticleBlock

    /** Rows of cells; the first [headerRows] rows are headers. */
    data class Table(val rows: List<List<AnnotatedString>>, val headerRows: Int) : ArticleBlock

    /**
     * Where a members-only or paid post ends for readers who aren't signed in to its site: the service's notice, as
     * [title] and [message]. The reader replaces it with offers to sign in or subscribe.
     */
    data class MembersOnly(val title: String?, val message: String?) : ArticleBlock
}

/** What the reader knows about a members-only post's gate: where the site's Portal is, and how far unlocking got. */
@Immutable
data class ArticleAccess(val siteUrl: String?, val status: Status) {
    enum class Status {
        /** No member session for the site, or none that's been tried. */
        SignedOut,

        /** The full post is being fetched with the member session. */
        Loading,

        /** A member session exists, but the post couldn't be unlocked with it. */
        Unavailable,
    }
}

/** [marker] is what precedes the text: "•" for bullets, "3." for the third ordered item. */
@Immutable
data class ListEntry(val text: AnnotatedString, val depth: Int, val marker: String)
