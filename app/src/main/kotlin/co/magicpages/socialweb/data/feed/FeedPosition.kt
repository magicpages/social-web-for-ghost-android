package co.magicpages.socialweb.data.feed

/**
 * Where the user was in a feed: the first post on screen with how far it was scrolled ([offset] in
 * pixels, how much of it is above the top edge), and [topId], the newest post they have seen, which
 * tells posts that arrived since from posts they already know.
 */
data class SavedFeedPosition(val anchorId: String, val offset: Int, val topId: String) {
    /** The user was looking at the top of the feed, so a newer feed should open at its own top. */
    val wasAtTop: Boolean get() = anchorId == topId

    fun encode(): String = "$offset\t$anchorId\t$topId"

    companion object {
        fun decode(stored: String?): SavedFeedPosition? {
            val parts = stored?.split('\t', limit = 3) ?: return null
            if (parts.size != 3 || parts[1].isEmpty() || parts[2].isEmpty()) return null
            val offset = parts[0].toIntOrNull() ?: return null
            return SavedFeedPosition(anchorId = parts[1], offset = offset, topId = parts[2])
        }
    }
}

/**
 * The newest post the user is known to have seen, given the loaded post ids (newest first), the
 * previous such post and the index of the first post on screen. Reaching the top of the list means
 * seeing all of it; a previous post that isn't in the list any more (deleted, blocked, or buried
 * under more new posts than were loaded) can't be told from the rest, so the list's first stands in.
 */
fun seenTopId(ids: List<String>, previous: String?, firstVisibleIndex: Int): String? {
    if (ids.isEmpty()) return previous
    if (firstVisibleIndex <= 0 || previous == null || previous !in ids) return ids.first()
    return previous
}

/** How many posts newer than [seenTop] sit above the first visible one: those the user can scroll up to. */
fun newPostsAbove(ids: List<String>, seenTop: String?, firstVisibleIndex: Int): Int {
    val seenIndex = seenTop?.let(ids::indexOf) ?: return 0
    if (seenIndex <= 0) return 0
    return minOf(seenIndex, firstVisibleIndex.coerceAtLeast(0))
}
