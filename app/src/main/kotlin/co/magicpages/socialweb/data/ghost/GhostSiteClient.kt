package co.magicpages.socialweb.data.ghost

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup

/** A site another site recommends, with what could be found out about it from public endpoints. */
data class Recommendation(
    /** The recommended address, as the recommending site lists it. */
    val url: String,
    /** The Ghost title, or the page's `og:site_name` or `<title>`. */
    val title: String? = null,
    val excerpt: String? = null,
    val iconUrl: String? = null,
    val isGhost: Boolean = false,
    /** A Ghost site with member sign-up open. */
    val allowsSubscribing: Boolean = false,
    /** Its social web account as `@user@domain`, found with WebFinger. */
    val handle: String? = null,
) {
    val displayAddress: String get() = displayAddress(url)
    val displayName: String get() = title ?: displayAddress
}

/** What `/members/api/site` says about a Ghost site. */
data class GhostSiteInfo(
    val url: String?,
    val title: String?,
    val description: String?,
    val iconUrl: String?,
    /** Member sign-up is open to anyone (Portal's subscribe form). */
    val allowsSubscribing: Boolean,
)

/** The name, description and icon a page's `<head>` gives. */
data class PageHead(val title: String?, val description: String?, val iconUrl: String?)

/**
 * Reads what a Ghost site publishes without signing in:
 * - `/.well-known/recommendations.json`: the sites it recommends (an open format, written for every Ghost site);
 * - `/members/api/site/`: answers on Ghost sites with their title, icon and whether sign-up is open;
 * - WebFinger for `acct:index@host`: Ghost's account is always the actor `users/index`, and its WebFinger accepts
 *   `index` whatever username the site picked. The answer's subject is the real handle;
 * - for other sites, the page's `<head>`.
 */
@Singleton
class GhostSiteClient @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) {
    /**
     * The URLs [siteUrl] recommends, in its order. Empty when it recommends none or doesn't publish the
     * file; throws [IOException] when the site can't be reached.
     */
    suspend fun recommendedUrls(siteUrl: String): List<String> {
        val endpoint = resolveUrl(".well-known/recommendations.json", siteUrl) ?: return emptyList()
        val page = fetch(endpoint, "application/json") ?: throw IOException("Couldn't reach $siteUrl")
        return if (page.status in 200..299) parseRecommendedUrls(page.body, json) else emptyList()
    }

    /** The site's Ghost details, or null when it isn't a Ghost site (or can't be reached). */
    suspend fun siteInfo(siteUrl: String): GhostSiteInfo? = (probe(siteUrl) as? SiteCheck.Ghost)?.info

    /** What each of [urls] is, a few at a time, each as soon as it's known. */
    fun details(urls: List<String>): Flow<IndexedValue<Recommendation>> = channelFlow {
        val gate = Semaphore(MaxConcurrentSites)
        urls.forEachIndexed { index, url ->
            launch { gate.withPermit { send(IndexedValue(index, details(url))) } }
        }
    }

    /** One site: Ghost or not, its social web account if it has one, its name and icon. */
    suspend fun details(url: String): Recommendation {
        var result = Recommendation(url)
        // Cleartext traffic is blocked; such a site stays a plain link.
        if (!url.startsWith("https://", ignoreCase = true)) return result

        val check = probe(url)
        if (check is SiteCheck.Ghost) {
            result = result.copy(
                title = check.info.title,
                excerpt = check.info.description,
                iconUrl = check.info.iconUrl,
                isGhost = true,
                allowsSubscribing = check.info.allowsSubscribing,
            )
        }
        result = result.copy(handle = webFingerHandle(url))
        // A site that didn't answer at all isn't asked again for its page.
        if (check is SiteCheck.NotGhost) {
            fetch(url, "text/html", maxChars = MaxHeadChars)?.takeIf { it.status in 200..299 }?.let { page ->
                val head = parsePageHead(page.body, page.finalUrl)
                result = result.copy(title = head.title, excerpt = head.description, iconUrl = head.iconUrl)
            }
        }
        if (result.iconUrl == null) {
            // Most sites answer this, Ghost with a redirect to the publication icon.
            result = result.copy(iconUrl = resolveUrl("/favicon.ico", url))
        }
        return result
    }

    private sealed interface SiteCheck {
        data class Ghost(val info: GhostSiteInfo) : SiteCheck
        data object NotGhost : SiteCheck
        data object Unreachable : SiteCheck
    }

    private suspend fun probe(siteUrl: String): SiteCheck {
        val endpoint = resolveUrl("members/api/site/", siteUrl) ?: return SiteCheck.NotGhost
        val first = probeAt(endpoint, siteUrl)
        // A Ghost site in a subdirectory may be recommended by its root address, or the other way round.
        val path = parseWebUri(siteUrl)?.rawPath.orEmpty()
        if (first !is SiteCheck.NotGhost || path.isEmpty() || path == "/") return first
        val rootEndpoint = resolveUrl("/members/api/site/", siteUrl) ?: return first
        return probeAt(rootEndpoint, siteUrl)
    }

    private suspend fun probeAt(endpoint: String, base: String): SiteCheck {
        val page = fetch(endpoint, "application/json") ?: return SiteCheck.Unreachable
        if (page.status !in 200..299) return SiteCheck.NotGhost
        return parseGhostSite(page.body, base, json)?.let(SiteCheck::Ghost) ?: SiteCheck.NotGhost
    }

