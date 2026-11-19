package co.magicpages.socialweb.data.activitypub

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshAvatarsTest {
    private val stale = "https://cdn.example/old.png"
    private val fresh = "https://cdn.example/new.png"

    private fun author(id: String, handle: String, avatar: String? = stale) =
        Author(id = id, handle = handle, name = handle, avatarUrl = avatar)

    private fun post(author: Author, repostedBy: Author? = null) =
        Post(id = "post-${author.id}", author = author, repostedBy = repostedBy)

    private fun profile(id: String, apId: String, handle: String, avatar: String?) =
        Profile(id = id, apId = apId, handle = handle, avatarUrl = avatar)

    @Test
    fun `a loaded profile is found by service id, ActivityPub id or handle`() {
        val avatars = FreshAvatars()
        avatars.record(profile("42", "https://example.social/users/ana", "@Ana@example.social", fresh))

        assertEquals(fresh, avatars.freshUrl(author("42", "@other@x")))
        assertEquals(fresh, avatars.freshUrl(author("https://example.social/users/ana", "@other@x")))
        assertEquals(fresh, avatars.freshUrl(author("7", "@ana@EXAMPLE.social")))
        assertNull(avatars.freshUrl(author("7", "@ben@example.social")))
    }

    @Test
    fun `a profile without a picture keeps the avatar already known`() {
        val avatars = FreshAvatars()
        avatars.record(profile("42", "", "@ana@example.social", fresh))

        avatars.record(profile("42", "", "@ana@example.social", ""))
        avatars.record(profile("42", "", "@ana@example.social", null))

        assertEquals(fresh, avatars.freshUrl(author("42", "@ana@example.social")))
    }

    @Test
    fun `the account's own record teaches the map too`() {
        val avatars = FreshAvatars()

        avatars.record(Account(id = "1", name = "Me", handle = "@me@example.com", avatarUrl = fresh))

        assertEquals(fresh, avatars.freshUrl(author("1", "@me@example.com")))
    }

    @Test
    fun `a tracked avatar is replaced once a fresher one is known, and not before`() {
        val avatars = FreshAvatars()
        val ana = author("42", "@ana@example.social")
        avatars.track(listOf(post(ana)))

        assertNull(avatars.replacementFor(stale))

        avatars.record(profile("42", "", "@ana@example.social", fresh))

        assertEquals(fresh, avatars.replacementFor(stale))
        assertNull("the fresh address needs no replacement", avatars.replacementFor(fresh))
    }

    @Test
    fun `an avatar that profile data confirms is not replaced`() {
        val avatars = FreshAvatars()
        avatars.track(listOf(post(author("42", "@ana@example.social"))))

        avatars.record(profile("42", "", "@ana@example.social", stale))

        assertNull(avatars.replacementFor(stale))
    }

    @Test
    fun `reposters are tracked like authors`() {
        val avatars = FreshAvatars()
        val ben = author("8", "@ben@example.social")
        avatars.track(listOf(post(author("1", "@ana@example.social", avatar = "https://cdn.example/ana.png"), repostedBy = ben)))

        assertEquals(ben, avatars.ownerOf(stale))
    }

    // Lookups

    private class Lookups(val avatars: FreshAvatars, val fresh: Map<String, String?>) {
        val handles = mutableListOf<String>()
        val startTimes = mutableListOf<Long>()
        var failWith: Exception? = null
        var clock: () -> Long = { 0 }

        suspend fun fetch(handle: String) {
            handles += handle
            startTimes += clock()
            failWith?.let { throw it }
            avatars.record(Profile(id = handle, handle = handle, avatarUrl = fresh[handle]))
        }
    }

    private fun TestScope.refresher(lookups: Lookups, spacing: Long = 1_000) = AvatarRefresher(
        avatars = lookups.avatars,
        scope = backgroundScope,
        spacingMillis = spacing,
        fetchProfile = lookups::fetch,
    ).also { lookups.clock = { currentTime } }

    @Test
    fun `an avatar that fails to load is replaced by its owner's current one`() = runTest {
        val avatars = FreshAvatars()
        avatars.track(listOf(post(author("1", "@ana@example.social"))))
        val lookups = Lookups(avatars, mapOf("@ana@example.social" to fresh))

        assertEquals(fresh, refresher(lookups).replacementFor(stale))
    }

    @Test
    fun `many images of one account cause one lookup, and later failures none`() = runTest {
        val avatars = FreshAvatars()
        avatars.track(listOf(post(author("1", "@ana@example.social"))))
        val lookups = Lookups(avatars, mapOf("@ana@example.social" to fresh))
        val refresher = refresher(lookups)

        val results = List(5) { async { refresher.replacementFor(stale) } }.awaitAll()
        val later = refresher.replacementFor(stale)

        assertEquals(List(5) { fresh }, results)
        assertEquals(fresh, later)
        assertEquals(listOf("@ana@example.social"), lookups.handles)
    }

    @Test
    fun `the same account under different letter case is one account`() = runTest {
        val avatars = FreshAvatars()
        val lookups = Lookups(avatars, mapOf("@Ana@Example.social" to fresh, "@ana@example.social" to fresh))
        avatars.track(listOf(post(author("1", "@Ana@Example.social", avatar = "https://cdn.example/a.png"))))
        avatars.track(listOf(post(author("2", "@ana@example.social", avatar = "https://cdn.example/b.png"))))
        val refresher = refresher(lookups)

        refresher.replacementFor("https://cdn.example/a.png")
        refresher.replacementFor("https://cdn.example/b.png")

        assertEquals(1, lookups.handles.size)
    }

    @Test
    fun `an account whose lookup failed is not asked for again and keeps its picture`() = runTest {
        val avatars = FreshAvatars()
        avatars.track(listOf(post(author("1", "@ana@example.social"))))
        val lookups = Lookups(avatars, emptyMap()).apply { failWith = SocialWebException(404, "gone") }
        val refresher = refresher(lookups)

        assertNull(refresher.replacementFor(stale))
        assertNull(refresher.replacementFor(stale))

        assertEquals(1, lookups.handles.size)
    }

    @Test
    fun `an address nobody tracked is not looked up`() = runTest {
        val lookups = Lookups(FreshAvatars(), emptyMap())

        assertNull(refresher(lookups).replacementFor("https://cdn.example/unknown.png"))
        assertTrue(lookups.handles.isEmpty())
    }

    @Test
    fun `a lookup that finds the same picture replaces nothing`() = runTest {
        val avatars = FreshAvatars()
        avatars.track(listOf(post(author("1", "@ana@example.social"))))
        val lookups = Lookups(avatars, mapOf("@ana@example.social" to stale))

        assertNull(refresher(lookups).replacementFor(stale))
        assertEquals(1, lookups.handles.size)
    }

    @Test
    fun `lookups of different accounts are spaced out`() = runTest {
        val avatars = FreshAvatars()
        val urls = (1..3).associate { "@user$it@example.social" to "https://cdn.example/old$it.png" }
        val lookups = Lookups(avatars, urls.keys.associateWith { fresh })
        urls.entries.forEachIndexed { index, (handle, url) ->
            avatars.track(listOf(post(author("${index + 1}", handle, avatar = url))))
        }
        val refresher = refresher(lookups, spacing = 1_000)

        urls.values.map { url -> async { refresher.replacementFor(url) } }.awaitAll()

        assertEquals(3, lookups.handles.size)
        assertEquals(listOf(0L, 1_000L, 2_000L), lookups.startTimes)
    }
}
