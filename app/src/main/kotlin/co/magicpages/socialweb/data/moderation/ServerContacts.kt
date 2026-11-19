package co.magicpages.socialweb.data.moderation

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Finds the email address a fediverse server publishes for its moderators, so a report can be
 * copied to them as well. Ghost has no report API, which makes the user's mail app the only way
 * a report reaches anyone; the server's own moderators are the ones who can act on the account.
 *
 * Everything is asked without signing in, in the order servers are likely to answer:
 * 1. Mastodon (and forks) `/api/v2/instance`: `contact.email`;
 * 2. older Mastodon `/api/v1/instance`: `email`;
 * 3. NodeInfo (Misskey, Sharkey, others): `metadata.maintainer.email`.
 *
 * Lookups never throw: a server that doesn't answer simply has no contact.
 */
@Singleton
class ServerContacts @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) {
    private val lock = Mutex()

    /** Per lower-case host; a null value is a server that answered but publishes no contact. */
    private val known = HashMap<String, String?>()

    /** The moderators' email address of [host], or null when it publishes none (or can't be reached). */
    suspend fun moderatorEmail(host: String): String? {
        val key = host.trim().lowercase()
        if (!isPlainHost(key)) return null
        lock.withLock { if (key in known) return known[key] }
        val lookup = lookUp(key)
        // A server that couldn't be reached at all is asked again next time, not remembered as having no contact.
        if (lookup.reached) lock.withLock { known[key] = lookup.email }
        return lookup.email
    }

    private class Lookup(val email: String?, val reached: Boolean)

    private suspend fun lookUp(host: String): Lookup {
        var reached = false

        suspend fun fetch(path: String): JsonElement? {
            val reply = http.publicGet("https://$host$path") ?: return null
            reached = true
            return if (reply.status in 200..299) parseJson(reply.body, json) else null
        }

        val email = mastodonContactEmail(fetch("/api/v2/instance"))
            ?: instanceEmail(fetch("/api/v1/instance"))
            ?: nodeInfoUrl(fetch("/.well-known/nodeinfo"), host)?.let { url ->
                val reply = http.publicGet(url)
                if (reply != null) reached = true
                nodeInfoMaintainerEmail(reply?.takeIf { it.status in 200..299 }?.let { parseJson(it.body, json) })
            }
        return Lookup(email, reached)
    }
}

/** A plain body of one public GET; [status] is the HTTP status. */
internal class PublicReply(val status: Int, val body: String)

/** Longest answer read; the pages asked for are small JSON documents. */
private const val MaxReplyChars = 500_000

/** Short, so a dead server delays the report flow by seconds, not a minute. */
private const val PublicRequestMillis = 6_000L

/**
 * An unauthenticated GET of [url], or null when the server can't be reached in time. The client is
 * shared with the rest of the app but carries no credentials of its own.
 */
internal suspend fun HttpClient.publicGet(url: String): PublicReply? = try {
    withTimeoutOrNull(PublicRequestMillis) {
        val response = get(url) { header(HttpHeaders.Accept, "application/json") }
        PublicReply(response.status.value, response.bodyAsText().take(MaxReplyChars))
    }
} catch (_: IOException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** Whether [host] is a bare DNS name, so building a URL from it can't reach anywhere else. */
internal fun isPlainHost(host: String): Boolean =
    host.isNotEmpty() && host.length <= 253 && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }

internal fun parseJson(body: String, json: Json): JsonElement? = try {
    json.parseToJsonElement(body)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun JsonElement?.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

/** [raw] as an address to write to, or null when it is blank or couldn't be one. */
internal fun cleanEmail(raw: String?): String? {
    val email = raw?.trim().orEmpty()
    if (email.isEmpty() || email.any { it.isWhitespace() || it == ',' || it == ';' || it == '<' || it == '>' }) return null
    val at = email.indexOf('@')
    return email.takeIf { at > 0 && at < email.lastIndex && email.indexOf('@', at + 1) < 0 }
}

/** `/api/v2/instance`: `contact.email`. */
internal fun mastodonContactEmail(root: JsonElement?): String? = cleanEmail(root.field("contact").field("email").text())

/** `/api/v1/instance`: `email`. */
internal fun instanceEmail(root: JsonElement?): String? = cleanEmail(root.field("email").text())

private val NodeInfoSchema = Regex("/schema/2\\.(\\d+)$")

/**
 * `/.well-known/nodeinfo`: the link to the newest 2.x document. It must stay on [host]: the
 * answer of one server is no reason to ask another.
 */
internal fun nodeInfoUrl(root: JsonElement?, host: String): String? =
    (root.field("links") as? JsonArray)
        ?.mapNotNull { link ->
            val minor = NodeInfoSchema.find(link.field("rel").text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val href = link.field("href").text()
            if (minor != null && href != null && isHttpsOn(href, host)) minor to href else null
        }
        ?.maxByOrNull { it.first }
        ?.second

private fun isHttpsOn(url: String, host: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(host, ignoreCase = true)
}.getOrDefault(false)

/** A NodeInfo document: `metadata.maintainer.email`. */
internal fun nodeInfoMaintainerEmail(root: JsonElement?): String? =
    cleanEmail(root.field("metadata").field("maintainer").field("email").text())
