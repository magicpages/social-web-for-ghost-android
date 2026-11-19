package co.magicpages.socialweb.data.member

import co.magicpages.socialweb.data.ghost.resolveUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Asks a Ghost site whether the app's member session for it is a live one. */
@Singleton
class MemberClient @Inject constructor(
    private val http: HttpClient,
    private val sessions: MemberSessions,
) {
    /**
     * Whether the in-app browser is signed in to the site at [siteUrl] as a member: it holds the session cookie
     * and `members/api/member/` confirms it.
     */
    suspend fun isSubscribed(siteUrl: String): Boolean {
        val cookie = sessions.cookieHeader(siteUrl)
        if (!hasMemberCookie(cookie)) return false
        val endpoint = resolveUrl("members/api/member/", siteUrl) ?: return false
        val status = try {
            http.get(endpoint) { header(HttpHeaders.Cookie, cookie) }.status.value
        } catch (_: IOException) {
            null
        }
        return isMember(hasCookie = true, memberApiStatus = status)
    }
}

/**
 * The decision behind [MemberClient.isSubscribed]. `members/api/member/` answers 200 with the member when the
 * session is live and 204 (or 401/404) when it isn't. When the site can't be asked, or fails, the cookie it set
 * is all there is to go by.
 */
internal fun isMember(hasCookie: Boolean, memberApiStatus: Int?): Boolean = when {
    !hasCookie -> false
    memberApiStatus == null || memberApiStatus >= 500 -> true
    else -> memberApiStatus == 200
}
