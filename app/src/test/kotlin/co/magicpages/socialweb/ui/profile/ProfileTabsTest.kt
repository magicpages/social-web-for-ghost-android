package co.magicpages.socialweb.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileTabsTest {
    @Test
    fun `replies are offered only for accounts whose server lists them`() {
        assertEquals(
            listOf(ProfileTab.Posts, ProfileTab.Replies),
            profileTabs(isMe = false, apId = "https://mastodon.example/users/ann"),
        )
        // Ghost sites leave replies out of their outbox, so the tab would always be empty.
        assertEquals(
            listOf(ProfileTab.Posts),
            profileTabs(isMe = false, apId = "https://site.example/.ghost/activitypub/users/index"),
        )
        assertEquals(listOf(ProfileTab.Posts), profileTabs(isMe = false, apId = ""))
    }

    @Test
    fun `the site's own account gets its likes instead`() {
        assertEquals(
            listOf(ProfileTab.Posts, ProfileTab.Likes),
            profileTabs(isMe = true, apId = "https://site.example/.ghost/activitypub/users/index"),
        )
    }
}
