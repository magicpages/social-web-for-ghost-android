package co.magicpages.socialweb.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AccountsFileProfileTest {
    private fun account(site: String, handle: String) = StoredAccount(
        site = GhostSite(title = site, siteUrl = "https://$site/", adminApiUrl = "https://$site/ghost/api/admin/", version = "6.0"),
        credential = Credential.StaffToken("t"),
        profile = Profile(name = site, handle = handle),
    )

    private val a = account("a.example.com", "@index@a.example.com")
    private val b = account("b.example.com", "@index@b.example.com")

    @Test
    fun `an edited profile replaces the stored one in place`() {
        val file = AccountsFile(accounts = listOf(a, b), activeId = b.id)
        val next = file.withProfile(a.id, Profile(name = "New name", handle = "@index@a.example.com", avatarUrl = "https://a.example.com/me.png"))
        assertEquals(listOf(a.id, b.id), next.accounts.map { it.id })
        assertEquals("New name", next.accounts[0].profile.name)
        assertEquals("https://a.example.com/me.png", next.accounts[0].profile.avatarUrl)
        assertEquals(a.credential, next.accounts[0].credential)
        assertEquals(b.id, next.activeId)
    }

    @Test
    fun `a new handle keeps the account's id, so it stays active`() {
        val file = AccountsFile(accounts = listOf(a, b), activeId = a.id)
        val next = file.withProfile(a.id, Profile(name = "a.example.com", handle = "@renamed@a.example.com"))
        assertEquals(listOf(a.id, b.id), next.accounts.map { it.id })
        assertEquals("@renamed@a.example.com", next.active?.profile?.handle)
    }

    @Test
    fun `renaming twice and back keeps the first id`() {
        val file = AccountsFile(accounts = listOf(a), activeId = a.id)
        val renamed = file
            .withProfile(a.id, Profile(name = "a", handle = "@one@a.example.com"))
            .withProfile(a.id, Profile(name = "a", handle = "@two@a.example.com"))
        assertEquals(a.id, renamed.accounts.single().id)
        val back = renamed.withProfile(a.id, Profile(name = "a", handle = "@index@a.example.com"))
        assertEquals(a, back.accounts.single().copy(profile = a.profile))
        assertEquals(null, back.accounts.single().originalId)
    }

    @Test
    fun `signing in again after a rename replaces the account instead of adding one`() {
        val file = AccountsFile(accounts = listOf(a, b), activeId = b.id)
            .withProfile(a.id, Profile(name = "a.example.com", handle = "@renamed@a.example.com"))
        val again = account("a.example.com", "@renamed@a.example.com").copy(credential = Credential.StaffToken("fresh"))
        val next = file.withAdded(again)
        assertEquals(listOf(a.id, b.id), next.accounts.map { it.id })
        assertEquals(Credential.StaffToken("fresh"), next.accounts[0].credential)
        assertEquals(a.id, next.activeId)
    }

    @Test
    fun `an unknown account changes nothing`() {
        val file = AccountsFile(accounts = listOf(a), activeId = a.id)
        assertSame(file, file.withProfile(b.id, Profile(name = "x", handle = "@x@b.example.com")))
    }
}
