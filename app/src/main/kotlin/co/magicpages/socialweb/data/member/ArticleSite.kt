package co.magicpages.socialweb.data.member

import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.ghost.ghostSiteUrl
import co.magicpages.socialweb.data.ghost.parseWebUri

/**
 * The Ghost site (with a trailing slash) that published this article: the part of its ActivityPub ID before
 * `/.ghost/activitypub/`, which is also the subdirectory of a site that lives in one. An article with Ghost's
 * members-only notice comes from a Ghost site whatever its ID says (one served by another instance keeps the
 * notice), so its page's host is the site then. Null for articles from anywhere else.
 */
val Post.articleSiteUrl: String?
    get() {
        ghostSiteUrl(id)?.let { return it }
        if (!MemberContent.isPaywalled(content)) return null
        val page = parseWebUri(url)?.takeIf { it.scheme.equals("https", ignoreCase = true) } ?: return null
        return "https://${page.host.lowercase()}/"
    }
