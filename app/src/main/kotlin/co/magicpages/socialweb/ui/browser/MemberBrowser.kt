package co.magicpages.socialweb.ui.browser

import android.app.Activity
import android.content.Context
import android.content.Intent

/** A page of a Ghost site's Portal, the members popup every Ghost theme opens from a `#/portal/…` address. */
enum class PortalPage(val path: String) {
    SignIn("signin"),
    SignUp("signup"),
    Account("account"),
}

/** The address of [page] on the site at [siteUrl]. */
fun portalUrl(siteUrl: String, page: PortalPage): String =
    siteUrl.substringBefore('#').substringBefore('?').let { if (it.endsWith('/')) it else "$it/" } + "#/portal/${page.path}"

/**
 * Opens a Ghost site's Portal in the in-app browser, where a reader signs in or becomes a member. Its web view keeps the
 * member session across launches, which is what lets profiles show "Member" and the reader show members-only posts in full.
 *
 * [siteName] titles the screen until the page has a title of its own.
 */
fun openPortal(context: Context, siteUrl: String, page: PortalPage, siteName: String? = null) {
    val intent = Intent(context, MemberBrowserActivity::class.java)
        .putExtra(MemberBrowserActivity.ExtraUrl, portalUrl(siteUrl, page))
        .putExtra(MemberBrowserActivity.ExtraSite, siteUrl)
        .putExtra(MemberBrowserActivity.ExtraTitle, siteName)
        .putExtra(MemberBrowserActivity.ExtraAutoClose, page != PortalPage.Account)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
