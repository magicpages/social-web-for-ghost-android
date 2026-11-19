package co.magicpages.socialweb.ui.posts

import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A post as the timeline shows it once, with everyone known to have reposted it, newest first. */
class RepostGroup(val post: Post, val reposters: List<Author>)

/**
 * Folds the repeats of a post in a timeline into one. The service lists a post once per activity:
 * the post itself and each repost of it, so a post several followed accounts reposted shows up
 * several times, possibly on different pages. Timelines run newest first, so the first time a post
 * turns up is its newest position and it keeps it; later copies only add their reposter.
 *
 * One instance covers one load of a list: pages must be [fold]ed in the order they were fetched,
 * and a refresh starts over with a new instance.
 */
class RepostGroups {
    private val reposters = HashMap<String, MutableList<Author>>()
    private val _late = MutableStateFlow<Map<String, List<Author>>>(emptyMap())

    /**
     * Reposters found after their post had already been handed out by [fold], for posts whose
     * earlier page is long gone. Maps post id to all its reposters.
     */
    val late: StateFlow<Map<String, List<Author>>> = _late

    /** The posts of [page] that aren't repeats of ones seen before, each with its reposters so far. */
    @Synchronized
    fun fold(page: List<Post>): List<RepostGroup> {
        val opened = LinkedHashMap<String, Post>()
        val extended = LinkedHashSet<String>()
        for (post in page) {
            val known = reposters[post.id]
            if (known == null) {
                reposters[post.id] = listOfNotNull(post.repostedBy).toMutableList()
                opened[post.id] = post
                continue
            }
            val reposter = post.repostedBy ?: continue
            if (known.none { it.isSameAccount(reposter) }) {
                known += reposter
                if (post.id !in opened) extended += post.id
            }
        }
        if (extended.isNotEmpty()) {
            _late.update { it + extended.associateWith { id -> reposters.getValue(id).toList() } }
        }
        return opened.map { (id, post) -> RepostGroup(post, reposters.getValue(id).toList()) }
    }
}

private fun Author.isSameAccount(other: Author): Boolean =
    if (handle.isNotBlank() && other.handle.isNotBlank()) handle.equals(other.handle, ignoreCase = true) else id == other.id

/** What the "reposted" line says, before it is turned into text. */
sealed interface RepostSummary {
    /** Named first: the signed-in account if it reposted, otherwise the most recent reposter. */
    val first: Author

    /** Whether [first] is the signed-in account, which the line calls "You". */
    val firstIsYou: Boolean

    /** "[first] reposted" */
    data class One(override val first: Author, override val firstIsYou: Boolean = false) : RepostSummary

    /** "[first] and [second] reposted" */
    data class Two(override val first: Author, val second: Author, override val firstIsYou: Boolean = false) : RepostSummary

    /** "[first] and [others] others reposted": [others] counts the accounts that aren't named. */
    data class Many(override val first: Author, val others: Int, override val firstIsYou: Boolean = false) : RepostSummary
}

/**
 * How to word a line naming [reposters], newest first; null when nobody reposted. The account with
 * [ownHandle], if among them, comes first, as "You".
 */
fun summarizeReposters(reposters: List<Author>, ownHandle: String? = null): RepostSummary? {
    val mine = ownHandle?.takeIf { it.isNotBlank() }?.let { handle ->
        reposters.firstOrNull { it.handle.trimStart('@').equals(handle.trimStart('@'), ignoreCase = true) }
    }
    val named = if (mine == null) reposters else listOf(mine) + reposters.filter { it !== mine }
    val you = mine != null
    return when (named.size) {
        0 -> null
        1 -> RepostSummary.One(named[0], you)
        2 -> RepostSummary.Two(named[0], named[1], you)
        else -> RepostSummary.Many(named[0], named.size - 1, you)
    }
}
