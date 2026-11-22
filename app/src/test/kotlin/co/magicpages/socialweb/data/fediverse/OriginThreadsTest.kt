package co.magicpages.socialweb.data.fediverse

import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.ReplyChain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginThreadsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    // Which post a server is asked for

    @Test
    fun `a Mastodon id names the server and the status on it`() {
        assertEquals(
            OriginStatusId("socel.net", "117660793370766840"),
            originStatusOf("https://socel.net/users/KarenKasparArt/statuses/117660793370766840"),
        )
    }

    @Test
    fun `web addresses and other paths ending in a status id are read too`() {
        val expected = OriginStatusId("mastodon.social", "42")
        assertEquals(expected, originStatusOf("https://mastodon.social/@ana/42"))
        assertEquals(expected, originStatusOf("https://Mastodon.Social/@ana/42/"))
        assertEquals(expected, originStatusOf("https://mastodon.social/web/@ana/42"))
        assertEquals(expected, originStatusOf("https://mastodon.social/ap/users/1234/statuses/42"))
        assertEquals(expected, originStatusOf("https://mastodon.social/users/ana/statuses/42?foo=1#bar"))
    }

    @Test
    fun `ids of other software and shapes without a status id are refused`() {
        // A UUID, a path that isn't a status, a numeric id of another kind of post.
        assertNull(originStatusOf("https://labyrinth.zone/objects/1b6f2b31-d7a3-4ae6-abec-c80ddb087991"))
        assertNull(originStatusOf("https://lemmy.world/post/12345"))
        assertNull(originStatusOf("https://mastodon.social/users/ana/statuses/42/replies"))
        assertNull(originStatusOf("https://mastodon.social/users/ana"))
        assertNull(originStatusOf("https://mastodon.social/42"))
        assertNull(originStatusOf("https://mastodon.social/"))
        assertNull(originStatusOf("https://example.com/.ghost/activitypub/note/8f1d0e3c-9a52-4c36-8c2a-7d0d0a2d4f11"))
    }

    @Test
    fun `only plain https addresses of named hosts are used`() {
        assertNull(originStatusOf("http://mastodon.social/users/ana/statuses/42"))
        assertNull(originStatusOf("https://mastodon.social:8443/users/ana/statuses/42"))
        assertNull(originStatusOf("https://me:secret@mastodon.social/users/ana/statuses/42"))
        assertNull(originStatusOf("https://192.168.1.10/users/ana/statuses/42"))
        assertNull(originStatusOf("https://localhost/users/ana/statuses/42"))
        assertNull(originStatusOf("not a url"))
        assertNull(originStatusOf(""))
    }

    // The replies as a tree

    private fun reply(id: String, parent: String?) = OriginReply(
        id = id,
        parentId = parent,
        post = Post(id = "https://remote.example/statuses/$id", author = Author(id = "a", handle = "@a@remote.example", name = "A")),
    )

    private fun List<ReplyChain.Child>.summary() =
        map { it.post.id.substringAfterLast('/') to it.chain.map { post -> post.id.substringAfterLast('/') } }

    @Test
    fun `direct replies become the thread's replies, in the server's order`() {
        val tree = replyTree("1", listOf(reply("2", "1"), reply("3", "1"), reply("4", "1")))

        assertEquals(listOf("2" to emptyList<String>(), "3" to emptyList(), "4" to emptyList()), tree.summary())
    }

    @Test
    fun `what is below a reply becomes its follow-ups, branch by branch`() {
        val tree = replyTree(
            "1",
            listOf(
                reply("2", "1"),
                reply("3", "2"),
                reply("4", "2"),
                reply("5", "3"),
                reply("6", "1"),
                reply("7", "6"),
            ),
        )

        // 2's branch reads 3, then what answers 3 (5), then 4; the server's own order inside each level.
        assertEquals(listOf("2" to listOf("3", "5", "4"), "6" to listOf("7")), tree.summary())
        assertTrue(tree.none { it.hasMore })
    }

    @Test
    fun `replies whose parent is missing are dropped`() {
        val tree = replyTree("1", listOf(reply("2", "1"), reply("3", "gone"), reply("4", "3"), reply("5", null)))

        assertEquals(listOf("2" to emptyList<String>()), tree.summary())
    }

    @Test
    fun `a post listed twice or answering itself shows once`() {
        val tree = replyTree("1", listOf(reply("2", "1"), reply("2", "1"), reply("3", "2"), reply("3", "2"), reply("9", "9")))

        assertEquals(listOf("2" to listOf("3")), tree.summary())
    }

    @Test
    fun `a thread with no replies has no groups`() {
        assertEquals(emptyList<Any>(), replyTree("1", emptyList()))
    }

    // Asking the server

    private val postId = "https://socel.net/users/ana/statuses/100"
    private val requests = mutableListOf<HttpRequestData>()

    private fun status(id: String, uri: String, parent: String?, replies: Int = 0, likes: Int = 0) = """
        {"id":"$id","uri":"$uri","url":"$uri","created_at":"2026-11-22T13:41:00.000Z","content":"<p>$id</p>",
         "in_reply_to_id":${parent?.let { "\"$it\"" }},"sensitive":false,"spoiler_text":"",
         "account":{"acct":"ana","display_name":"Ana","avatar":"https://socel.net/a.png","url":"https://socel.net/@ana"},
         "media_attachments":[],"replies_count":$replies,"reblogs_count":4,"favourites_count":$likes}
    """.trimIndent()

    private fun threads(handler: (HttpRequestData) -> Pair<HttpStatusCode, String>): OriginThreads {
        val engine = MockEngine { request ->
            requests += request
            val (status, body) = handler(request)
            respond(body, status)
        }
        return OriginThreads(HttpClient(engine) { expectSuccess = false }, json)
    }

    private val context = """
        {"ancestors":[],"descendants":[
          ${status("101", "https://remote.example/users/bo/statuses/9", "100")},
          ${status("102", "https://other.example/users/cy/statuses/7", "101")}
        ]}
    """.trimIndent()

    @Test
    fun `the home server is asked unauthenticated for the post and its context`() = runTest {
        val thread = threads { request ->
            when (request.url.encodedPath) {
                "/api/v1/statuses/100" -> HttpStatusCode.OK to status("100", postId, null, replies = 9, likes = 111)
                "/api/v1/statuses/100/context" -> HttpStatusCode.OK to context
                else -> HttpStatusCode.NotFound to ""
            }
        }.load(postId)!!

        assertEquals(setOf("/api/v1/statuses/100", "/api/v1/statuses/100/context"), requests.map { it.url.encodedPath }.toSet())
        assertTrue(requests.all { it.url.host == "socel.net" && it.url.protocol.name == "https" && !it.headers.contains("Authorization") })
        assertEquals(111, thread.post?.likeCount)
        assertEquals(9, thread.post?.replyCount)
        assertEquals(postId, thread.post?.id)
        // The second reply answers the first, so it's a follow-up.
        assertEquals(listOf("https://remote.example/users/bo/statuses/9"), thread.replies.map { it.post.id })
        assertEquals(listOf("https://other.example/users/cy/statuses/7"), thread.replies.single().chain.map { it.id })
    }

    @Test
    fun `the replies are still read when the post itself isn't`() = runTest {
        val thread = threads { request ->
            if (request.url.encodedPath.endsWith("/context")) HttpStatusCode.OK to context else HttpStatusCode.NotFound to ""
        }.load(postId)!!

        assertNull(thread.post)
        assertEquals(1, thread.replies.size)
    }

    @Test
    fun `an answer about some other post gives no counts`() = runTest {
        val thread = threads { request ->
            if (request.url.encodedPath.endsWith("/context")) HttpStatusCode.OK to context
            else HttpStatusCode.OK to status("100", "https://socel.net/users/ana/statuses/555", null, likes = 5)
        }.load(postId)!!

        assertNull(thread.post)
    }

    @Test
    fun `one post that doesn't parse costs only that post`() = runTest {
        val thread = threads { request ->
            if (request.url.encodedPath.endsWith("/context")) {
                HttpStatusCode.OK to """{"descendants":[{"id":"101"},${status("102", "https://remote.example/users/bo/statuses/9", "100")}]}"""
            } else {
                HttpStatusCode.NotFound to ""
            }
        }.load(postId)!!

        assertEquals(listOf("https://remote.example/users/bo/statuses/9"), thread.replies.map { it.post.id })
    }

    @Test
    fun `refusals, bad bodies and network errors give null`() = runTest {
        assertNull(threads { HttpStatusCode.Forbidden to """{"error":"login required"}""" }.load(postId))
        assertNull(threads { HttpStatusCode.OK to "<html>not the API</html>" }.load(postId))
        assertNull(threads { HttpStatusCode.OK to "[1,2]" }.load(postId))
        val offline = OriginThreads(HttpClient(MockEngine { throw IOException("offline") }), json)
        assertNull(offline.load(postId))
    }

    @Test
    fun `an id that isn't Mastodon-shaped isn't requested at all`() = runTest {
        val thread = threads { HttpStatusCode.OK to context }.load("https://labyrinth.zone/objects/1b6f2b31-d7a3-4ae6-abec-c80ddb087991")

        assertNull(thread)
        assertTrue(requests.isEmpty())
    }
}
