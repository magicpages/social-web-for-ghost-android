package co.magicpages.socialweb.ui.posts

import co.magicpages.socialweb.data.activitypub.GhostAuthor
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleBylineTest {
    private fun writers(vararg names: String) = names.map { GhostAuthor(it) }

    @Test
    fun `no writers show no byline`() {
        assertEquals(emptyList<String>(), bylineNames(emptyList(), "Site"))
    }

    @Test
    fun `a lone writer named like the author adds nothing`() {
        assertEquals(emptyList<String>(), bylineNames(writers("Site"), "Site"))
        assertEquals(emptyList<String>(), bylineNames(writers(" site "), "Site"))
    }

    @Test
    fun `a lone writer with another name is shown`() {
        assertEquals(listOf("Ana"), bylineNames(writers("Ana"), "Site"))
    }

    @Test
    fun `several writers are shown in order, even when one matches the author`() {
        assertEquals(listOf("Ana", "Ben"), bylineNames(writers("Ana", "Ben"), "Site"))
        assertEquals(listOf("Site", "Ben"), bylineNames(writers("Site", "Ben"), "Site"))
    }

    @Test
    fun `repeated and blank names collapse`() {
        assertEquals(listOf("Ana"), bylineNames(writers("Ana", "ana", " "), "Site"))
        assertEquals(emptyList<String>(), bylineNames(writers("Site", "SITE"), "Site"))
    }
}
