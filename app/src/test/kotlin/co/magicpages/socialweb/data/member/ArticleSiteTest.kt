package co.magicpages.socialweb.data.member

import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArticleSiteTest {
    private fun article(id: String, url: String, content: String = "<p>Hi</p>") = Post(
        id = id,
        type = Post.TYPE_ARTICLE,
        content = content,
        url = url,
        author = Author(id = "author", handle = "@a@b.example"),
    )

    @Test
    fun `a Ghost article belongs to the site its ActivityPub id is on`() {
        val post = article("https://www.fayn.press/.ghost/activitypub/article/7e48", "https://www.fayn.press/artakalan/")
        assertEquals("https://www.fayn.press/", post.articleSiteUrl)
    }

    @Test
    fun `a site in a subdirectory keeps it`() {
        val post = article("https://example.com/blog/.ghost/activitypub/article/1", "https://example.com/blog/a/")
        assertEquals("https://example.com/blog/", post.articleSiteUrl)
    }

    @Test
    fun `an article from other software has no site`() {
        assertNull(article("https://write.as/api/posts/abc", "https://write.as/matt/post").articleSiteUrl)
    }

    @Test
    fun `the members-only notice marks a Ghost site whatever the id says, so its page's host is the site`() {
        val gated = article(
            "https://example.org/p/1",
            "https://News.example/a/",
            "<p>Hi</p><div class='gh-paid-content-notice'></div>",
        )
        assertEquals("https://news.example/", gated.articleSiteUrl)
    }
}
