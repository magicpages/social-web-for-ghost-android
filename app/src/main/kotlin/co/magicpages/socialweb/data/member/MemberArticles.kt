package co.magicpages.socialweb.data.member

import co.magicpages.socialweb.data.ghost.parseWebUri
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a members-only article looks like for the member who is (or isn't) signed in to its site. */
sealed interface FullArticle {
    /** The post's HTML as the site rendered it for the member. */
    data class Html(val html: String) : FullArticle

    /** The app holds no member session for the site. */
    data object SignedOut : FullArticle

    /** Signed in, but the post couldn't be had: not on the member's tier, the page changed, or the site is down. */
    data object Unavailable : FullArticle
}

/**
 * Loads a members-only article in full: its page, requested with the member cookies the in-app browser holds, which the
 * site renders in full for its member.
 */
@Singleton
class MemberArticles @Inject constructor(
    private val http: HttpClient,
    private val sessions: MemberSessions,
) {
    /** The same client without redirects, which [fetchPage] follows itself. */
    private val unredirected by lazy { http.config { followRedirects = false } }

    /**
     * [pageUrl] is the article's web address, [publicHtml] the public part the social web has of it (what the post
     * is recognised by in the page).
     */
    suspend fun load(pageUrl: String, publicHtml: String): FullArticle {
        if (!pageUrl.startsWith("https://", ignoreCase = true)) return FullArticle.Unavailable
        if (!sessions.hasSession(pageUrl)) return FullArticle.SignedOut
        val page = try {
            fetchPage(pageUrl)
        } catch (_: IOException) {
            null
        } ?: return FullArticle.Unavailable
        val extraction = withContext(Dispatchers.Default) { MemberContent.extract(page, publicHtml) }
        return (extraction as? MemberContent.Extraction.Full)?.let { FullArticle.Html(it.html) } ?: FullArticle.Unavailable
    }

    /**
     * The page's HTML, or null when it isn't served. Redirects within the site are followed by hand: the member cookie
     * must never travel to another host.
     */
    private suspend fun fetchPage(pageUrl: String): String? {
        var url = pageUrl
        repeat(MaxRedirects + 1) {
            val cookie = sessions.cookieHeader(url) ?: return null
            val response = unredirected.get(url) {
                header(HttpHeaders.Cookie, cookie)
                header(HttpHeaders.Accept, "text/html")
            }
            when (response.status) {
                HttpStatusCode.OK -> return response.bodyAsText()
                HttpStatusCode.MovedPermanently, HttpStatusCode.Found, HttpStatusCode.SeeOther,
                HttpStatusCode.TemporaryRedirect, HttpStatusCode.PermanentRedirect,
                -> {
                    val target = response.headers[HttpHeaders.Location]?.let { resolve(url, it) } ?: return null
                    if (!sameHost(url, target) || !target.startsWith("https://", ignoreCase = true)) return null
                    url = target
                }
                else -> return null
            }
        }
        return null
    }

    private fun resolve(base: String, location: String): String? = try {
        URI(base).resolve(location).toString()
    } catch (_: java.net.URISyntaxException) {
        null
    }

    private fun sameHost(a: String, b: String): Boolean {
        fun host(url: String) = parseWebUri(url)?.host?.lowercase()?.removePrefix("www.")
        return host(a) != null && host(a) == host(b)
    }

    private companion object {
        const val MaxRedirects = 3
    }
}
