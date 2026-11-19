package co.magicpages.socialweb.ui.notifications

import androidx.compose.runtime.Immutable
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Notification

/**
 * One row of the notifications list: everything [actors] did of one kind, shown as a single line
 * like "Ana, Ben and 4 others liked your post". A row for a single notification is a group of one.
 *
 * [newest] supplies what the row shows besides the people: its time, post excerpt and id. The id is
 * the row's list key, so it stays stable and unique however the groups are cut.
 */
@Immutable
data class NotificationGroup(
    val newest: NotificationItem,
    /** Newest first, each account once. */
    val actors: List<Author>,
) {
    val id: String get() = newest.id
    val type: Notification.Type get() = newest.type
}

/**
 * This group without the people [hides] covers; a group nobody is left in has an
 * empty [NotificationGroup.actors] and is for the caller to drop. Hiding after grouping keeps the
 * list in place when an account or server is blocked while it is on screen.
 */
fun NotificationGroup.without(hides: (Author) -> Boolean): NotificationGroup {
    val kept = actors.filterNot(hides)
    return if (kept.size == actors.size) this else copy(actors = kept)
}

/**
 * Folds one page of notifications (newest first) into rows the way Mastodon does:
 *
 * - Likes, and reposts, of the same post become one row each, wherever on the page they sit; the
 *   row takes the place of its newest notification.
 * - Follows that are next to each other in the list become one row. "Next to each other" means no
 *   other row sits between them, so a follower who arrives among likes starts a new row; this keeps
 *   the list in time order and avoids a rule about days and time zones.
 * - Replies and mentions are never grouped, since each is a message of its own.
 *
 * Every notification ends up in exactly one row, and rows keep the order of their newest member.
 * Grouping works on the page the service sent, so a group whose members straddle a page boundary
 * is shown as two rows: merging them would mean rewriting a page the list already showed.
 */
fun groupNotifications(items: List<NotificationItem>): List<NotificationGroup> {
    val rows = ArrayList<Builder>()
    val byPost = HashMap<Pair<Notification.Type, String>, Builder>()
    var followRun: Builder? = null
    for (item in items) {
        val postId = item.postId
        when {
            item.type == Notification.Type.Follow -> {
                val run = followRun ?: Builder(item).also { rows += it; followRun = it }
                run.add(item)
            }
            (item.type == Notification.Type.Like || item.type == Notification.Type.Repost) && postId != null -> {
                val key = item.type to postId
                val existing = byPost[key]
                if (existing != null) {
                    // Folded into an earlier row, so nothing new sits between two follows.
                    existing.add(item)
                } else {
                    rows += Builder(item).also { byPost[key] = it; it.add(item) }
                    followRun = null
                }
            }
            else -> {
                rows += Builder(item).also { it.add(item) }
                followRun = null
            }
        }
    }
    return rows.map { it.build() }
}

private class Builder(private val newest: NotificationItem) {
    private val actors = LinkedHashMap<String, Author>()

    fun add(item: NotificationItem) {
        actors.putIfAbsent(item.actor.id, item.actor)
    }

    fun build() = NotificationGroup(newest, actors.values.toList())
}
