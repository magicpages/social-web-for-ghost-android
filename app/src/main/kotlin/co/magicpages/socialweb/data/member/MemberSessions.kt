package co.magicpages.socialweb.data.member

import android.webkit.CookieManager
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

/** The cookies the app's web views hold for a page: a `Cookie` header value, or null when there are none. */
fun interface SiteCookies {
    fun header(url: String): String?
}

/** Reads the cookies of the in-app browser, which keeps them across launches in the WebView cookie store. */
@Singleton
class WebViewSiteCookies @Inject constructor() : SiteCookies {
    override fun header(url: String): String? = CookieManager.getInstance().getCookie(url)
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
}

/** Whether a `Cookie` header value ("a=1; b=2") holds a member session. */
internal fun hasMemberCookie(header: String?): Boolean = header.orEmpty().split(';').any { pair ->
    val name = pair.substringBefore('=').trim()
    name == MemberCookieName && pair.substringAfter('=', "").isNotBlank()
}
