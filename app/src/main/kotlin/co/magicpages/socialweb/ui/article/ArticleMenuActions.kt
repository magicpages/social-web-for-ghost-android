package co.magicpages.socialweb.ui.article

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.ghost.displayAddress
import co.magicpages.socialweb.data.member.MemberSessions
import co.magicpages.socialweb.data.member.articleSiteUrl
import co.magicpages.socialweb.ui.browser.PortalPage
import co.magicpages.socialweb.ui.browser.openPortal
import co.magicpages.socialweb.ui.components.ActionSheetScope
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.SheetAction
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The member session of an article's site, for the article menu's sign in and sign out. */
@HiltViewModel
class ArticleSiteViewModel @Inject constructor(private val sessions: MemberSessions) : ViewModel() {
    /** Whether the app holds a member session for [siteUrl], checked again when the in-app browser closes and after [signOut]. */
    fun signedIn(siteUrl: String): Flow<Boolean> = sessions.changes.map { sessions.hasSession(siteUrl) }.distinctUntilChanged()

    fun signOut(siteUrl: String) {
        viewModelScope.launch { sessions.signOut(siteUrl) }
    }
}

/** The Ghost site an article comes from, which its menu signs in to or out of. */
class ArticleSite(val url: String, val name: String, val signedIn: Boolean, val signOut: () -> Unit)

/**
 * The site of [post] with whether the app is signed in to it as a member, or null for an article from anywhere but a
 * Ghost site. Collected by the screen rather than the menu, so the menu opens with the right entry.
 */
@Composable
fun rememberArticleSite(post: Post?): ArticleSite? {
    val model = hiltViewModel<ArticleSiteViewModel>()
    val siteUrl = remember(post) { post?.articleSiteUrl }
    val signedIn by remember(model, siteUrl) { siteUrl?.let(model::signedIn) ?: flowOf(false) }.collectAsStateWithLifecycle(false)
    return remember(model, siteUrl, signedIn) {
        siteUrl?.let { ArticleSite(it, displayAddress(it), signedIn) { model.signOut(it) } }
    }
}

/**
 * Sign in to the article's site in the in-app browser, or out of it: as the site's profile does, a member session is
 * what shows the full post here. Once the browser closes signed in, the reader loads the whole article.
 */
@Composable
fun ActionSheetScope.ArticleSiteAction(site: ArticleSite?) {
    if (site == null) return
    val context = LocalContext.current
    if (site.signedIn) {
        SheetAction(stringResource(R.string.article_sign_out_of, site.name), AppIcons.Logout, onClick = site.signOut)
    } else {
        SheetAction(stringResource(R.string.article_sign_in_to, site.name), AppIcons.Login) {
            openPortal(context, site.url, PortalPage.SignIn, site.name)
        }
    }
}

/**
 * The reader view's switch for the site of [post], an article. While the site's reader view is hidden (its articles
 * open as their page in the browser) this is "Show reader", in any menu. "Hide reader" is only offered where [onHide]
 * says what happens to the screen the reader is on: it gets the page's address, once the choice is saved. Articles
 * without a page of their own have no reader view to hide.
 */
@Composable
fun ActionSheetScope.ReaderViewAction(post: Post, onHide: ((page: String) -> Unit)? = null) {
    val sites = LocalReaderSites.current ?: return
    val page = post.webUrl?.takeIf { post.isArticle } ?: return
    if (sites.isHidden(page)) {
        SheetAction(stringResource(R.string.reader_show), AppIcons.Reader) { sites.setHidden(page, false) }
    } else if (onHide != null) {
        SheetAction(stringResource(R.string.reader_hide), AppIcons.Reader) {
            sites.setHidden(page, true)
            onHide(page)
        }
    }
}
