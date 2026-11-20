package co.magicpages.socialweb.ui.profile

import co.magicpages.socialweb.data.activitypub.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDraftTest {
    private val draft = ProfileDraft(name = "Ann", username = "ann_99", bio = "Hi", avatarUrl = "", bannerUrl = "")

    @Test
    fun `a plain draft is valid`() {
        assertTrue(draft.isValid)
        assertNull(draft.nameError)
        assertNull(draft.usernameError)
        assertNull(draft.bioError)
    }

    @Test
    fun `the display name is required and capped at 64 characters`() {
        assertEquals(ProfileFieldError.NameRequired, draft.copy(name = "").nameError)
        assertEquals(ProfileFieldError.NameRequired, draft.copy(name = "   ").nameError)
        assertNull(draft.copy(name = "a".repeat(64)).nameError)
        assertEquals(ProfileFieldError.NameTooLong, draft.copy(name = "a".repeat(65)).nameError)
        // Spaces around the name aren't sent, so they don't count.
        assertNull(draft.copy(name = " " + "a".repeat(64) + " ").nameError)
    }

    @Test
    fun `the bio is capped at 250 characters and may be empty`() {
        assertNull(draft.copy(bio = "").bioError)
        assertNull(draft.copy(bio = "a".repeat(250)).bioError)
        assertEquals(ProfileFieldError.BioTooLong, draft.copy(bio = "a".repeat(251)).bioError)
    }

    @Test
    fun `the username is two to 99 letters, numbers or underscores`() {
        assertEquals(ProfileFieldError.UsernameTooShort, draft.copy(username = "").usernameError)
        assertEquals(ProfileFieldError.UsernameTooShort, draft.copy(username = "a").usernameError)
        assertNull(draft.copy(username = "ab").usernameError)
        assertNull(draft.copy(username = "a".repeat(99)).usernameError)
        assertEquals(ProfileFieldError.UsernameTooLong, draft.copy(username = "a".repeat(100)).usernameError)
        assertEquals(ProfileFieldError.UsernameInvalid, draft.copy(username = "an.n").usernameError)
        assertEquals(ProfileFieldError.UsernameInvalid, draft.copy(username = "an n").usernameError)
        assertEquals(ProfileFieldError.UsernameInvalid, draft.copy(username = "ann@site").usernameError)
        assertEquals(ProfileFieldError.UsernameInvalid, draft.copy(username = "añn").usernameError)
        assertNull(draft.copy(username = "Ann_Z_09").usernameError)
    }

    @Test
    fun `any invalid field makes the draft invalid`() {
        assertFalse(draft.copy(name = "").isValid)
        assertFalse(draft.copy(username = "!").isValid)
        assertFalse(draft.copy(bio = "a".repeat(300)).isValid)
    }

    @Test
    fun `normalizing trims the text and nothing else`() {
        val messy = ProfileDraft(" Ann ", " ann ", "\n Hi \n", "https://x/a.png", "")
        assertEquals(ProfileDraft("Ann", "ann", "Hi", "https://x/a.png", ""), messy.normalized())
        assertEquals(draft.normalized(), draft.copy(name = "Ann  ").normalized())
    }

    @Test
    fun `the form opens on the profile with its bio as plain text`() {
        val profile = Profile(
            id = "1",
            apId = "https://site.example/.ghost/activitypub/users/index",
            name = "Seaside Notes",
            handle = "@index@site.example",
            avatarUrl = null,
            bio = "<p>First line<br>Second &amp; last</p><p>Next</p>",
            url = "https://site.example/",
            bannerImageUrl = "https://site.example/banner.jpg",
        )
        val form = ProfileDraft.of(profile)
        assertEquals("Seaside Notes", form.name)
        assertEquals("index", form.username)
        assertEquals("", form.avatarUrl)
        assertEquals("https://site.example/banner.jpg", form.bannerUrl)
        assertTrue(form.bio.startsWith("First line"))
        assertTrue(form.bio.contains("Second & last"))
        assertFalse(form.bio.contains("<"))
    }

    @Test
    fun `handles split into username and domain`() {
        assertEquals("ann", usernameOf("@ann@site.example"))
        assertEquals("site.example", domainOf("@ann@site.example"))
        assertEquals("ann", usernameOf("ann"))
        assertEquals("", domainOf("ann"))
        assertEquals("@new@site.example", handleWithUsername("@ann@site.example", " new "))
    }
}
