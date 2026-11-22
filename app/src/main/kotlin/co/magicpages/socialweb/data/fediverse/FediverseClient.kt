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
import javax.inject.Inject
import javax.inject.Singleton
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

/** A public server refused a request (or isn't one); 401, 403 and 422 mean it keeps that list to signed-in users. */
class PublicServerException(val server: String, val status: Int) : Exception("HTTP $status from $server") {
    val notPublic: Boolean get() = status == 401 || status == 403 || status == 422
}

/**
 * Reads what a Mastodon-compatible server shares without signing in: its trending posts and hashtags,
 * and the posts under a hashtag. The posts come back as the app's [Post]s with their ActivityPub ids,
 * so opening, liking, reposting and replying go through the user's own site, which fetches them.
 */
@Singleton
class FediverseClient @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) {
    /** A page of the server's trending posts, from [offset]; the next page's offset is in [PostsPage.next]. */
    suspend fun trendingPosts(server: String, offset: Int): PostsPage {
        val statuses = get<List<MastodonStatus>>(server, listOf("trends", "statuses"), "limit" to "$PageSize", "offset" to "$offset")
        return PostsPage(statuses.map { it.toPost(server) }, next = if (statuses.size < PageSize) null else "${offset + statuses.size}")
    }

    suspend fun trendingTags(server: String): List<TrendingTag> =
        get<List<MastodonTag>>(server, listOf("trends", "tags"), "limit" to "$TagLimit").map { tag ->
            TrendingTag(tag.name, tag.history.take(2).sumOf { it.accounts.toIntOrNull() ?: 0 })
        }

    /** The newest public posts under #[tag], older than [maxId] when it's given; the next page's id is in [PostsPage.next]. */
    suspend fun tagPosts(server: String, tag: String, maxId: String?): PostsPage {
        val query = listOfNotNull("limit" to "$PageSize", maxId?.let { "max_id" to it })
        val statuses = get<List<MastodonStatus>>(server, listOf("timelines", "tag", tag), *query.toTypedArray())
        return PostsPage(statuses.map { it.toPost(server) }, next = if (statuses.size < PageSize) null else statuses.last().id)
    }

    /** Throws unless [server] answers for its trending posts, which is all Explore needs from it. */
    suspend fun check(server: String) {
        get<List<JsonElement>>(server, listOf("trends", "statuses"), "limit" to "1")
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

    private companion object {
        const val PageSize = 20
        const val TagLimit = 20
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
    val content: String = "",
    val sensitive: Boolean = false,
    @SerialName("spoiler_text") val spoilerText: String = "",
    val account: MastodonAccount,
    @SerialName("media_attachments") val media: List<MastodonMedia> = emptyList(),
    @SerialName("replies_count") val replies: Int = 0,
    @SerialName("reblogs_count") val reblogs: Int = 0,
    @SerialName("favourites_count") val favourites: Int = 0,
)

@Serializable
internal data class MastodonAccount(
    /** `user` for the server's own accounts, `user@domain` for others. */
    val acct: String,
    @SerialName("display_name") val displayName: String = "",
    val avatar: String? = null,
    val url: String? = null,
    /** The ActivityPub id; servers before Mastodon 4.2 leave it out. */
    val uri: String? = null,
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
