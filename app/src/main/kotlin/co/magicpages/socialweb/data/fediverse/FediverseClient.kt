package co.magicpages.socialweb.data.fediverse

import co.magicpages.socialweb.data.activitypub.Attachment
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.PostsPage
import co.magicpages.socialweb.data.ghost.GhostException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.isSuccess
import io.ktor.http.path
import java.io.IOException
import java.net.IDN
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The experimental Explore source: whether it's on, and the server whose trends it shows, as a bare host. */
data class FediverseExplore(val enabled: Boolean, val server: String) {
    companion object {
        const val DefaultServer = "mastodon.social"
    }
}

/** A hashtag a server sees trending; [people] is how many accounts used it today and yesterday. */
data class TrendingTag(val name: String, val people: Int)

/** A web page a server sees shared a lot: [people] is how many accounts shared it today and yesterday. */
data class TrendingLink(val url: String, val title: String, val provider: String?, val imageUrl: String?, val people: Int)

/**
 * What else a server shows besides posts. Either list is empty when it has none or won't say.
 * [fromMembers] says they come from the server's own members' posts rather than its trends.
 */
data class FediverseTrends(val tags: List<TrendingTag>, val links: List<TrendingLink>, val fromMembers: Boolean)

/** A public server refused a request (or isn't one); 401, 403 and 422 mean it keeps that list to signed-in users. */
class PublicServerException(val server: String, val status: Int) : Exception("HTTP $status from $server") {
    val notPublic: Boolean get() = status == 401 || status == 403 || status == 422
}

/**
 * Reads what a Mastodon-compatible server shares without signing in: its own members' recent posts, its
 * trending posts and hashtags, and the posts under a hashtag. The posts come back as the app's [Post]s with
 * their ActivityPub ids, so opening, liking, reposting and replying go through the user's own site, which
 * fetches them.
 */
