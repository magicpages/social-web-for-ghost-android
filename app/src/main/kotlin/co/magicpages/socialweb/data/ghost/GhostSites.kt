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

/** A fedi.pub page the app shows itself, read by [parseFediPubLink]. */
sealed interface FediPubLink {
    /** The account's `@user@domain` handle, its domain lowercased. */
    val handle: String

    /** `https://fedi.pub/@user@domain`: the account's profile. */
    data class Profile(override val handle: String) : FediPubLink

    /** `https://fedi.pub/@user@domain/<id>/`: a note, [noteId] being the last part of its ActivityPub ID. */
    data class Note(override val handle: String, val noteId: String) : FediPubLink
}

private val FediPubPath = Regex("""/@([A-Za-z0-9_.-]+)@([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)(?:/([A-Za-z0-9-]+))?/?""")

/**
 * The profile or note a fedi.pub link opens ([fediPubUrl], [fediPubNoteUrl]), or null for every other
 * page there (the home page, the link generator) and for other sites. The path is read decoded, so
 * `%40` counts as `@`, as it does for the manifest's intent filter; a query or fragment (a tracking
 * tag) doesn't change what the link is.
 */
fun parseFediPubLink(url: String): FediPubLink? {
    val uri = parseWebUri(url) ?: return null
    if (uri.scheme.lowercase() != "https" || uri.host.lowercase() != "fedi.pub" || uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) return null
    // An encoded slash would split into segments only once decoded.
    if (uri.rawPath.orEmpty().contains("%2f", ignoreCase = true)) return null
    val match = FediPubPath.matchEntire(uri.path.orEmpty()) ?: return null
    val (user, domain, noteId) = match.destructured
    val handle = "@$user@${domain.lowercase()}"
    return if (noteId.isEmpty()) FediPubLink.Profile(handle) else FediPubLink.Note(handle, noteId)
}

/**
 * A Ghost note's ActivityPub ID from its author's [actorId] and the last part of its own ID. Ghost
 * serves both under the same site, which may be on another host than the handle's domain
 * (`@jannis@jannis.io` is `https://www.jannis.io/.ghost/activitypub/users/index`) and may sit in a
 * subdirectory. Null when [actorId] isn't a Ghost actor: fedi.pub only serves Ghost accounts.
 */
fun ghostNoteId(actorId: String, noteId: String): String? =
    ghostSiteUrl(actorId)?.let { "${it}.ghost/activitypub/note/$noteId" }

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
