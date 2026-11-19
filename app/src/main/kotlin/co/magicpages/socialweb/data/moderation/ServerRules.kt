package co.magicpages.socialweb.data.moderation

import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One rule of a server, as it lists them in `/api/v1/instance/rules`. */
data class ServerRule(val id: String, val text: String, val hint: String = "")

/**
 * The rules a fediverse server publishes, which a report can name as the ones broken. Mastodon and
 * its forks serve them to anyone; most other software doesn't, which simply leaves the list empty.
 */
@Singleton
class ServerRules @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) {
    /** The rules of [host] in its order; empty when it publishes none or can't be reached. Never throws. */
    suspend fun rules(host: String): List<ServerRule> {
        val name = host.trim().lowercase()
        if (!isPlainHost(name)) return emptyList()
        val reply = http.publicGet("https://$name/api/v1/instance/rules") ?: return emptyList()
        if (reply.status !in 200..299) return emptyList()
        return parseServerRules(parseJson(reply.body, json))
    }
}

/** The rules in a JSON array of `{id, text, hint?}`; entries without text are skipped. */
internal fun parseServerRules(root: JsonElement?): List<ServerRule> =
    (root as? JsonArray).orEmpty().mapIndexedNotNull { index, entry ->
        val rule = entry as? JsonObject ?: return@mapIndexedNotNull null
        val text = (rule["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@mapIndexedNotNull null
        ServerRule(
            id = (rule["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: index.toString(),
            text = text,
            hint = (rule["hint"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
        )
    }
