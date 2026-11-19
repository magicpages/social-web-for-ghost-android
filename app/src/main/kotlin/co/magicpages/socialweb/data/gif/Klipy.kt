package co.magicpages.socialweb.data.gif

import co.magicpages.socialweb.data.linkpreview.readUpTo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A Ghost site's KLIPY settings (`klipy` in Ghost Admin's config), which its editor's GIF card uses.
 * The app searches KLIPY with the same key, on behalf of the signed-in site.
 * [contentFilter] is KLIPY's filter for the site: "off", "low", "medium" or "high".
 */
data class KlipyConfig(val apiKey: String, val contentFilter: String = "off")

/** One GIF from KLIPY, in the sizes it comes in. */
data class KlipyGif(
    val id: String,
    /** What the GIF shows ("dog dancing"), for its alt text. */
    val title: String,
    /** Small, for the picker's grid. */
    val preview: Media,
    /** The GIF itself, then smaller versions of it, largest first: the first one under the upload limit is attached. */
    val sizes: List<Media>,
) {
    /** [bytes] is null when KLIPY doesn't say. */
    data class Media(val url: String, val width: Int, val height: Int, val bytes: Long?)
}

/** [next] continues the search; null at the end. */
data class KlipyPage(val gifs: List<KlipyGif>, val next: String?)

sealed class KlipyException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    /** KLIPY's own message, such as an invalid key. */
    class Refused(val klipyMessage: String) : KlipyException(klipyMessage)

    class Failed(val status: Int) : KlipyException("HTTP $status")

    class Network(cause: IOException) : KlipyException(cause.message, cause)

    /** None of the GIF's sizes fits the upload limit. */
    data object TooLarge : KlipyException()
}

/**
 * KLIPY's Tenor-compatible API (`api.klipy.com/v2`), as Ghost's editor uses it: `search` for a term,
 * `featured` (with Ghost's default term) when there's none.
 */
@Singleton
class KlipyClient @Inject constructor(private val http: HttpClient) {
    /** GIFs for [term], or featured ones for a blank term. [next] continues a page. */
    suspend fun gifs(config: KlipyConfig, term: String, next: String? = null, limit: Int = PageSize): KlipyPage {
        val response = try {
            http.get(klipyUrl(config, term.trim(), next, limit))
        } catch (e: IOException) {
            throw KlipyException.Network(e)
        }
        val body = try {
            response.bodyAsText().let(Json::parseToJsonElement) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (e: IOException) {
            throw KlipyException.Network(e)
        }
        if (!response.status.isSuccess() || (body?.get("result") as? JsonPrimitive)?.booleanOrNull == false) {
            klipyErrorMessage(body)?.let { throw KlipyException.Refused(it) }
            throw KlipyException.Failed(response.status.value)
        }
        return klipyPage(body ?: JsonObject(emptyMap()))
    }

    /**
     * The GIF's bytes: its full size, or the largest smaller one within [maxBytes].
     * Throws [KlipyException.TooLarge] when none fits, or the last download failure.
     */
    suspend fun download(gif: KlipyGif, maxBytes: Long): ByteArray {
        var failure: KlipyException = KlipyException.TooLarge
        for (media in gif.sizes) {
            if ((media.bytes ?: 0) > maxBytes) continue
            try {
                val bytes = http.prepareGet(media.url) { header(HttpHeaders.Accept, "image/gif") }.execute { response ->
                    if (!response.status.isSuccess()) throw KlipyException.Failed(response.status.value)
                    response.bodyAsChannel().readUpTo((maxBytes + 1).toInt())
                }
                when {
                    bytes.size > maxBytes -> failure = KlipyException.TooLarge
                    !bytes.isGif() -> failure = KlipyException.Failed(200)
                    else -> return bytes
                }
            } catch (e: KlipyException) {
                failure = e
            } catch (e: IOException) {
                failure = KlipyException.Network(e)
            }
        }
        throw failure
    }

    private fun ByteArray.isGif() = size >= 6 && String(this, 0, 6, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" }

    private companion object {
        const val PageSize = 30
    }
}

private const val ApiUrl = "https://api.klipy.com/v2"

/** Tells KLIPY which integration is asking (Ghost's editor sends "ghost-editor"). */
private const val ClientKey = "social-web-app"

/** What Ghost's editor shows before anything is typed. */
private const val FeaturedTerm = "excited"

/** Largest first, as [KlipyGif.sizes] lists them. */
private val SizeNames = listOf("gif", "mediumgif", "tinygif")

internal fun klipyUrl(config: KlipyConfig, term: String, next: String?, limit: Int): String =
    URLBuilder(ApiUrl).apply {
        appendPathSegments(if (term.isEmpty()) "featured" else "search")
        parameters.append("q", term.ifEmpty { FeaturedTerm })
        parameters.append("key", config.apiKey)
        parameters.append("client_key", ClientKey)
        parameters.append("contentfilter", config.contentFilter)
        parameters.append("media_filter", SizeNames.reversed().joinToString(","))
        parameters.append("limit", limit.toString())
        if (!next.isNullOrEmpty()) parameters.append("pos", next)
    }.buildString()

/** From Ghost Admin's `{"config": {"klipy": {"apiKey": …, "contentFilter": …}}}`; null when there's no key. */
internal fun klipyConfigFrom(adminConfig: JsonElement): KlipyConfig? {
    val klipy = ((adminConfig as? JsonObject)?.get("config") as? JsonObject)?.get("klipy") as? JsonObject ?: return null
    val key = klipy.string("apiKey")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return KlipyConfig(key, klipy.string("contentFilter")?.takeIf { it.isNotEmpty() } ?: "off")
}

internal fun klipyPage(json: JsonObject): KlipyPage {
    val gifs = (json["results"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::klipyGif) }
    val next = (json["next"] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isEmpty() || it == "0" }
    return KlipyPage(gifs, next.takeIf { gifs.isNotEmpty() })
}

internal fun klipyGif(result: JsonObject): KlipyGif? {
    val formats = result["media_formats"] as? JsonObject ?: return null
    fun media(name: String): KlipyGif.Media? {
        val format = formats[name] as? JsonObject ?: return null
        val url = format.string("url")?.takeIf { it.startsWith("https://", ignoreCase = true) } ?: return null
        val dims = (format["dims"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.intOrNull ?: 0 }
        return KlipyGif.Media(url, dims.getOrElse(0) { 0 }, dims.getOrElse(1) { 0 }, (format["size"] as? JsonPrimitive)?.longOrNull)
    }
    val sizes = SizeNames.mapNotNull(::media)
    val preview = media("tinygif") ?: sizes.lastOrNull() ?: return null
    val id = (result["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: preview.url
    val title = listOf("content_description", "title")
        .firstNotNullOfOrNull { name -> result.string(name)?.trim()?.takeIf { it.isNotEmpty() } }
        .orEmpty()
    return KlipyGif(id, title, preview, sizes)
}

/** KLIPY's `{"result": false, "errors": {"message": [...]}}`, or Tenor's `{"error": {"message": …}}`. */
internal fun klipyErrorMessage(json: JsonObject?): String? {
    when (val message = (json?.get("errors") as? JsonObject)?.get("message")) {
        is JsonArray -> (message.firstOrNull() as? JsonPrimitive)?.contentOrNull?.let { return it }
        is JsonPrimitive -> message.contentOrNull?.let { return it }
        else -> Unit
    }
    return ((json?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
