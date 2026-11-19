package co.magicpages.socialweb.data.ghost

import java.net.URI

/** Ghost serves its social web accounts under this path of the site, whatever username the site picked. */
private const val GhostActorPath = "/.ghost/activitypub/"

/**
 * The site (with a trailing slash) a Ghost account belongs to, read from the account's ActivityPub
 * [actorId]. Null for accounts on other software, which is also how the app tells the two apart.
 */
fun ghostSiteUrl(actorId: String?): String? {
    val id = actorId?.trim().orEmpty()
    val index = id.indexOf(GhostActorPath)
    if (index <= 0 || !id.startsWith("https://")) return null
    return id.substring(0, index) + "/"
}

/**
 * Whether [url] is a Ghost ActivityPub ID (`…/.ghost/activitypub/note/<uuid>` and the like). Ghost
 * publishes no web page for its notes or profiles: those URLs serve JSON, even to browsers.
 */
fun isGhostActivityPubUrl(url: String?): Boolean = ghostSiteUrl(url) != null

private val FullHandle = Regex("""@?([A-Za-z0-9_.-]+)@([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)""")

/**
 * fedi.pub's page for a Ghost account (`https://fedi.pub/@user@domain`): its profile and latest
 * notes, read from the site's ActivityPub API. It only serves Ghost accounts. Null unless [handle]
 * is a full `@user@domain`.
 */
fun fediPubUrl(handle: String): String? {
    val match = FullHandle.matchEntire(handle.trim()) ?: return null
    val (user, domain) = match.destructured
    return "https://fedi.pub/@$user@${domain.lowercase()}"
}

private val GhostNoteId = Regex("""/\.ghost/activitypub/note/([A-Za-z0-9-]+)/?""")

/**
 * fedi.pub's page for one Ghost note (`https://fedi.pub/@user@domain/<uuid>/`), from the note's
 * ActivityPub [noteId] (`…/.ghost/activitypub/note/<uuid>`) and its author's [handle]. Null for
 * articles, notes on other software, and a handle that isn't a full `@user@domain`.
 */
fun fediPubNoteUrl(noteId: String, handle: String): String? {
    val id = noteId.trim()
    if (ghostSiteUrl(id) == null) return null
    val uuid = GhostNoteId.find(id)?.takeIf { it.range.last == id.lastIndex }?.groupValues?.get(1) ?: return null
    val profile = fediPubUrl(handle) ?: return null
    return "$profile/$uuid/"
}

/** The address without scheme and `www.`, trailing slash dropped: `example.com`, `example.com/blog`. */
fun displayAddress(url: String): String {
    val uri = parseWebUri(url) ?: return url
    val host = uri.host.lowercase().removePrefix("www.")
    val path = uri.rawPath.orEmpty().trim('/')
    return if (path.isEmpty()) host else "$host/$path"
}

/**
 * Whether two addresses are the same site: the same host (with or without `www.`) and path, ignoring
 * scheme, case and a trailing slash. `example.com/blog` and `example.com` are different sites.
 */
fun isSameSite(a: String, b: String): Boolean {
    val keyA = siteKey(a) ?: return false
    return keyA == siteKey(b)
}

private fun siteKey(url: String): String? {
    val uri = parseWebUri(url) ?: return null
    return uri.host.lowercase().removePrefix("www.") + "/" + uri.rawPath.orEmpty().trim('/').lowercase()
}

/** [url] as a URI with a host, or null when it isn't one (bare words, `mailto:`). */
internal fun parseWebUri(url: String): URI? {
    val uri = try {
        URI(url.trim())
    } catch (_: java.net.URISyntaxException) {
        return null
    }
    return uri.takeIf { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrEmpty() }
}
