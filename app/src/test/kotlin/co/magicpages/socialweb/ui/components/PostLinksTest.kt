package co.magicpages.socialweb.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostLinksTest {
    private fun assertPosts(vararg urls: String) = urls.forEach { assertTrue("should match: $it", PostLinks.matches(it)) }

    private fun assertNotPosts(vararg urls: String) = urls.forEach { assertFalse("should not match: $it", PostLinks.matches(it)) }

    @Test
    fun `Mastodon post pages and ids match`() = assertPosts(
        "https://mastodon.social/@alice/112233445566778899",
        "https://mastodon.social/@alice/112233445566778899/",
        "https://mastodon.social/@alice@other.example/112233445566778899",
        "https://mastodon.social/web/@alice/112233445566778899",
        "https://mastodon.social/web/statuses/112233445566778899",
        "https://mastodon.social/users/alice/statuses/112233445566778899",
    )

    @Test
    fun `GoToSocial Pixelfed and Misskey addresses match`() = assertPosts(
        "https://gts.example/@bob/statuses/01H9ZQ4V5Y3K8X7N2M6R1T0WEB",
        "https://gts.example/users/bob/statuses/01H9ZQ4V5Y3K8X7N2M6R1T0WEB",
        "https://pixelfed.social/p/carol/598123456789012345",
        "https://misskey.io/notes/9abcdefghi",
        "https://sharkey.example/notes/a1b2c3d4e5f6g7h8",
    )

    @Test
    fun `Pleroma and Akkoma addresses match`() = assertPosts(
        "https://pleroma.example/notice/AZ0b1C2d3E4f5G6h7I",
        "https://pleroma.example/objects/0f8fad5b-d9cb-469f-a165-70867728950e",
    )

    @Test
    fun `Ghost notes and articles match by their ActivityPub id`() = assertPosts(
        "https://index.example/.ghost/activitypub/note/0f8fad5b-d9cb-469f-a165-70867728950e",
        "https://index.example/.ghost/activitypub/article/0f8fad5b-d9cb-469f-a165-70867728950e",
    )

    @Test
    fun `profiles and other pages of these servers are not posts`() = assertNotPosts(
        "https://mastodon.social/@alice",
        "https://mastodon.social/@alice/",
        "https://mastodon.social/users/alice",
        "https://mastodon.social/@alice/following",
        "https://mastodon.social/@alice/with_replies",
        "https://mastodon.social/tags/ghost",
        "https://index.example/.ghost/activitypub/users/index",
        "https://index.example/.ghost/activitypub/inbox/index",
    )

    @Test
    fun `ordinary pages are not posts`() = assertNotPosts(
        "https://example.com/",
        "https://example.com/2024/05/a-long-article/",
        "https://example.com/notes/my-thoughts-on-things",
        "https://example.com/notes/short",
        "https://example.com/p/some-page",
        "https://medium.com/@writer/how-to-write-8a3b2c1d0e9f",
        "https://www.youtube.com/@channel/videos",
        "https://example.com/objects/not-a-uuid",
    )

    @Test
    fun `query strings and fragments are not matched`() = assertNotPosts(
        "https://mastodon.social/@alice/112233445566778899?utm_source=feed",
        "https://mastodon.social/@alice/112233445566778899#replies",
        "https://pleroma.example/notice/AZ0b1C2d3E4f5G6h7I?foo=bar",
    )

    @Test
    fun `only https web addresses are matched`() = assertNotPosts(
        "http://mastodon.social/@alice/112233445566778899",
        "ftp://mastodon.social/@alice/112233445566778899",
        "mailto:alice@mastodon.social",
        "https://user:pass@mastodon.social/@alice/112233445566778899",
        "@alice/112233445566778899",
        "",
        "not a url",
    )

    @Test
    fun `the handle comes from a profile address`() {
        assertEquals("@alice@mastodon.social", ProfileLinks.handleOf("https://mastodon.social/@alice"))
        assertEquals("@bob_99@pixel.example", ProfileLinks.handleOf("https://pixel.example/users/bob_99/"))
        assertNull(ProfileLinks.handleOf("https://mastodon.social/@alice/112233445566778899"))
        assertNull(ProfileLinks.handleOf("https://example.com/blog"))
    }
}
