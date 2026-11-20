package co.magicpages.socialweb.data.activitypub

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostAuthorsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    private fun post(extra: String = "") =
        json.decodeFromString<Post>("""{"id": "p", "author": {"id": "a", "handle": "@site@example.com"}$extra}""")

    @Test
    fun `a post without metadata has no ghost authors`() {
        assertTrue(post().ghostAuthors.isEmpty())
    }

    @Test
    fun `null metadata and null author list are empty`() {
        assertTrue(post(""","metadata": null""").ghostAuthors.isEmpty())
        assertTrue(post(""","metadata": {"ghostAuthors": null}""").ghostAuthors.isEmpty())
        assertTrue(post(""","metadata": {}""").ghostAuthors.isEmpty())
    }

    @Test
    fun `writers are read with their names and profile images`() {
        val writers = post(
            ""","metadata": {"ghostAuthors": [
                {"name": "Ana", "profile_image": "https://example.com/ana.jpg"},
                {"name": "Ben", "profile_image": null}
            ]}""",
        ).ghostAuthors

        assertEquals(listOf("Ana", "Ben"), writers.map { it.name })
        assertEquals("https://example.com/ana.jpg", writers[0].profileImage)
        assertNull(writers[1].profileImage)
    }

    @Test
    fun `extra keys in metadata and in a writer are ignored`() {
        val writers = post(
            ""","metadata": {"other": {"a": 1}, "ghostAuthors": [{"name": "Ana", "slug": "ana"}]}""",
        ).ghostAuthors

        assertEquals(listOf("Ana"), writers.map { it.name })
    }

    @Test
    fun `entries without a usable name are dropped without failing the post`() {
        val writers = post(
            ""","metadata": {"ghostAuthors": [null, 3, {"profile_image": null}, {"name": null}, {"name": " "}, {"name": "Ana"}]}""",
        ).ghostAuthors

        assertEquals(listOf("Ana"), writers.map { it.name })
    }
}
