package co.magicpages.socialweb.data.account

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AccountsFileTest {
    // The configuration the app stores with.
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun account(site: String, handle: String, token: String = "t") = StoredAccount(
        site = GhostSite(title = site, siteUrl = "https://$site/", adminApiUrl = "https://$site/ghost/api/admin/", version = "6.0"),
        credential = Credential.StaffToken(token),
        profile = Profile(name = site, handle = handle),
    )

    private val a = account("a.example.com", "@index@a.example.com")
    private val b = account("b.example.com", "@index@b.example.com")
    private val c = account("c.example.com", "@index@c.example.com")

    private fun AccountsFile.ids() = accounts.map { it.id }

    @Test
    fun `the file of the single account version becomes one active account`() {
        val old = """
            {"account":{"site":{"title":"A","siteUrl":"https://a.example.com/","adminApiUrl":"https://a.example.com/ghost/api/admin/","version":"6.0"},
            "credential":{"type":"session","cookies":{"ghost-admin-api-session":"s"}},
            "profile":{"name":"A","handle":"@index@a.example.com"}}}
        """.trimIndent()

        val file = json.decodeFromString<AccountsFile>(old).migrated()

        assertEquals(1, file.accounts.size)
        assertEquals("@index@a.example.com", file.accounts.single().profile.handle)
        assertEquals(Credential.Session(mapOf("ghost-admin-api-session" to "s")), file.accounts.single().credential)
        assertEquals(file.accounts.single().id, file.activeId)
        assertEquals(file.accounts.single(), file.active)
    }

    @Test
    fun `a migrated file is written in the new format only`() {
        val migrated = AccountsFile(account = a).migrated()

        val written = json.encodeToString(AccountsFile.serializer(), migrated)

        assertFalse(written.contains("\"account\""))
        assertEquals(migrated, json.decodeFromString<AccountsFile>(written).migrated())
    }

    @Test
    fun `an empty file stays empty`() {
        val file = AccountsFile().migrated()

        assertEquals(emptyList<StoredAccount>(), file.accounts)
        assertNull(file.activeId)
        assertNull(file.active)
    }

    @Test
    fun `an active id that matches nothing falls back to the first account`() {
        val file = AccountsFile(accounts = listOf(a, b), activeId = "gone").migrated()

        assertEquals(a.id, file.activeId)
    }

    @Test
    fun `adding makes the new account active and keeps the others`() {
        val file = AccountsFile().withAdded(a).withAdded(b)

        assertEquals(listOf(a.id, b.id), file.ids())
        assertEquals(b, file.active)
    }

    @Test
    fun `adding a stored site and user replaces its credential in place and makes it active`() {
        val renewed = account("a.example.com", "@index@a.example.com", token = "new")

        val file = AccountsFile().withAdded(a).withAdded(b).withAdded(renewed)

        assertEquals(listOf(a.id, b.id), file.ids())
        assertEquals(Credential.StaffToken("new"), file.accounts.first().credential)
        assertEquals(renewed, file.active)
    }

    @Test
    fun `the same site as another user is another account`() {
        val other = account("a.example.com", "@other@a.example.com")

        val file = AccountsFile().withAdded(a).withAdded(other)

        assertEquals(2, file.accounts.size)
        assertEquals(other, file.active)
    }

    @Test
    fun `site address spelling does not make a new account`() {
        val spelled = StoredAccount(
            site = a.site.copy(siteUrl = "HTTPS://A.example.com"),
            credential = Credential.StaffToken("x"),
            profile = a.profile.copy(handle = "@Index@a.example.com"),
        )

        assertEquals(a.id, spelled.id)
    }

    @Test
    fun `switching changes the active account only`() {
        val file = AccountsFile().withAdded(a).withAdded(b).withActive(a.id)

        assertEquals(a, file.active)
        assertEquals(listOf(a.id, b.id), file.ids())
    }

    @Test
    fun `switching to an unknown account changes nothing`() {
        val file = AccountsFile().withAdded(a).withAdded(b)

        assertSame(file, file.withActive("gone"))
    }

    @Test
    fun `removing the active account activates the first remaining one`() {
        val file = AccountsFile().withAdded(a).withAdded(b).withAdded(c).withRemoved(c.id)

        assertEquals(listOf(a.id, b.id), file.ids())
        assertEquals(a, file.active)
    }

    @Test
    fun `removing another account keeps the active one`() {
        val file = AccountsFile().withAdded(a).withAdded(b).withRemoved(a.id)

        assertEquals(listOf(b.id), file.ids())
        assertEquals(b, file.active)
    }

    @Test
    fun `removing the last account leaves nobody signed in`() {
        val file = AccountsFile().withAdded(a).withRemoved(a.id)

        assertEquals(emptyList<StoredAccount>(), file.accounts)
        assertNull(file.activeId)
        assertNull(file.active)
    }
}
