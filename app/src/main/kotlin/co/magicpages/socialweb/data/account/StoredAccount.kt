package co.magicpages.socialweb.data.account

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A signed-in Ghost site. Persisted encrypted by [AccountStore]. */
@Serializable
data class StoredAccount(
    val site: GhostSite,
    val credential: Credential,
    val profile: Profile,
    /**
     * The id this account was stored under before its handle changed, so a username change keeps the
     * session and everything stored per account. Null while the handle is the one it signed in with.
     */
    val originalId: String? = null,
) {
    /**
     * Who this account is: its site and its social web handle when it was first stored. Signing in
     * again to the same site as the same user gives the same id, so it replaces the stored account
     * instead of adding one. Stable across sign-ins and username changes, safe in intents and preference keys.
     */
    val id: String
        get() = originalId ?: currentId

    /** The id the account's handle gives it today; differs from [id] after a username change. */
    private val currentId: String
        get() = accountId(site.siteUrl, profile.handle)

    /** Whether [other] is this account signed in again, under either its original or its current handle. */
    fun isSameAs(other: StoredAccount): Boolean = other.id == id || other.id == currentId

    companion object {
        fun accountId(siteUrl: String, handle: String): String =
            "${siteUrl.trim().trimEnd('/').lowercase()}|${handle.trim().lowercase()}"
    }
}

@Serializable
data class GhostSite(
    val title: String,
    /** Public site URL with a trailing slash; the social web API lives under it. */
    val siteUrl: String,
    /** Admin API base with a trailing slash, e.g. `https://admin.example.com/ghost/api/admin/`. */
    val adminApiUrl: String,
    val version: String,
    val iconUrl: String? = null,
    /** The accent colour set in Ghost Admin, as Ghost sends it (e.g. `#ff1a75`). Themes the app for this account. */
    val accentColor: String? = null,
) {
    /** Origin of the admin API; Ghost checks it against session-authenticated requests. */
    val adminOrigin: String
        get() = URI(adminApiUrl).let { "${it.scheme}://${it.rawAuthority}" }
}

@Serializable
sealed interface Credential {
    /** Cookies issued by `POST /session` (and `/session/verify`), sent back verbatim. */
    @Serializable
    @SerialName("session")
    data class Session(val cookies: Map<String, String>) : Credential

    /** A Staff Access Token in Ghost's `{id}:{secret}` format. */
    @Serializable
    @SerialName("staff_token")
    data class StaffToken(val token: String) : Credential
}

/** The site's own fediverse profile, captured at sign-in. */
@Serializable
data class Profile(
    val name: String,
    val handle: String,
    val avatarUrl: String? = null,
)

/**
 * Everything [AccountStore] persists: the signed-in accounts in the order they were added and which
 * of them is active. Changes are plain functions returning the new state, so the rules live here.
 */
@Serializable
data class AccountsFile(
    val accounts: List<StoredAccount> = emptyList(),
    val activeId: String? = null,
    /** The one account of versions before several sites could be signed in; [migrated] moves it into [accounts]. */
    val account: StoredAccount? = null,
) {
    /** The account the app shows, or null when nobody is signed in. */
    val active: StoredAccount?
        get() = accounts.firstOrNull { it.id == activeId }

    /** This state in the current format: the legacy single account becomes the only (active) account. */
    fun migrated(): AccountsFile {
        var accounts = accounts
        var activeId = activeId
        account?.let { legacy ->
            if (accounts.none { it.id == legacy.id }) accounts = accounts + legacy
            if (activeId == null) activeId = legacy.id
        }
        if (accounts.none { it.id == activeId }) activeId = accounts.firstOrNull()?.id
        return AccountsFile(accounts = accounts, activeId = activeId)
    }

    /** Adds [account], or replaces the stored one it signs in again (keeping its place and id), and makes it active. */
    fun withAdded(account: StoredAccount): AccountsFile {
        val index = accounts.indexOfFirst { it.isSameAs(account) }
        if (index < 0) return copy(accounts = accounts + account, activeId = account.id)
        val replaced = account.copy(originalId = accounts[index].id.takeIf { it != account.id })
        return copy(accounts = accounts.toMutableList().also { it[index] = replaced }, activeId = replaced.id)
    }

    /** Makes [id] the active account; unchanged when no stored account has that id. */
    fun withActive(id: String): AccountsFile =
        if (accounts.any { it.id == id }) copy(activeId = id) else this

    /** Forgets [id]. When it was the active account the first remaining one takes over. */
    fun withRemoved(id: String): AccountsFile {
        val next = accounts.filterNot { it.id == id }
        val active = if (activeId == id) next.firstOrNull()?.id else activeId
        return copy(accounts = next, activeId = active)
    }

    /** Records [accentColor] as the site colour of every stored account on [siteUrl]. */
    fun withAccentColor(siteUrl: String, accentColor: String?): AccountsFile =
        copy(accounts = accounts.map { if (it.site.siteUrl == siteUrl) it.copy(site = it.site.copy(accentColor = accentColor)) else it })

    /**
     * Replaces the cached profile of [id] (its name, handle and avatar as the site now has them). A new
     * handle keeps the account's id, so the session and everything stored per account carry on.
     * Unchanged when no stored account has [id].
     */
    fun withProfile(id: String, profile: Profile): AccountsFile {
        val index = accounts.indexOfFirst { it.id == id }
        if (index < 0) return this
        val renamed = accounts[index].copy(profile = profile, originalId = null)
        val updated = if (renamed.id == id) renamed else renamed.copy(originalId = id)
        return copy(accounts = accounts.toMutableList().also { it[index] = updated })
    }
}
