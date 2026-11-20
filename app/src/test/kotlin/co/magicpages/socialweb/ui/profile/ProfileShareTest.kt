package co.magicpages.socialweb.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileShareTest {
    private val ghostId = "https://site.example/.ghost/activitypub/users/index"

    @Test
    fun `a Ghost account is shared as its fedi pub page, not its home page`() {
        assertEquals(
            "https://fedi.pub/@index@site.example",
            profileShareUrl("@index@site.example", ghostId, url = "https://site.example/", fallbackUrl = "https://elsewhere.example/"),
        )
    }

    @Test
    fun `other accounts are shared as the page their server reports`() {
        assertEquals(
            "https://mastodon.example/@ann",
            profileShareUrl("@ann@mastodon.example", "https://mastodon.example/users/ann", url = "https://mastodon.example/@ann", fallbackUrl = null),
        )
    }

    @Test
    fun `without a page the actor id or the link the profile was opened from is used`() {
        assertEquals(
            "https://mastodon.example/users/ann",
            profileShareUrl("@ann@mastodon.example", "https://mastodon.example/users/ann", url = null, fallbackUrl = "https://x.example/"),
        )
        assertEquals(
            "https://x.example/",
            profileShareUrl("@ann@mastodon.example", "", url = " ", fallbackUrl = "https://x.example/"),
        )
        assertNull(profileShareUrl("@ann@mastodon.example", "", url = null, fallbackUrl = null))
    }

    @Test
    fun `a wide picture is cut to a centred square`() {
        assertEquals(SquareCrop(left = 100, top = 0, side = 200), centredSquare(400, 200))
        assertEquals(SquareCrop(left = 0, top = 50, side = 100), centredSquare(100, 200))
        assertEquals(SquareCrop(left = 0, top = 0, side = 64), centredSquare(64, 64))
    }

    @Test
    fun `an odd leftover leaves the extra pixel on the far side`() {
        assertEquals(SquareCrop(left = 1, top = 0, side = 10), centredSquare(13, 10))
    }

    @Test
    fun `a picture without area has no square`() {
        assertNull(centredSquare(0, 10))
        assertNull(centredSquare(10, -1))
    }
}
