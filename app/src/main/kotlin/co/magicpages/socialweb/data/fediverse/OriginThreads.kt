package co.magicpages.socialweb.data.fediverse

import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.ReplyChain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.isSuccess
import io.ktor.http.path
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * What a post's home server knows about its thread: the post as the server counts it now, and the
 * replies it has seen, laid out like the user's site lays out a thread ([ReplyChain.Child]).
 * Either part is empty when the server didn't answer for it.
 */
data class OriginThread(val post: Post?, val replies: List<ReplyChain.Child>)

/**
 * Reads a thread from the server the post lives on. The user's own site only knows the replies that
 * federated to it and keeps no counts for other servers' posts, so a thread opened from Fediverse
 * explore would look dead without this.
 *
 * It contacts a third-party server, so callers ask only for posts the user opted into reading
 * from there (the ones [ExploredPosts] knows), never for posts of the user's own feed.
 */
@Singleton
class OriginThreads @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) {
    /**
     * The thread of the post with ActivityPub id [postId], or null when its server can't be asked (an id
     * that isn't Mastodon-shaped) or answers for neither the post nor its context. Never throws.
     */
    suspend fun load(postId: String): OriginThread? {
        val origin = originStatusOf(postId) ?: return null
        val (status, context) = coroutineScope {
            val status = async { fetch(origin, context = false) }
            val context = async { fetch(origin, context = true) }
            status.await() to context.await()
        }
        return withContext(Dispatchers.Default) {
            val post = status?.let { parseStatus(it, postId, origin.host) }
            val replies = context?.let { parseReplies(it, postId, origin.host) }
            if (post == null && replies == null) null else OriginThread(post, replyTree(origin.id, replies.orEmpty()))
        }
    }

    /** The response body, or null for anything but a success. */
    private suspend fun fetch(origin: OriginStatusId, context: Boolean): String? = try {
        val response = http.get {
            url {
                protocol = URLProtocol.HTTPS
                host = origin.host
                if (context) path("api", "v1", "statuses", origin.id, "context") else path("api", "v1", "statuses", origin.id)
            }
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        if (response.status.isSuccess()) response.bodyAsText() else null
    } catch (_: IOException) {
        null
    }

    // SerializationException is an IllegalArgumentException, and so is what asking a JSON value for the wrong kind throws.
    private fun parseStatus(body: String, postId: String, host: String): Post? = try {
        // A server that answers with some other post than the one asked for would put its counts on the wrong post.
        json.decodeFromString<MastodonStatus>(body).takeIf { it.uri == postId }?.toPost(host)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseReplies(body: String, postId: String, host: String): List<OriginReply>? = try {
        val descendants = json.parseToJsonElement(body).jsonObject["descendants"]?.jsonArray
        descendants?.mapNotNull { element ->
            // One post a server describes oddly shouldn't cost the whole thread.
            try {
                val status = json.decodeFromJsonElement<MastodonStatus>(element)
                val link = json.decodeFromJsonElement<ReplyLink>(element)
                if (status.uri == postId) null else OriginReply(link.id, link.inReplyToId, status.toPost(host))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Where a post lives on its home server: [host] and the [id] that server's API knows it by. */
internal data class OriginStatusId(val host: String, val id: String)

/** A reply as the home server's context lists it: its id there, the id it answers, and the post itself. */
internal data class OriginReply(val id: String, val parentId: String?, val post: Post)

/** The part of a status the thread tree needs and [MastodonStatus] doesn't carry. */
@Serializable
private data class ReplyLink(
    val id: String,
    @SerialName("in_reply_to_id") val inReplyToId: String? = null,
)

/**
 * Where the post with ActivityPub id [apId] can be read through a Mastodon-compatible API, or null.
 * Mastodon's own ids end in the status id its server uses (`https://host/users/name/statuses/<id>`,
 * the web address `https://host/@name/<id>`); anything else (a UUID, a path of some other software) isn't known to
 * have that API, and isn't worth contacting a server for.
 */
internal fun originStatusOf(apId: String): OriginStatusId? {
    val uri = try {
        URI(apId.trim())
    } catch (_: URISyntaxException) {
        return null
    }
    if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return null
    val host = uri.host?.let(::normalizeServer) ?: return null
    // An address like 10.0.0.1 passes as a host name; no server name ends in digits only.
    if (host.substringAfterLast('.').all { it.isDigit() }) return null
    val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
    val id = segments.lastOrNull()?.takeIf { StatusId.matches(it) } ?: return null
    val before = segments.getOrNull(segments.size - 2) ?: return null
    if (before != "statuses" && !before.startsWith("@") && !before.startsWith("%40")) return null
    return OriginStatusId(host, id)
}

private val StatusId = Regex("""\d{1,32}""")

/**
 * The direct replies to the status [rootId] from [replies], in the order the server listed them. Each comes with
 * everything below it, flattened depth first, as the follow-ups the thread shows under a reply. Replies whose
 * parent isn't among [replies] (hidden or deleted there) and repeats are left out.
 */
internal fun replyTree(rootId: String, replies: List<OriginReply>): List<ReplyChain.Child> {
    val byParent = replies.groupBy { it.parentId }
    val seen = hashSetOf(rootId)
    return byParent[rootId].orEmpty().filter { seen.add(it.id) }.map { direct ->
        val followUps = ArrayList<Post>()
        val pending = ArrayDeque<OriginReply>()
        byParent[direct.id]?.let { pending.addAll(it.asReversed()) }
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            if (!seen.add(next.id)) continue
            followUps += next.post
            byParent[next.id]?.let { pending.addAll(it.asReversed()) }
        }
        ReplyChain.Child(direct.post, followUps)
    }
}
