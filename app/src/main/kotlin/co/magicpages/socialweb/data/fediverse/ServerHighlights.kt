package co.magicpages.socialweb.data.fediverse

import co.magicpages.socialweb.data.activitypub.Post

/**
 * What a server's own members posted lately, best first: their posts, the hashtags they used and the
 * links they shared. A server's trends mix in whatever is popular across the fediverse, so on a topic
 * server they mostly show posts from the big general servers; its members' posts show what it's about.
 */
data class ServerHighlights(val posts: List<Post>, val tags: List<TrendingTag>, val links: List<TrendingLink>)

/**
 * Ranks the members' [statuses] from a server's local timeline. Bots and replies are left out: bots post
 * feeds and weather, and a reply makes little sense on its own. A repost reaches the most people, so it
 * counts most, as in Reach. No one gets more than [MaxPostsPerPerson] posts in, so one busy account
 * can't fill the list, and a hashtag counts each person once.
 */
internal fun highlightsOf(statuses: List<MastodonStatus>, server: String): ServerHighlights {
    val own = statuses.filter { it.isOwnPost }.distinctBy { it.id }
    val ranked = own.sortedWith(compareByDescending<MastodonStatus> { it.score }.thenByDescending { it.createdAt.orEmpty() })
    val perPerson = HashMap<String, Int>()
    val posts = ranked
        .filter { status -> perPerson.merge(status.account.acct, 1, Int::plus)!! <= MaxPostsPerPerson }
        .take(MaxPosts)
        .map { it.toPost(server) }
    return ServerHighlights(posts, tagsOf(own), linksOf(own))
}

/** Hashtags at least two people used, by how many did; the name keeps the case it was first seen in. */
private fun tagsOf(statuses: List<MastodonStatus>): List<TrendingTag> {
    val people = LinkedHashMap<String, MutableSet<String>>()
    val names = HashMap<String, String>()
    for (status in statuses) {
        for (tag in status.tags) {
            val key = tag.name.lowercase()
            if (key.isEmpty()) continue
            names.putIfAbsent(key, tag.name)
            people.getOrPut(key) { HashSet() } += status.account.acct
        }
    }
    return people.entries
        .filter { it.value.size >= MinTagPeople }
        .sortedByDescending { it.value.size }
        .take(MaxTags)
        .map { TrendingTag(names.getValue(it.key), it.value.size) }
}

/**
 * Articles in the members' posts that got noticed, by how much the posts sharing them were. A link without
 * a preview image is rarely an article, and one nobody reacted to is often its poster's petition, stream
 * or event page; both are left out.
 */
private fun linksOf(statuses: List<MastodonStatus>): List<TrendingLink> {
    class Shared(val card: MastodonCard, val people: MutableSet<String> = HashSet(), var score: Int = 0)
    val byUrl = LinkedHashMap<String, Shared>()
    for (status in statuses) {
        val card = status.card?.takeIf { it.url.startsWith("https://") && it.title.isNotBlank() && it.image?.startsWith("https://") == true } ?: continue
        val shared = byUrl.getOrPut(card.url) { Shared(card) }
        shared.people += status.account.acct
        shared.score += status.score
    }
    return byUrl.values
        .filter { it.score >= MinLinkScore }
        .sortedByDescending { it.score }
        .take(MaxLinks)
        .map { shared ->
            TrendingLink(
                url = shared.card.url,
                title = shared.card.title.trim(),
                provider = shared.card.providerName?.trim()?.takeIf { it.isNotEmpty() },
                imageUrl = shared.card.image,
                people = shared.people.size,
            )
        }
}

/** A post by a person on the server that starts a conversation, rather than a bot's or a reply. */
internal val MastodonStatus.isOwnPost: Boolean get() = !account.bot && inReplyToId == null

private val MastodonStatus.score: Int get() = reblogs * 3 + replies * 2 + favourites

private const val MaxPosts = 40
private const val MaxPostsPerPerson = 2
private const val MaxTags = 20
private const val MinTagPeople = 2
private const val MaxLinks = 10
private const val MinLinkScore = 10
