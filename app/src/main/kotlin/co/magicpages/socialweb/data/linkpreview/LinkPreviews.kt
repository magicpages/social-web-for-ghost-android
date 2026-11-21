package co.magicpages.socialweb.data.linkpreview

import android.os.SystemClock
import android.util.LruCache
import co.magicpages.socialweb.data.ghost.parseWebUri
import co.magicpages.socialweb.di.ApplicationScope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * Reads the title, description and image a page offers for being shown as a link (Open Graph,
 * Twitter card, `<title>`). The same link shows in the feed, a thread and the composer, so each
 * address is fetched once at a time and remembered for the session; a page that has nothing to
 * show is remembered too, so scrolling past it doesn't ask again. Anonymous requests: the page's
 * server learns the reader's address, as it would when the link is opened.
 */
@Singleton
class LinkPreviews @Inject constructor(
    private val http: HttpClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    sealed interface Result {
        data class Found(val preview: LinkPreview) : Result

        /** No page, not HTML, nothing to show, or the server didn't answer in time. */
        data object Unavailable : Result
    }

    private class Entry(val result: Result, val expiresAt: Long)

    private val cache = LruCache<String, Entry>(MaxEntries)

    // Fetches run in the application scope, so one that a scrolled-away card started still lands in the cache.
    private val inFlight = HashMap<String, Deferred<Result>>()

    /** What [load] answered for [url] and is still good, or null when it has to be loaded. */
    fun cached(url: String): Result? {
        val entry = cache.get(url) ?: return null
        if (SystemClock.elapsedRealtime() < entry.expiresAt) return entry.result
        cache.remove(url)
        return null
    }

    /** Never throws; every failure is [Result.Unavailable]. */
    suspend fun load(url: String): Result {
        cached(url)?.let { return it }
        if (!isPreviewableUrl(url)) return Result.Unavailable
        val fetch = synchronized(inFlight) {
            inFlight[url] ?: scope.async(Dispatchers.IO) { fetchAndRemember(url) }.also { job ->
                inFlight[url] = job
                job.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(url, job) } }
            }
        }
        return fetch.await()
    }

    private suspend fun fetchAndRemember(url: String): Result {
        val now = SystemClock.elapsedRealtime()
        val (result, expiresAt) = try {
            val preview = fetch(url)
            if (preview != null) Result.Found(preview) to Long.MAX_VALUE else Result.Unavailable to now + AnsweredRetryMillis
        } catch (_: IOException) {
            // Offline or slow: worth another try soon, unlike a page that answered with nothing to show.
            Result.Unavailable to now + FailedRetryMillis
        } catch (_: IllegalArgumentException) {
            Result.Unavailable to now + AnsweredRetryMillis
        }
        cache.put(url, Entry(result, expiresAt))
        return result
    }

    /** Null when the page answered but isn't an HTML page with a title; throws [IOException] when it couldn't be read. */
    private suspend fun fetch(url: String): LinkPreview? =
        http.prepareGet(url) {
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
            timeout {
                connectTimeoutMillis = ConnectTimeoutMillis
                requestTimeoutMillis = RequestTimeoutMillis
            }
        }.execute { response ->
            if (!response.status.isSuccess()) return@execute null
            val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
            val mime = contentType.substringBefore(';').trim().lowercase()
            if (mime.isNotEmpty() && mime != "text/html" && mime != "application/xhtml+xml") return@execute null
            val bytes = response.bodyAsChannel().readUpTo(MaxBytes)
            val html = String(bytes, charsetOf(contentType))
            parseLinkPreview(html, response.call.request.url.toString())
        }

    private companion object {
        /** The metadata sits in the page's `<head>`, near the top; the rest of a long page is never read. */
        const val MaxBytes = 256 * 1024
        const val MaxEntries = 300
        const val ConnectTimeoutMillis = 5_000L
        const val RequestTimeoutMillis = 10_000L
        const val AnsweredRetryMillis = 60 * 60_000L
        const val FailedRetryMillis = 2 * 60_000L
    }
}

/**
 * Whether [url] is a public web page worth asking for a preview: http(s) with a real host name.
 * Addresses of the reader's own network and device are left alone; a post must not be able to
 * make the app poke at them.
 */
fun isPreviewableUrl(url: String): Boolean {
    val host = parseWebUri(url)?.host?.lowercase() ?: return false
    if ('.' !in host || ':' in host) return false
    if (PrivateSuffixes.any { host.endsWith(it) }) return false
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }
    if (octets != null) {
        val (a, b) = octets
        if (a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168)) return false
    }
    return true
}

private val PrivateSuffixes = listOf(".local", ".localhost", ".internal", ".lan", ".home.arpa")

private val charsetParameter = Regex("""charset\s*=\s*"?([\w.:-]+)""", RegexOption.IGNORE_CASE)

/** The charset a Content-Type header names; pages without one are UTF-8 in practice. */
internal fun charsetOf(contentType: String): Charset =
    charsetParameter.find(contentType)?.groupValues?.get(1)
        ?.let { name -> try { Charset.forName(name) } catch (_: IllegalArgumentException) { null } }
        ?: Charsets.UTF_8

/** Reads at most [limit] bytes; the rest of the body is left unread. */
internal suspend fun ByteReadChannel.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val read = readAvailable(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private val typedLink = Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE)
private const val SentencePunctuation = ".,;:!?'\"]}"

/** The first link typed in [text] that [isPreviewableUrl], without the punctuation that ends a sentence around it. */
fun firstLinkIn(text: String): String? =
    typedLink.findAll(text).map { it.value.trimLinkEnd() }.firstOrNull(::isPreviewableUrl)

private fun String.trimLinkEnd(): String {
    var end = length
    while (end > 0) {
        val last = this[end - 1]
        // A closing parenthesis stays when it closes one inside the link, as in Wikipedia's.
        val unmatched = last == ')' && substring(0, end).let { s -> s.count { it == ')' } > s.count { it == '(' } }
        if (last in SentencePunctuation || unmatched) end-- else break
    }
    return substring(0, end)
}