    private suspend fun webFingerHandle(url: String): String? {
        val uri = parseWebUri(url) ?: return null
        val host = uri.host.lowercase()
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val resource = "acct:index@${host.removePrefix("www.")}"
        val endpoint = "https://$host$port/.well-known/webfinger?resource=$resource"
        val page = fetch(endpoint, "application/jrd+json, application/json") ?: return null
        return if (page.status in 200..299) parseWebFingerHandle(page.body, json) else null
    }

    private class Page(val status: Int, val body: String, val finalUrl: String)

    /** Null when the site can't be reached in time. */
    private suspend fun fetch(url: String, accept: String, maxChars: Int = Int.MAX_VALUE): Page? = try {
        withTimeoutOrNull(RequestTimeoutMillis) {
            val response = http.get(url) { header(HttpHeaders.Accept, accept) }
            Page(response.status.value, response.bodyAsText().take(maxChars), response.call.request.url.toString())
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        /** A recommended site that's down mustn't hold up the list for long. */
        const val RequestTimeoutMillis = 6_000L
        const val MaxConcurrentSites = 6

        /** The `<head>` is near the top; a long page isn't read whole. */
        const val MaxHeadChars = 300_000
    }
}

/** The http(s) URLs of `/.well-known/recommendations.json`, deduplicated; entries without one are skipped. */
internal fun parseRecommendedUrls(body: String, json: Json): List<String> {
    val entries = try {
        json.parseToJsonElement(body) as? JsonArray
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: return emptyList()
    val seen = HashSet<String>()
    return entries.mapNotNull { entry ->
        val url = ((entry as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull?.trim()
        url?.takeIf { parseWebUri(it) != null && seen.add(it) }
    }
}

/**
 * A Ghost site from `/members/api/site/`. Null for anything that isn't one: older Ghost versions don't send
 * `allow_external_signup`, and their answer isn't reliable (Ghost skips them the same way for its own checks).
 */
internal fun parseGhostSite(body: String, base: String, json: Json): GhostSiteInfo? {
    val site = try {
        (json.parseToJsonElement(body) as? JsonObject)?.get("site") as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    fun text(key: String) = (site[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val allows = (site["allow_external_signup"] as? JsonPrimitive)?.booleanOrNull ?: return null
    val siteUrl = text("url")
    val icon = (text("icon") ?: text("logo"))?.let { resolveUrl(it, siteUrl ?: base) }
    return GhostSiteInfo(url = siteUrl, title = text("title"), description = text("description"), iconUrl = icon, allowsSubscribing = allows)
}

/** `acct:user@domain` becomes `@user@domain`, when the answer points to an ActivityPub actor. */
internal fun parseWebFingerHandle(body: String, json: Json): String? {
    val answer = try {
        json.parseToJsonElement(body) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    val subject = (answer["subject"] as? JsonPrimitive)?.contentOrNull ?: return null
    if (!subject.startsWith("acct:", ignoreCase = true)) return null
    val isActor = (answer["links"] as? JsonArray).orEmpty().any { link ->
        val fields = link as? JsonObject ?: return@any false
        val type = (fields["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        (fields["rel"] as? JsonPrimitive)?.contentOrNull == "self" && ("activity+json" in type || "ld+json" in type)
    }
    if (!isActor) return null
    val account = subject.substring("acct:".length)
    val parts = account.split('@')
    if (parts.size != 2 || parts[0].isEmpty() || '.' !in parts[1] || '/' in account) return null
    return "@$account"
}

/** Name, description and best icon from the page's `<head>` ([base] resolves relative addresses). */
internal fun parsePageHead(html: String, base: String): PageHead {
    val head = Jsoup.parse(html, base).head()
    fun meta(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        head.select("meta[property=\"$name\"], meta[name=\"$name\"]").firstOrNull()?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
    }
    val title = meta("og:site_name", "og:title") ?: head.selectFirst("title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
    val description = meta("og:description", "description")
    // Bigger icons and touch icons score higher.
    val icon = head.select("link[rel][href]").mapNotNull { link ->
        val rels = link.attr("rel").lowercase().split(' ')
        if (rels.none { it == "icon" || it == "apple-touch-icon" || it == "apple-touch-icon-precomposed" }) return@mapNotNull null
        val url = resolveUrl(link.attr("href"), base) ?: return@mapNotNull null
        val size = link.attr("sizes").substringBefore('x').toIntOrNull() ?: 0
        val touch = if (rels.any { it.startsWith("apple-touch-icon") }) 1_000 else 0
        url to (size + touch)
    }.maxByOrNull { it.second }?.first
    return PageHead(title, description, icon)
}

/**
 * [path] against [base] read as a directory (so `.well-known/x` under `https://host/blog` is
 * `https://host/blog/.well-known/x`); null when the result isn't an http(s) address.
 */
internal fun resolveUrl(path: String, base: String): String? {
    val trimmed = path.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("javascript:", ignoreCase = true) || trimmed.startsWith("data:", ignoreCase = true)) return null
    val directory = base.substringBefore('#').substringBefore('?').let { if (it.endsWith('/')) it else "$it/" }
    return try {
        URI(directory).resolve(trimmed).toString().takeIf { parseWebUri(it) != null }
    } catch (_: java.net.URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
