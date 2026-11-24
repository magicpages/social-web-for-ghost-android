package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.ghost.GhostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable

/** The site's handle with its custom domain, as `GET domain` and `PUT domain` answer it. */
@Serializable
data class SocialWebDomain(
    /** The custom domain of the handle; null while the handle uses the site's own host. */
    val domain: String? = null,
    /** `@username@domain`, as it reads with [domain] applied. */
    val handle: String = "",
    /** The account's actor URL, whose host is where the redirect has to point. */
    val actorUrl: String = "",
) {
    /** The host the account lives on, the one a custom domain redirects to. */
    val actorHost: String
        get() = actorUrl.substringAfter("://", "").substringBefore('/').ifBlank { handle.substringAfterLast('@') }

    /** The part of the handle before the domain. */
    val username: String get() = handle.removePrefix("@").substringBefore('@')

    /** The part of the handle after the username. */
    val handleDomain: String get() = handle.substringAfterLast('@', "")
}

/** An account that moved, or is moving, to this one; [apId] is its actor URL. */
@Serializable
data class AccountAlias(val apId: String) {
    /** `user@host` for a Mastodon-style actor URL, else host and path, as Ghost Admin names them. */
    val display: String
        get() {
            val rest = apId.substringAfter("://", apId)
            val host = rest.substringBefore('/')
            val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#').trimEnd('/')
            val user = path.removePrefix("users/").takeIf { path.startsWith("users/") && "/" !in it }
            return when {
                user != null -> "$user@$host"
                path.isEmpty() -> host
                else -> "$host/$path"
            }
        }
}

/** `GET aliases` and its POST and DELETE answers: the accounts that moved to this one. */
@Serializable
data class AccountAliases(val aliases: List<AccountAlias> = emptyList())

/** Where moving the followers to another account stands, `GET migration` and `POST migration`. */
@Serializable
data class AccountMigration(
    /** The destination's actor URL; null until a move has been started. */
    val targetApId: String? = null,
    val targetHandle: String? = null,
    /** Whether the followers' servers have been told. */
    val sent: Boolean = false,
)

/**
 * The identity tools of the signed-in account [accountId]'s site: the custom domain of the handle,
 * aliases, and moving the followers away. Every call throws [GhostException] or [SocialWebException]
 * when the service doesn't do it. The service lets Owners and Administrators use the domain and
 * aliases, and only the Owner move the followers; it answers 403 to anyone else.
 */
@Singleton
class AccountIdentity @Inject constructor(
    private val accounts: AccountStore,
    private val client: ActivityPubClient,
) {
    /** The site's handle and custom domain. */
    suspend fun domain(accountId: String): SocialWebDomain = with(account(accountId)) { client.domain(site, credential) }

    /** Checks a domain's redirect without saving it; Ghost Admin calls this "Validate". */
    suspend fun validateDomain(accountId: String, domain: String): SocialWebDomain =
        with(account(accountId)) { client.validateDomain(site, credential, domain) }

    /** Saves [domain], or goes back to the site's own host for null. */
    suspend fun setDomain(accountId: String, domain: String?): SocialWebDomain =
        with(account(accountId)) { client.setDomain(site, credential, domain) }

    /** The accounts that moved, or are moving, to this one. */
    suspend fun aliases(accountId: String): List<AccountAlias> = with(account(accountId)) { client.aliases(site, credential) }

    /** Adds [handle] as an alias; the answers are the aliases as they now are. */
    suspend fun addAlias(accountId: String, handle: String): List<AccountAlias> =
        with(account(accountId)) { client.addAlias(site, credential, handle) }

    /** Takes the alias out again, for a move that wasn't started. */
    suspend fun removeAlias(accountId: String, alias: AccountAlias): List<AccountAlias> =
        with(account(accountId)) { client.removeAlias(site, credential, alias.apId) }

    /** Where a move of the followers to another account stands; only the Owner may ask. */
    suspend fun migration(accountId: String): AccountMigration = with(account(accountId)) { client.migration(site, credential) }

    /** Tells the followers' servers to follow [targetHandle] instead; this can't be taken back. */
    suspend fun moveAccount(accountId: String, targetHandle: String): AccountMigration =
        with(account(accountId)) { client.moveAccount(site, credential, targetHandle) }

    private suspend fun account(id: String): StoredAccount = accounts.signedIn(id) ?: throw GhostException.Unauthorized(null)
}

/**
 * The host of what the user typed for the custom domain: a bare domain or a URL, without its scheme,
 * path, user info or a leading `www.`, as the service normalises it. Null when nothing is left.
 */
fun normalizeDomainInput(value: String): String? {
    val withoutScheme = value.trim().replace(Regex("^[a-zA-Z][a-zA-Z\\d+\\-.]*://"), "")
    val authority = withoutScheme.split('/', '?', '#').first().substringAfterLast('@')
    return authority.lowercase().removePrefix("www.").ifBlank { null }
}

/** `@user@domain` when [value] looks like a handle, with or without the leading `@`; null otherwise. */
fun normalizeHandleInput(value: String): String? {
    val trimmed = value.trim()
    if (!HandlePattern.matches(trimmed)) return null
    return if (trimmed.startsWith("@")) trimmed else "@$trimmed"
}

/** Whether two handles are the same account, whatever their case and leading `@`. */
fun sameHandle(a: String, b: String): Boolean = a.trim().removePrefix("@").equals(b.trim().removePrefix("@"), ignoreCase = true)

private val HandlePattern = Regex("^@?[^@\\s]+@[^@\\s]+$")
