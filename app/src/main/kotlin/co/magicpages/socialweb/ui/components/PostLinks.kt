package co.magicpages.socialweb.ui.components

import co.magicpages.socialweb.data.ghost.parseWebUri

/**
 * Recognises the web addresses fediverse servers give their posts, so a tap on one can open the post
 * in the app. Only the address's shape is checked: whether it is a post the service can show is found
 * out by asking it, and an address that turns out not to be one goes to the browser.
 */
object PostLinks {
    private const val User = """@[A-Za-z0-9_.-]+(?:@[A-Za-z0-9.-]+)?"""
    private const val Name = """[A-Za-z0-9_.-]+"""
    private const val Uuid = """[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"""

    private val shapes = listOf(
        // Mastodon and its forks: the web page, in the web app too, and the ActivityPub id. GoToSocial's ids end in a ULID, not digits.
        Regex("""/(?:web/)?$User/\d+/?"""),
        Regex("""/web/statuses/\d+/?"""),
        Regex("""/users/$Name/statuses/[A-Za-z0-9]+/?"""),
        // GoToSocial.
        Regex("""/$User/statuses/[A-Za-z0-9]+/?"""),
        // Pixelfed.
        Regex("""/p/$Name/\d+/?"""),
        // Misskey and its forks: ids are 8 to 32 letters and digits, which keeps `/notes/some-title` pages out.
        Regex("""/notes/[A-Za-z0-9]{8,32}/?"""),
        // Pleroma and Akkoma.
        Regex("""/notice/[A-Za-z0-9]+/?"""),
        Regex("""/objects/$Uuid/?"""),
        // Ghost's notes and articles, which are addressed by their ActivityPub id.
        Regex("""/\.ghost/activitypub/(?:note|article)/[A-Za-z0-9-]+/?"""),
    )

    /** Whether [url] is an https address shaped like a post. Query strings and fragments mean it isn't one of these. */
    fun matches(url: String): Boolean {
        val uri = parseWebUri(url) ?: return false
        if (uri.scheme.lowercase() != "https" || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return false
        val path = uri.rawPath.orEmpty()
        return shapes.any { it.matches(path) }
    }
}
