package co.magicpages.socialweb.ui.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.ghost.parseWebUri
import co.magicpages.socialweb.data.member.MemberSessions
import co.magicpages.socialweb.data.settings.ThemeColors
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.theme.SocialWebTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * A Ghost site's Portal (sign in, subscribe, account) in a web view that keeps its cookies, so the member session it
 * ends up with outlives the screen and the app: [MemberSessions] reads it back. Ghost's sign-in emails a code to type
 * here, so the session lands in this web view; the screen closes itself once it holds one (except on the account page).
 * Links to other sites, Stripe's checkout included, open in the browser.
 */
@AndroidEntryPoint
class MemberBrowserActivity : ComponentActivity() {
    @Inject lateinit var sessions: MemberSessions
    @Inject lateinit var themeColors: ThemeColors

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val startUrl = intent.getStringExtra(ExtraUrl)
        val siteUrl = intent.getStringExtra(ExtraSite)
        if (startUrl == null || siteUrl == null || parseWebUri(startUrl) == null || parseWebUri(siteUrl) == null) {
            finish()
            return
        }
        val title = intent.getStringExtra(ExtraTitle) ?: parseWebUri(siteUrl)?.host.orEmpty()
        val autoClose = intent.getBooleanExtra(ExtraAutoClose, true)
        setContent {
            val seed by themeColors.seed.collectAsStateWithLifecycle()
            SocialWebTheme(seed) {
                MemberBrowserScreen(
                    startUrl = startUrl,
                    siteUrl = siteUrl,
                    title = title,
                    autoClose = autoClose,
                    sessions = sessions,
                    onClose = ::finish,
                    openExternally = ::openExternally,
                )
            }
        }
    }

    override fun onStop() {
        // Write the cookies to disk now (the process may be killed any time), and let screens re-check the session.
        CookieManager.getInstance().flush()
        sessions.notifyChanged()
        super.onStop()
    }

    private fun openExternally(uri: Uri) {
        try {
            when (uri.scheme?.lowercase()) {
                "http", "https" -> CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
                else -> startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (_: ActivityNotFoundException) {
            // Nothing on the device can open it.
        }
    }

    companion object {
        const val ExtraUrl = "co.magicpages.socialweb.browser.URL"
        const val ExtraSite = "co.magicpages.socialweb.browser.SITE"
        const val ExtraTitle = "co.magicpages.socialweb.browser.TITLE"
        const val ExtraAutoClose = "co.magicpages.socialweb.browser.AUTO_CLOSE"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberBrowserScreen(
    startUrl: String,
    siteUrl: String,
    title: String,
    autoClose: Boolean,
    sessions: MemberSessions,
    onClose: () -> Unit,
    openExternally: (Uri) -> Unit,
) {
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    // Signing in (or confirming a sign-up) sets the session cookie without the page telling us; poll for it.
    // Already signed in when opened: stay, there is something to look at (the account page).
    LaunchedEffect(siteUrl, autoClose) {
        if (!autoClose || sessions.hasSession(siteUrl)) return@LaunchedEffect
        while (!sessions.hasSession(siteUrl)) delay(SessionPollMillis)
        // Long enough to see Portal's "signed in" state.
        delay(SignedInPauseMillis)
        onClose()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(pageTitle?.takeIf { it.isNotBlank() } ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(AppIcons.Close, contentDescription = stringResource(R.string.browser_close))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        configure()
                        webViewClient = PortalClient(siteUrl, openExternally) { view -> canGoBack = view.canGoBack() }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView, title: String?) {
                                pageTitle = title
                            }
                        }
                        loadUrl(startUrl)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configure() {
    with(settings) {
        // Portal is a script-driven popup.
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        setSupportMultipleWindows(false)
    }
    with(CookieManager.getInstance()) {
        setAcceptCookie(true)
        // The card form and bot checks Portal embeds come from other hosts.
        setAcceptThirdPartyCookies(this@configure, true)
    }
}

/** Keeps the main frame on the member's site; embedded frames (Portal, bot checks, Stripe's card fields) load here. */
private class PortalClient(
    siteUrl: String,
    private val openExternally: (Uri) -> Unit,
    private val onHistoryChanged: (WebView) -> Unit,
) : WebViewClient() {
    private val siteHost = bareHost(parseWebUri(siteUrl)?.host)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val target = request.url
        val external = when (target.scheme?.lowercase()) {
            "http", "https" -> !isOnSite(target.host)
            "mailto", "tel" -> true
            else -> false
        }
        if (external) openExternally(target)
        return external
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = onHistoryChanged(view)

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = onHistoryChanged(view)

    private fun isOnSite(host: String?): Boolean {
        val bare = bareHost(host) ?: return false
        val own = siteHost ?: return false
        return bare == own || bare.endsWith(".$own") || own.endsWith(".$bare")
    }

    private fun bareHost(host: String?): String? = host?.lowercase()?.removePrefix("www.")
}

private const val SessionPollMillis = 1_000L
private const val SignedInPauseMillis = 800L
