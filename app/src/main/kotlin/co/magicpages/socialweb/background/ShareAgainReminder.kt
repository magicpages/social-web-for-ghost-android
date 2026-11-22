package co.magicpages.socialweb.background

import android.content.Intent
import androidx.work.Data
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.ui.quote.ellipsize
import java.time.ZonedDateTime

/**
 * How long after setting it a "share again" reminder goes off. Each fires at 9:00 in the user's own
 * time zone rather than at the hour it was set: a reminder set at midnight shouldn't ring at midnight,
 * and a morning post reaches the followers who check their timeline over breakfast.
 */
enum class ShareAgainDelay(val days: Long) {
    Tomorrow(1),
    InThreeDays(3),
    InAWeek(7),
    ;

    /** The moment this reminder goes off when it is set at [now]. */
    fun triggerAt(now: ZonedDateTime): ZonedDateTime =
        now.toLocalDate().plusDays(days).atTime(ReminderHour, 0).atZone(now.zone)

    private companion object {
        const val ReminderHour = 9
    }
}

/**
 * What a reminder to share an article again needs once it fires: enough of the article to rebuild its quote card
 * and link without asking the service, so it works offline and long after the article left the feed.
 * It travels in the work request's input and in the notification's intent, under the same keys.
 */
data class ShareAgainReminder(
    /** The account the article is the own post of; the app switches to it before opening the note. */
    val accountId: String,
    val postId: String,
    val url: String,
    val title: String,
    /** The article's summary, cut short: the work request's input is limited to 10 KB. */
    val excerpt: String,
    val authorId: String,
    val authorName: String,
    val authorHandle: String,
    val authorAvatarUrl: String?,
    val imageUrl: String?,
    val publishedAt: String?,
    val sensitive: Boolean,
    val warning: String?,
) {
    /** The post as the quote card needs it. */
    fun toPost(): Post = Post(
        id = postId,
        type = Post.TYPE_ARTICLE,
        title = title,
        excerpt = excerpt,
        sensitive = sensitive,
        contentWarning = warning,
        url = url,
        featureImageUrl = imageUrl,
        publishedAt = publishedAt,
        author = Author(id = authorId, handle = authorHandle, name = authorName, avatarUrl = authorAvatarUrl),
        authoredByMe = true,
    )

    internal fun values(): Map<String, String> = buildMap {
        put(KeyAccount, accountId)
        put(KeyPost, postId)
        put(KeyUrl, url)
        put(KeyTitle, title)
        put(KeyExcerpt, excerpt)
        put(KeyAuthorId, authorId)
        put(KeyAuthorName, authorName)
        put(KeyAuthorHandle, authorHandle)
        authorAvatarUrl?.let { put(KeyAvatar, it) }
        imageUrl?.let { put(KeyImage, it) }
        publishedAt?.let { put(KeyPublished, it) }
        if (sensitive) put(KeySensitive, "1")
        warning?.let { put(KeyWarning, it) }
    }

    /** The work request's input. */
    fun toData(): Data = Data.Builder().also { builder -> values().forEach { (key, value) -> builder.putString(key, value) } }.build()

    /** Puts the reminder on [intent], the notification's tap that opens the app. */
    fun putInto(intent: Intent): Intent = intent.also { values().forEach { (key, value) -> it.putExtra(key, value) } }

    companion object {
        private const val Prefix = "co.magicpages.socialweb.share_again."
        private const val KeyAccount = Prefix + "account"
        private const val KeyPost = Prefix + "post"
        private const val KeyUrl = Prefix + "url"
        private const val KeyTitle = Prefix + "title"
        private const val KeyExcerpt = Prefix + "excerpt"
        private const val KeyAuthorId = Prefix + "author_id"
        private const val KeyAuthorName = Prefix + "author_name"
        private const val KeyAuthorHandle = Prefix + "author_handle"
        private const val KeyAvatar = Prefix + "avatar"
        private const val KeyImage = Prefix + "image"
        private const val KeyPublished = Prefix + "published"
        private const val KeySensitive = Prefix + "sensitive"
        private const val KeyWarning = Prefix + "warning"
        private const val MaxExcerpt = 300

        /** The reminder for [post], one of [accountId]'s own articles; null when it has no web page to link to. */
        fun of(accountId: String, post: Post): ShareAgainReminder? {
            val url = post.webUrl ?: return null
            val summary = post.summary?.takeIf { it.isNotBlank() } ?: post.excerpt
            return ShareAgainReminder(
                accountId = accountId,
                postId = post.id,
                url = url,
                title = post.title.trim(),
                excerpt = ellipsize(summary.trim(), MaxExcerpt),
                authorId = post.author.id,
                authorName = post.author.name,
                authorHandle = post.author.handle,
                authorAvatarUrl = post.author.avatarUrl?.takeIf { it.isNotBlank() },
                imageUrl = post.featureImageUrl?.takeIf { it.isNotBlank() },
                publishedAt = post.publishedAt,
                sensitive = post.sensitive,
                warning = post.contentWarning?.takeIf { it.isNotBlank() },
            )
        }

        /** The reminder a work request carries; null for input that isn't one. */
        fun fromData(data: Data): ShareAgainReminder? = from(data::getString)

        /** The reminder a notification's tap carries; null for any other launch of the app. */
        fun fromIntent(intent: Intent?): ShareAgainReminder? = intent?.let { from(it::getStringExtra) }

        internal fun from(get: (String) -> String?): ShareAgainReminder? {
            val accountId = get(KeyAccount)?.takeIf { it.isNotEmpty() } ?: return null
            val postId = get(KeyPost)?.takeIf { it.isNotEmpty() } ?: return null
            val url = get(KeyUrl)?.takeIf { it.isNotEmpty() } ?: return null
            return ShareAgainReminder(
                accountId = accountId,
                postId = postId,
                url = url,
                title = get(KeyTitle).orEmpty(),
                excerpt = get(KeyExcerpt).orEmpty(),
                authorId = get(KeyAuthorId).orEmpty(),
                authorName = get(KeyAuthorName).orEmpty(),
                authorHandle = get(KeyAuthorHandle).orEmpty(),
                authorAvatarUrl = get(KeyAvatar),
                imageUrl = get(KeyImage),
                publishedAt = get(KeyPublished),
                sensitive = get(KeySensitive) == "1",
                warning = get(KeyWarning),
            )
        }
    }
}
