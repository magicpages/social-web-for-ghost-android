package co.magicpages.socialweb.data.reach

import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Notification
import co.magicpages.socialweb.data.activitypub.NotificationPost
import co.magicpages.socialweb.data.activitypub.serverOf
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One person liking or reposting a post, or replying to it ([reply] is then their reply), and when. */
data class Interaction(val author: Author, val atMillis: Long?, val reply: NotificationPost? = null)

/**
 * What the site's notifications say happened to one of its posts. The service keeps no list of who
 * liked or reposted a post, so this is the only record of it; the post's own counts stay the totals.
 */
data class PostActivity(
    val postId: String,
    /** The post as the newest notification about it carried it, for posts not otherwise loaded. */
    val post: NotificationPost?,
    val likes: List<Interaction> = emptyList(),
    val reposts: List<Interaction> = emptyList(),
    val replies: List<Interaction> = emptyList(),
) {
    val all: List<Interaction> get() = reposts + replies + likes

    /** The servers of everyone who took part. */
    val servers: Set<String> get() = all.mapTo(HashSet()) { serverOf(it.author.handle) }

    /**
     * Reposts carry a post to new followers and replies to the replier's, so they count for more
     * than a like, which mostly stays between the two accounts.
     */
    val score: Int get() = reposts.size * 3 + replies.size * 2 + likes.size

    companion object {
        fun empty(postId: String) = PostActivity(postId, post = null)
    }
}

/**
 * Groups [notifications] (newest first) by the site's post they're about: likes and reposts by the
 * post itself, replies by the post they answer. Mentions and follows aren't about a post of ours.
 * Someone liking twice (after an unlike) counts once; their newest notification is kept.
 */
fun activityByPost(notifications: List<Notification>): Map<String, PostActivity> {
    class Builder(val post: NotificationPost) {
        val likes = LinkedHashMap<String, Interaction>()
        val reposts = LinkedHashMap<String, Interaction>()
        val replies = LinkedHashMap<String, Interaction>()
    }
    val builders = LinkedHashMap<String, Builder>()
    for (notification in notifications) {
        val at = timestampMillis(notification.createdAt)
        when (notification.type) {
            Notification.Type.Like, Notification.Type.Repost -> {
                val post = notification.post ?: continue
                val builder = builders.getOrPut(post.id) { Builder(post) }
                val target = if (notification.type == Notification.Type.Like) builder.likes else builder.reposts
                target.putIfAbsent(notification.actor.handle, Interaction(notification.actor, at))
            }
            Notification.Type.Reply -> {
                val parent = notification.inReplyTo ?: continue
                val reply = notification.post ?: continue
                builders.getOrPut(parent.id) { Builder(parent) }
                    .replies.putIfAbsent(reply.id, Interaction(notification.actor, at, reply))
            }
            else -> Unit
        }
    }
    return builders.mapValues { (id, b) ->
        PostActivity(id, b.post, b.likes.values.toList(), b.reposts.values.toList(), b.replies.values.toList())
    }
}

/**
 * How many [times] fall on each local day from [startMillis]'s day, for [days] days. Times before
 * the start (clock drift) count for the first day; ones after the last day are left out.
 */
fun dailyCounts(times: List<Long>, startMillis: Long, days: Int, zone: ZoneId = ZoneId.systemDefault()): IntArray {
    val counts = IntArray(days.coerceAtLeast(0))
    if (counts.isEmpty()) return counts
    val start = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
    for (time in times) {
        val day = ChronoUnit.DAYS.between(start, Instant.ofEpochMilli(time).atZone(zone).toLocalDate()).toInt()
        if (day < counts.size) counts[day.coerceAtLeast(0)]++
    }
    return counts
}

/** The share, in percent, of [times] within a day of [startMillis]; null with nothing to share out. */
fun firstDayPercent(times: List<Long>, startMillis: Long): Int? {
    if (times.isEmpty()) return null
    val early = times.count { it - startMillis < DayMillis }
    return early * 100 / times.size
}

/**
 * The most people a post could have reached: the site's own followers and the followers of each
 * account that reposted it. Followers overlap, so this is an upper bound, never a count of readers.
 */
fun potentialReach(ownFollowers: Int, reposterFollowers: Collection<Int>): Long =
    ownFollowers.toLong() + reposterFollowers.sumOf { it.toLong() }

/** Epoch millis of an ISO-8601 timestamp, or null when it's missing or unreadable. */
fun timestampMillis(value: String?): Long? =
    value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

const val DayMillis = 24L * 60 * 60 * 1000
