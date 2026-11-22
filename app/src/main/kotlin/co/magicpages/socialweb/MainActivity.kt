package co.magicpages.socialweb

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import co.magicpages.socialweb.background.ShareAgainReminder
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.media.LocalImages
import co.magicpages.socialweb.data.settings.ThemeColors
import co.magicpages.socialweb.ui.AppRoot
import co.magicpages.socialweb.ui.OpenLinkRequests
import co.magicpages.socialweb.ui.home.HomeTab
import co.magicpages.socialweb.ui.home.HomeTabRequests
import co.magicpages.socialweb.ui.quote.SharedContent
import co.magicpages.socialweb.ui.media.LocalVideoPlayback
import co.magicpages.socialweb.ui.media.PictureInPicture
import co.magicpages.socialweb.ui.media.VideoPlayback
import co.magicpages.socialweb.ui.media.VideoViewerOverlay
import co.magicpages.socialweb.ui.quote.SharedContentRequests
import co.magicpages.socialweb.ui.theme.SocialWebTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var accounts: AccountStore
    @Inject lateinit var tabRequests: HomeTabRequests
    @Inject lateinit var sharedContent: SharedContentRequests
    @Inject lateinit var openLinks: OpenLinkRequests
    @Inject lateinit var videoPlayback: VideoPlayback
    @Inject lateinit var themeColors: ThemeColors
    @Inject lateinit var localImages: LocalImages

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation) still holds the launching intent; it was handled already.
        if (savedInstanceState == null) {
            handleTabRequest(intent)
            handleShare(intent)
            handleNewNote(intent)
            handleShareAgain(intent)
        }
        setContent {
            val uriHandler = remember { CustomTabsUriHandler(this) }
            val seed by themeColors.seed.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalUriHandler provides uriHandler, LocalVideoPlayback provides videoPlayback) {
                SocialWebTheme(seed) {
                    // The full-screen video covers the app (also in picture-in-picture, where it is all that shows) without leaving its navigation state.
                    Box(Modifier.fillMaxSize()) {
                        AppRoot()
                        VideoViewerOverlay(videoPlayback)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        videoPlayback.setAppVisible(true)
    }

    // Not onPause: the picture-in-picture window keeps the activity paused but visible, and the video playing.
    override fun onStop() {
        super.onStop()
        videoPlayback.setAppVisible(false)
    }

    // Before Android 12 the window only shrinks into picture-in-picture when asked to as the user leaves.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val player = videoPlayback.viewerPlayer ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && player.isPlaying) PictureInPicture.enter(this, player)
    }

    // A hardware keyboard's Escape goes back, as it does in the app's dialogs and the open search. Only
    // while there is somewhere to go back to inside the app: Escape shouldn't close it.
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && !event.isCanceled && onBackPressedDispatcher.hasEnabledCallbacks()) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // singleTop: a tapped system notification reaches the running activity here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTabRequest(intent)
        handleShare(intent)
        handleNewNote(intent)
        handleShareAgain(intent)
    }

    private fun handleTabRequest(intent: Intent?) {
        val tab = HomeTab.entries.firstOrNull { it.name == intent?.getStringExtra(ExtraOpenTab) } ?: return
        val accountId = intent?.getStringExtra(ExtraOpenAccount)
        if (accountId == null) {
            // Same for the Notifications shortcut: signed out, a request would jump the tab after sign-in.
            lifecycleScope.launch {
                if (accounts.activeId.first() != null) tabRequests.request(tab)
            }
            return
        }
        lifecycleScope.launch {
            // Switch first, so the request is taken by the screens of that account. An account that
            // was signed out meanwhile has nothing to show: the app just opens.
            if (accounts.switchTo(accountId)) tabRequests.request(tab, accountId)
        }
    }

    /**
     * A tapped "share again" reminder: a new note quoting the article, for the account it was set on.
     * That account comes first, so the screens that take the request are its own; one that was signed
     * out meanwhile has nothing to share from, and the app just opens.
     */
    private fun handleShareAgain(intent: Intent?) {
        val reminder = ShareAgainReminder.fromIntent(intent) ?: return
        lifecycleScope.launch {
            if (accounts.switchTo(reminder.accountId)) sharedContent.submit(reminder.toPost())
        }
    }

    /**
     * The "New note" launcher shortcut: an empty composer for the active account, which keeps a draft
     * already begun. Signed out there is no composer, so the app just opens at the sign-in; the request
     * must not wait for the sign-in, or a note would pop open after it.
     */
    private fun handleNewNote(intent: Intent?) {
        if (intent?.action != ActionNewNote) return
        lifecycleScope.launch {
            if (accounts.activeId.first() != null) {
                sharedContent.submit(SharedContent(url = null, quote = null, title = null, plainText = null))
            }
        }
    }

    /**
     * A share from another app: a link, words selected on a page with their link, or one image with
     * or without such text. The image is copied at once, as the sender's grant ends with this activity.
     */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.component?.className == OpenLinkAlias) {
            handleOpenLink(intent)
            return
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = (intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TITLE))?.toString()
        val image = if (intent.type?.startsWith("image/") == true) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            null
        }
        if (image == null) {
            if (intent.type == "text/plain") SharedContent.parse(text, subject)?.let(sharedContent::submit)
            return
        }
        lifecycleScope.launch {
            val words = SharedContent.parse(text, subject) ?: SharedContent(url = null, quote = null, title = null, plainText = null)
            val shared = when (val staged = localImages.stage(image.toString(), ActivityPubClient.MAX_IMAGE_BYTES)) {
                is LocalImages.Staged.Ready -> words.copy(imageUri = staged.uri)
                is LocalImages.Staged.Rejected -> words.copy(imageRejection = staged.reason)
            }
            sharedContent.submit(shared)
        }
    }

    /**
     * The "Open in Social Web" share target: the link in the shared text opens in the app, as a profile or
     * a post's thread. Text without a web link has nothing to open.
     */
    private fun handleOpenLink(intent: Intent) {
        if (intent.type != "text/plain") return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val url = SharedContent.parse(text, null)?.url ?: return
        if (url.startsWith("https://", ignoreCase = true)) openLinks.submit(url)
    }

    companion object {
        /** Intent extra naming the [HomeTab] to show, set by system notifications. */
        const val ExtraOpenTab = "co.magicpages.socialweb.OPEN_TAB"

        /** Intent extra with the id of the account ([co.magicpages.socialweb.data.account.StoredAccount.id]) the tab is shown for; the app switches to it first. */
        const val ExtraOpenAccount = "co.magicpages.socialweb.OPEN_ACCOUNT"

        /** Intent action of the launcher shortcut that opens an empty new note. */
        const val ActionNewNote = "co.magicpages.socialweb.action.NEW_NOTE"

        /** Class name of the manifest's `activity-alias` that lists the app in the share sheet as "Open in Social Web". */
        const val OpenLinkAlias = "co.magicpages.socialweb.OpenLinkAlias"
    }
}

/** Opens links in a Custom Tab so readers stay inside the app. */
private class CustomTabsUriHandler(private val context: Context) : UriHandler {
    override fun openUri(uri: String) {
        // Link targets come from remote servers; only web pages go to the browser.
        val parsed = uri.toUri()
        if (parsed.scheme?.lowercase() !in WebSchemes) return
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, parsed)
        } catch (_: ActivityNotFoundException) {
            // No browser installed; nothing sensible to open the link with.
        }
    }

    private companion object {
        val WebSchemes = setOf("http", "https")
    }
}
