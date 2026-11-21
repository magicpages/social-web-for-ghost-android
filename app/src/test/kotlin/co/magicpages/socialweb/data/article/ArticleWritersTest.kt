package co.magicpages.socialweb.data.article

import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleWritersTest {
    private fun page(json: String) =
        """<html><head><script type="application/ld+json">$json</script></head><body><p>Hi</p></body></html>"""

    @Test
    fun `Ghost's author comes first, then contributors, once each`() {
        val writers = writersFromStructuredData(
            page(
                """
                {"@context":"https://schema.org","@type":"Article",
                 "publisher":{"@type":"Organization","name":"404 Media"},
                 "author":{"@type":"Person","name":"Samantha Cole","image":{"@type":"ImageObject","url":"https://example.com/sam.jpg"}},
                 "contributor":[{"@type":"Person","name":"Jason Koebler","image":"https://example.com/jason.jpg"},
                                {"@type":"Person","name":"samantha cole"}]}
                """,
            ),
        )
        assertEquals(listOf("Samantha Cole", "Jason Koebler"), writers.map { it.name })
        assertEquals(listOf("https://example.com/sam.jpg", "https://example.com/jason.jpg"), writers.map { it.profileImage })
    }

    @Test
    fun `references in a Yoast graph resolve, and escaped names decode`() {
        val writers = writersFromStructuredData(
            page(
                """
                {"@context":"https://schema.org","@graph":[
                  {"@type":"WebPage","@id":"https://blog.example/post/"},
                  {"@type":"BlogPosting","@id":"https://blog.example/post/#article","author":{"@id":"https://blog.example/#/person/1"}},
                  {"@type":"Person","@id":"https://blog.example/#/person/1","name":"Jo &amp; Sam","image":{"url":"https://blog.example/jo.png"}}]}
                """,
            ),
        )
        assertEquals(listOf("Jo & Sam"), writers.map { it.name })
        assertEquals("https://blog.example/jo.png", writers.single().profileImage)
    }

    @Test
    fun `an organization as the author is the publication, not a writer`() {
        assertEquals(emptyList<String>(), writersFromStructuredData(page("""{"@type":"NewsArticle","author":{"@type":"Organization","name":"The Paper"}}""")).map { it.name })
    }

    @Test
    fun `plain names count, other blocks and non-article objects don't`() {
        val html = """<script type="application/ld+json">{"@type":"WebSite","name":"Site"}</script>""" +
            page("""[{"@type":"BreadcrumbList"},{"@type":["Article","Thing"],"author":["A. Writer"]}]""")
        assertEquals(listOf("A. Writer"), writersFromStructuredData(html).map { it.name })
    }

    @Test
    fun `insecure pictures are dropped but the writer stays`() {
        val writers = writersFromStructuredData(page("""{"@type":"Article","author":{"name":"Ana","image":"http://example.com/ana.jpg"}}"""))
        assertEquals(listOf("Ana"), writers.map { it.name })
        assertEquals(null, writers.single().profileImage)
    }

    @Test
    fun `pages without usable structured data name nobody`() {
        assertEquals(0, writersFromStructuredData("<html><body><p>Hi</p></body></html>").size)
        assertEquals(0, writersFromStructuredData(page("{not json")).size)
        assertEquals(0, writersFromStructuredData(page("""{"@type":"WebPage","author":{"name":"X"}}""")).size)
    }
}
