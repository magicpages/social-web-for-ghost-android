package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.ghost.GhostException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads public ActivityStreams objects straight from the server that hosts them, for the details
 * the site's social web service doesn't pass on. Anonymous: servers that only answer signed
 * requests ("authorized fetch") give nothing, except Mastodon ones, whose public REST API still
 * tells the same details.
 */
@Singleton
class RemoteObjects @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) {
    /** The object at [id], or null when it isn't public, isn't there, or can't be read right now. */
    suspend fun fetch(id: String): RemoteObject? {
        if (!id.isWebUrl()) return null
        return try {
            val response = http.get(id) { header(HttpHeaders.Accept, ACCEPT) }
            when {
                response.status.isSuccess() -> json.decodeFromString<RemoteObject>(response.bodyAsText())
                response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden -> fromMastodonApi(id)
                else -> null
            }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** The details of a Mastodon status or account at [id], from the API Mastodon serves to anyone. */
    private suspend fun fromMastodonApi(id: String): RemoteObject? {
        MastodonStatusId.matchEntire(id)?.let { match ->
            val (host, statusId) = match.destructured
            val status = readApi<MastodonStatus>("https://$host/api/v1/statuses/$statusId") ?: return null
            // The API names the parent by this server's own number; its ActivityPub id takes one more call.
            val parent = status.inReplyToId?.let { readApi<MastodonStatus>("https://$host/api/v1/statuses/$it") }
            return RemoteObject(url = status.url, inReplyTo = parent?.uri, emojis = status.emojis.map { it.toCustomEmoji() })
        }
        MastodonAccountId.matchEntire(id)?.let { match ->
            val (host, username) = match.destructured
            val account = readApi<MastodonAccount>("https://$host/api/v1/accounts/lookup?acct=$username") ?: return null
            return RemoteObject(name = account.displayName, url = account.url, emojis = account.emojis.map { it.toCustomEmoji() })
        }
        return null
    }

    private suspend inline fun <reified T> readApi(url: String): T? {
        val response = http.get(url) { header(HttpHeaders.Accept, "application/json") }
        return if (response.status.isSuccess()) json.decodeFromString<T>(response.bodyAsText()) else null
    }

    /**
     * A page of the replies the account [actorId] wrote, newest first, read from its public outbox.
     * The service keeps no list of an account's replies, but its outbox has them. [page] is a
     * [OutboxReplies.next] from an earlier call, null for the newest. Comes back empty when the
     * server keeps the outbox private.
     *
     * @throws GhostException.Network when the server can't be reached.
     */
    suspend fun outboxReplies(actorId: String, page: String?): OutboxReplies {
        val items = if (page != null) readJson(page) else newestOutboxPage(actorId)
        val replies = items?.let(::parseOutboxReplies) ?: return OutboxReplies(emptyList(), next = null)
        // A page that points at itself would page forever.
        return if (replies.next == page) replies.copy(next = null) else replies
    }

    private suspend fun newestOutboxPage(actorId: String): JsonObject? {
        // Some servers only show actors to signed requests yet serve the outbox to anyone, at the
        // address Mastodon and most others use.
        val outboxUrl = objectId(readJson(actorId)?.get("outbox")) ?: "${actorId.trimEnd('/')}/outbox"
        val outbox = readJson(outboxUrl) ?: return null
        return when (val first = outbox["first"]) {
            is JsonObject -> if (first.hasItems()) first else objectId(first)?.let { readJson(it) }
            is JsonPrimitive -> first.contentOrNull?.let { readJson(it) }
            // A collection without pages lists its items itself.
            else -> outbox
        }
    }

    /** Null when [url] isn't public, isn't there, or isn't JSON; throws when the server can't be reached. */
    private suspend fun readJson(url: String): JsonObject? {
        if (!url.isWebUrl()) return null
        return try {
            val response = http.get(url) { header(HttpHeaders.Accept, ACCEPT) }
            if (!response.status.isSuccess()) return null
            json.parseToJsonElement(response.bodyAsText()) as? JsonObject
        } catch (e: IOException) {
            throw GhostException.Network(e)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val ACCEPT = "application/activity+json, application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\""

        // The ids Mastodon gives statuses and accounts, and the profile page address it also answers on.
        val MastodonStatusId = Regex("""https://([^/]+)/users/[A-Za-z0-9_]+/statuses/(\d+)""")
        val MastodonAccountId = Regex("""https://([^/]+)/(?:users/|@)([A-Za-z0-9_]+)""")
    }
}

@Serializable
private data class MastodonStatus(
    val uri: String? = null,
    val url: String? = null,
    @SerialName("in_reply_to_id") val inReplyToId: String? = null,
    val emojis: List<MastodonEmoji> = emptyList(),
)

@Serializable
private data class MastodonAccount(
    @SerialName("display_name") val displayName: String? = null,
    val url: String? = null,
    val emojis: List<MastodonEmoji> = emptyList(),
)

@Serializable
private data class MastodonEmoji(val shortcode: String, val url: String) {
    fun toCustomEmoji() = CustomEmoji(shortcode, url)
}

/** One page of an account's outbox, cut down to its replies. [next] pages on; null at the end. */
data class OutboxReplies(val replies: List<OutboxReply>, val next: String?)

/** A reply ([id]) and the post it answers ([inReplyTo]); both are ActivityPub ids. */
data class OutboxReply(val id: String, val inReplyTo: String)

/**
 * The replies on an outbox page: notes and articles the account created that answer something.
 * Reposts are someone else's posts, and objects only referenced by id can't be told apart from
 * other posts without fetching each, so both are left out.
 */
internal fun parseOutboxReplies(page: JsonObject): OutboxReplies {
    val items = (page["orderedItems"] ?: page["items"]) as? JsonArray ?: JsonArray(emptyList())
    val replies = items.mapNotNull { element ->
        val activity = element as? JsonObject ?: return@mapNotNull null
        if ((activity["type"] as? JsonPrimitive)?.contentOrNull != "Create") return@mapNotNull null
        val created = activity["object"] as? JsonObject ?: return@mapNotNull null
        val id = objectId(created["id"])?.takeIf { it.isWebUrl() } ?: return@mapNotNull null
        val parent = objectId(created["inReplyTo"])?.takeIf { it.isWebUrl() } ?: return@mapNotNull null
        OutboxReply(id, parent)
    }
    return OutboxReplies(replies, next = objectId(page["next"])?.takeIf { it.isWebUrl() })
}

private fun JsonObject.hasItems() = "orderedItems" in this || "items" in this

private fun String.isWebUrl() = startsWith("https://") || startsWith("http://")