@Singleton
class FediverseClient @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
    private val explored: ExploredPosts,
) {
    private val highlightCache = ConcurrentHashMap<String, CachedHighlights>()
    private val highlightLocks = ConcurrentHashMap<String, Mutex>()
    private val showsMembers = ConcurrentHashMap<String, Boolean>()

    /**
     * What [server]'s own members posted lately, ranked; null when it keeps those posts to signed-in users,
     * as mastodon.social does, and Explore shows its trends instead. The posts and the hashtags above them
     * come from one read, kept for a few minutes so both lists agree; [forgetHighlights] starts over.
     */
    suspend fun highlights(server: String): ServerHighlights? =
        highlightLocks.computeIfAbsent(server) { Mutex() }.withLock {
            highlightCache[server]?.takeIf { System.currentTimeMillis() - it.atMillis < HighlightsCacheMillis }?.let { return@withLock it.highlights }
            val highlights = try {
                highlightsOf(memberStatuses(server), server).also { explored.remember(it.posts) }
            } catch (e: PublicServerException) {
                if (!e.notPublic) throw e
                null
            }
            highlightCache[server] = CachedHighlights(highlights, System.currentTimeMillis())
            showsMembers[server] = highlights != null
            highlights
        }

    /** Whether Explore shows [server]'s members' posts rather than its trends; known after the first [highlights]. */
    suspend fun showsMembers(server: String): Boolean = showsMembers[server] ?: (highlights(server) != null)

    /** Makes the next [highlights] read the server again, as a pull to refresh should. */
    fun forgetHighlights(server: String) {
        highlightCache.remove(server)
    }

    /**
     * The local timeline back to about a day ago: enough pages for a quiet server to have something to rank,
     * and no more than [MembersMaxPages] on a busy one, where a day is thousands of posts.
     */
    private suspend fun memberStatuses(server: String): List<MastodonStatus> {
        val since = System.currentTimeMillis() - MembersWindowMillis
        val statuses = ArrayList<MastodonStatus>()
        var maxId: String? = null
        var usable = 0
        repeat(MembersMaxPages) {
            val query = listOfNotNull("local" to "true", "limit" to "$MembersPageSize", maxId?.let { "max_id" to it })
            val page = get<List<MastodonStatus>>(server, listOf("timelines", "public"), *query.toTypedArray())
            statuses += page
            usable += page.count { it.isOwnPost }
            val oldest = page.lastOrNull()?.createdMillis
            if (page.size < MembersPageSize || (usable >= MembersEnough && oldest != null && oldest < since)) return statuses
            maxId = page.last().id
        }
        return statuses
    }

    /** A page of the server's trending posts, from [offset]; the next page's offset is in [PostsPage.next]. */
    suspend fun trendingPosts(server: String, offset: Int): PostsPage {
        val statuses = get<List<MastodonStatus>>(server, listOf("trends", "statuses"), "limit" to "$PageSize", "offset" to "$offset")
        val posts = statuses.map { it.toPost(server) }.also(explored::remember)
        return PostsPage(posts, next = if (statuses.size < PageSize) null else "${offset + statuses.size}")
    }

    suspend fun trendingTags(server: String): List<TrendingTag> =
        get<List<MastodonTag>>(server, listOf("trends", "tags"), "limit" to "$TagLimit").map { tag ->
            TrendingTag(tag.name, tag.history.take(2).sumOf { it.accounts.toIntOrNull() ?: 0 })
        }

    suspend fun trendingLinks(server: String): List<TrendingLink> =
        get<List<MastodonLink>>(server, listOf("trends", "links"), "limit" to "$LinkLimit").mapNotNull { link ->
            val title = link.title.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            TrendingLink(
                url = link.url,
                title = title,
                provider = link.providerName?.trim()?.takeIf { it.isNotEmpty() },
                imageUrl = link.image?.takeIf { it.startsWith("https://") },
                people = link.history.take(2).sumOf { it.accounts.toIntOrNull() ?: 0 },
            )
        }

    /**
     * The newest public posts under #[tag], older than [maxId] when it's given; the next page's id is in
     * [PostsPage.next]. With [membersOnly], only the server's own members' posts, as its highlights are.
     */
    suspend fun tagPosts(server: String, tag: String, maxId: String?, membersOnly: Boolean): PostsPage {
        val query = listOfNotNull("limit" to "$PageSize", maxId?.let { "max_id" to it }, ("local" to "true").takeIf { membersOnly })
        val statuses = get<List<MastodonStatus>>(server, listOf("timelines", "tag", tag), *query.toTypedArray())
        val posts = statuses.map { it.toPost(server) }.also(explored::remember)
        return PostsPage(posts, next = if (statuses.size < PageSize) null else statuses.last().id)
    }

    /** Throws unless [server] answers for its members' posts or, failing those, its trending posts: what Explore shows. */
    suspend fun check(server: String) {
        try {
            get<List<JsonElement>>(server, listOf("timelines", "public"), "local" to "true", "limit" to "1")
        } catch (e: PublicServerException) {
            if (!e.notPublic) throw e
            get<List<JsonElement>>(server, listOf("trends", "statuses"), "limit" to "1")
        }
    }

    private suspend inline fun <reified T> get(server: String, segments: List<String>, vararg query: Pair<String, String>): T {
        val response: HttpResponse = try {
            http.get {
                url {
                    protocol = URLProtocol.HTTPS
                    host = server
                    path("api", "v1", *segments.toTypedArray())
                    for ((name, value) in query) parameters.append(name, value)
                }
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
        } catch (e: IOException) {
            throw GhostException.Network(e)
        }
        if (!response.status.isSuccess()) throw PublicServerException(server, response.status.value)
        return try {
            json.decodeFromString<T>(response.bodyAsText())
        } catch (e: SerializationException) {
            // An HTML page or anything else that isn't the API: not a server Explore can read.
            throw PublicServerException(server, response.status.value)
        } catch (e: IOException) {
            throw GhostException.Network(e)
        }
    }

    private class CachedHighlights(val highlights: ServerHighlights?, val atMillis: Long)

    private companion object {
        const val PageSize = 20
        const val TagLimit = 20
        const val LinkLimit = 10
        const val MembersPageSize = 40
        const val MembersMaxPages = 5
        /** Fewer posts than this is too few to rank, so a quiet server is read further back than a day. */
        const val MembersEnough = 80
        const val MembersWindowMillis = 24 * 60 * 60 * 1000L
        const val HighlightsCacheMillis = 3 * 60 * 1000L
    }
}

/**
 * Turns what someone types for a server ("hachyderm.io", "https://hachyderm.io/explore", "@me@hachyderm.io")
 * into a lower-case ASCII host, or null when no host is left.
 */
fun normalizeServer(input: String): String? {
    var text = input.trim().lowercase()
    if ("://" in text) text = text.substringAfter("://")
    text = text.substringBefore('/').substringBefore('?').substringBefore('#')
    text = text.substringAfterLast('@').trimEnd('.')
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    val ascii = try {
        IDN.toASCII(text, IDN.ALLOW_UNASSIGNED)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return ascii.takeIf { HostPattern.matches(it) }
}

private val HostPattern = Regex("""[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+""")

@Serializable
internal data class MastodonStatus(
    val id: String,
    /** The ActivityPub id, which the user's site looks the post up by. */
    val uri: String,
    val url: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("in_reply_to_id") val inReplyToId: String? = null,
    val content: String = "",
    val sensitive: Boolean = false,
    @SerialName("spoiler_text") val spoilerText: String = "",
    val account: MastodonAccount,
    @SerialName("media_attachments") val media: List<MastodonMedia> = emptyList(),
    @SerialName("replies_count") val replies: Int = 0,
    @SerialName("reblogs_count") val reblogs: Int = 0,
    @SerialName("favourites_count") val favourites: Int = 0,
    val tags: List<MastodonStatusTag> = emptyList(),
    /** The preview of the first link in the post, once the server has fetched it. */
    val card: MastodonCard? = null,
)

internal val MastodonStatus.createdMillis: Long?
    get() = try {
        createdAt?.let { Instant.parse(it).toEpochMilli() }
    } catch (_: DateTimeParseException) {
        null
    }

@Serializable
internal data class MastodonAccount(
    /** `user` for the server's own accounts, `user@domain` for others. */
    val acct: String,
    @SerialName("display_name") val displayName: String = "",
    val avatar: String? = null,
    val url: String? = null,
    /** The ActivityPub id; servers before Mastodon 4.2 leave it out. */
    val uri: String? = null,
    /** Set by the account itself when it posts automatically. */
    val bot: Boolean = false,
)

@Serializable
internal data class MastodonStatusTag(val name: String)

@Serializable
internal data class MastodonCard(
    val url: String,
    val title: String = "",
    @SerialName("provider_name") val providerName: String? = null,
    val image: String? = null,
)

@Serializable
internal data class MastodonMedia(
    val type: String,
    val url: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    @SerialName("remote_url") val remoteUrl: String? = null,
    val description: String? = null,
    val meta: MastodonMediaMeta? = null,
)

@Serializable
internal data class MastodonMediaMeta(val original: MastodonMediaSize? = null)

@Serializable
internal data class MastodonMediaSize(val width: Double? = null, val height: Double? = null)

@Serializable
internal data class MastodonTag(val name: String, val history: List<MastodonTagDay> = emptyList())

/** Mastodon sends the counts as text. */
@Serializable
internal data class MastodonTagDay(val accounts: String = "0")

@Serializable
internal data class MastodonLink(
    val url: String,
    val title: String = "",
    @SerialName("provider_name") val providerName: String? = null,
    val image: String? = null,
    val history: List<MastodonTagDay> = emptyList(),
)

/** The post as the app shows it; [server] completes the handles of its own accounts. Whether the user liked or reposted it isn't known here. */
internal fun MastodonStatus.toPost(server: String): Post {
    val handle = "@" + if ('@' in account.acct) account.acct else "${account.acct}@$server"
    return Post(
        id = uri,
        sensitive = sensitive,
        contentWarning = spoilerText.ifBlank { null },
        content = content,
        url = url ?: uri,
        publishedAt = createdAt,
        likeCount = favourites,
        replyCount = replies,
        repostCount = reblogs,
        attachments = media.mapNotNull { it.toAttachment() },
        author = Author(
            id = account.uri ?: account.url ?: handle,
            handle = handle,
            name = account.displayName,
            avatarUrl = account.avatar,
            url = account.url,
        ),
    )
}

private fun MastodonMedia.toAttachment(): Attachment? {
    val source = url ?: remoteUrl ?: return null
    val kind = when (type) {
        "image" -> "Image"
        "video", "gifv" -> "Video"
        "audio" -> "Audio"
        else -> return null
    }
    return Attachment(
        type = kind,
        name = description,
        url = source,
        posterUrl = previewUrl.takeIf { kind != "Image" },
        width = meta?.original?.width?.toInt(),
        height = meta?.original?.height?.toInt(),
    )
}
