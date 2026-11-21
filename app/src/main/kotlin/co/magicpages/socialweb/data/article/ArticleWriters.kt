package co.magicpages.socialweb.data.article

import co.magicpages.socialweb.data.activitypub.GhostAuthor
import co.magicpages.socialweb.data.linkpreview.charsetOf
import co.magicpages.socialweb.data.linkpreview.isPreviewableUrl
import co.magicpages.socialweb.data.linkpreview.readUpTo
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Who wrote an article the social web service names no writers for: an article from a Ghost site on another
 * instance comes with the site's account only. Its page says, in its structured data.
 */
@Singleton
class ArticleWriters @Inject constructor(private val http: HttpClient) {
    /** The writers named on the page at [pageUrl]; empty when it names none or can't be read. Never throws. */
    suspend fun load(pageUrl: String): List<GhostAuthor> {
        if (!pageUrl.startsWith("https://", ignoreCase = true) || !isPreviewableUrl(pageUrl)) return emptyList()
        val html = try {
            fetchHead(pageUrl)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return emptyList()
        return withContext(Dispatchers.Default) { writersFromStructuredData(html) }
    }

    private suspend fun fetchHead(url: String): String? =
        http.prepareGet(url) {
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
            timeout {
                connectTimeoutMillis = ConnectTimeoutMillis
                requestTimeoutMillis = RequestTimeoutMillis
            }
        }.execute { response ->
            if (!response.status.isSuccess()) return@execute null
            val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
            String(response.bodyAsChannel().readUpTo(MaxBytes), charsetOf(contentType))
        }

    private companion object {
        /** Structured data sits in the page's `<head>`; the rest of a long page is never read. */
        const val MaxBytes = 256 * 1024
        const val ConnectTimeoutMillis = 5_000L
        const val RequestTimeoutMillis = 10_000L
    }
}

/**
 * The writers an article page's schema.org JSON-LD names (Ghost, WordPress SEO plugins and many other platforms add
 * it): the article's `author`, then its `contributor`s, where Ghost lists co-authors. In page order, once each, and
 * people only: an organization as the author is the publication, which the reader shows anyway.
 */
internal fun writersFromStructuredData(html: String): List<GhostAuthor> {
    val objects = jsonLdScript.findAll(html).mapNotNull { match ->
        try {
            Json.parseToJsonElement(match.groupValues[1].trim())
        } catch (_: SerializationException) {
            null
        }
    }.flatMap { it.objects() }.toList()
    val byId = objects.mapNotNull { obj -> obj.string("@id")?.let { it to obj } }.toMap()
    val article = objects.firstOrNull { it.isArticle() } ?: return emptyList()
    val seen = HashSet<String>()
    return listOf("author", "contributor").flatMap { key -> article[key].entries() }.mapNotNull { entry ->
        val person = entry.resolvedIn(byId) ?: return@mapNotNull null
        if ("Organization" in person.types()) return@mapNotNull null
        val name = person.string("name")?.trim()?.takeIf { it.isNotEmpty() }?.let(::decodeEntities) ?: return@mapNotNull null
        if (!seen.add(name.lowercase())) return@mapNotNull null
        GhostAuthor(name = name, profileImage = person.imageUrl())
    }
}

private val jsonLdScript = Regex(
    """<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/** Every object in a block: the block itself, an array's items, and those of a `@graph`. */
private fun JsonElement.objects(): List<JsonObject> = when (this) {
    is JsonArray -> flatMap { it.objects() }
    is JsonObject -> listOf(this) + (this["@graph"]?.objects() ?: emptyList())
    else -> emptyList()
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.types(): List<String> = when (val type = this["@type"]) {
    is JsonPrimitive -> listOfNotNull(type.takeIf { it.isString }?.content)
    is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    else -> emptyList()
}

/** Article, NewsArticle, BlogPosting, TechArticle… */
private fun JsonObject.isArticle() = types().any { it.endsWith("Article") || it.endsWith("Posting") }

private fun JsonElement?.entries(): List<JsonElement> = when (this) {
    null -> emptyList()
    is JsonArray -> this
    else -> listOf(this)
}

/** A plain name as a person, and a reference (`{"@id": …}`, as Yoast's graph has) as the object it points to. */
private fun JsonElement.resolvedIn(byId: Map<String, JsonObject>): JsonObject? = when (this) {
    is JsonPrimitive -> if (isString) JsonObject(mapOf("name" to this)) else null
    is JsonObject -> if (this["name"] == null) string("@id")?.let(byId::get) ?: this else this
    else -> null
}

private fun JsonObject.imageUrl(): String? {
    val link = when (val image = this["image"].entries().firstOrNull()) {
        is JsonPrimitive -> image.takeIf { it.isString }?.content
        is JsonObject -> image.string("url")
        else -> null
    }
    return link?.takeIf { it.startsWith("https://", ignoreCase = true) }
}

/** Some sites escape names as HTML (`&amp;`, `&#39;`) inside the JSON. */
private fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    return htmlEntities.entries.fold(text) { result, (entity, character) -> result.replace(entity, character) }
}

private val htmlEntities = linkedMapOf(
    "&#39;" to "'", "&#039;" to "'", "&apos;" to "'", "&quot;" to "\"", "&lt;" to "<", "&gt;" to ">", "&amp;" to "&",
)
