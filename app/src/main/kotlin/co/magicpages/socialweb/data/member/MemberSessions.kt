package co.magicpages.socialweb.data.member

import android.webkit.CookieManager
import co.magicpages.socialweb.data.ghost.parseWebUri
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** The name of the cookie Ghost sets when a member signs in or confirms a sign-up; it lasts six months. */
const val MemberCookieName = "ghost-members-ssr"

/** The cookies the app's web views hold for a page, which are also where a member session ends. */
interface SiteCookies {
    /** The cookies the browser would send to [url], as a `Cookie` header value: null when there are none. */
    fun header(url: String): String?

    /** Deletes the member session cookies the browser holds for the site at [url]. */
    fun clearMemberSession(url: String)
}

/** Reads and clears the cookies of the in-app browser, which keeps them across launches in the WebView cookie store. */
@Singleton
class WebViewSiteCookies @Inject constructor() : SiteCookies {
    override fun header(url: String): String? = CookieManager.getInstance().getCookie(url)

    override fun clearMemberSession(url: String) {
        val manager = CookieManager.getInstance()
        val uri = parseWebUri(url) ?: return
        val paths = listOfNotNull("/", uri.rawPath.orEmpty().trimEnd('/').takeIf { it.isNotEmpty() })
        // A cookie is only replaced by one with the same name, domain and path, and the browser doesn't say which a
        // cookie has: expire both the host's own and the domain form, at the root and at the site's own path.
        for (name in memberCookieNames(manager.getCookie(url))) {
            for (path in paths) {
                manager.setCookie(url, "$name=; Max-Age=0; Path=$path")
                manager.setCookie(url, "$name=; Max-Age=0; Path=$path; Domain=${uri.host}")
            }
        }
        manager.flush()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MemberModule {
    @Binds
    abstract fun siteCookies(cookies: WebViewSiteCookies): SiteCookies
}

/**
 * The Ghost sites the app is signed in to as a member (a reader or subscriber). The in-app browser
 * ([co.magicpages.socialweb.ui.browser.MemberBrowserActivity]) is where a member signs in; its WebView
 * keeps the session cookie, and this reads it.
 */
@Singleton
class MemberSessions @Inject constructor(private val cookies: SiteCookies) {
    private val _changes = MutableStateFlow(0)

    /** Counts the times the in-app browser closed, after which sessions may have changed: collect to re-check. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun notifyChanged() = _changes.update { it + 1 }

    /** The cookies the browser would send to [url], or null when there are none. */
    suspend fun cookieHeader(url: String): String? = withContext(Dispatchers.IO) { cookies.header(url) }

    /** Whether the in-app browser holds a member session cookie for the site at [url]. */
    suspend fun hasSession(url: String): Boolean = hasMemberCookie(cookieHeader(url))

    /**
     * Forgets the member session of the site at [url] in the app, as ending it in the site's own Portal does. The site
     * keeps the member: signing in again brings the session back. Announces the change like a closed browser does.
     */
    suspend fun signOut(url: String) {
        withContext(Dispatchers.IO) { cookies.clearMemberSession(url) }
        notifyChanged()
    }
}

/** Whether a `Cookie` header value ("a=1; b=2") holds a member session. */
internal fun hasMemberCookie(header: String?): Boolean = header.orEmpty().split(';').any { pair ->
    val name = pair.substringBefore('=').trim()
    name == MemberCookieName && pair.substringAfter('=', "").isNotBlank()
}

/** The names of the member session cookies in a `Cookie` header value: the session itself and its `.sig` signature. */
internal fun memberCookieNames(header: String?): List<String> =
    header.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.startsWith(MemberCookieName) }.distinct()
