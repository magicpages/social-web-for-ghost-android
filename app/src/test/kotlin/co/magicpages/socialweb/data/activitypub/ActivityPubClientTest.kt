package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.account.Credential
import co.magicpages.socialweb.data.account.GhostSite
import co.magicpages.socialweb.data.ghost.GhostAdminClient
import co.magicpages.socialweb.data.ghost.GhostException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ActivityPubClientTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val requests = mutableListOf<HttpRequestData>()
    private val site = GhostSite(
        title = "Example",
        siteUrl = "https://example.com/",
        adminApiUrl = "https://example.com/ghost/api/admin/",
        version = "6.78",
    )
    private val credential = Credential.Session(mapOf("ghost-admin-api-session" to "abc"))

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ActivityPubClient {
        val http = HttpClient(MockEngine { request -> requests += request; handler(request) }) { expectSuccess = false }
        return ActivityPubClient(http, json, GhostAdminClient(http, json))
    }

    private val HttpRequestData.isIdentities get() = url.encodedPath.endsWith("/identities/")

    @Test
    fun `expired identity token is refreshed once and the request retried`() = runTest {
        var issued = 0
        val ap = client { request ->
            when {
                request.isIdentities -> respond("""{"identities":[{"token":"jwt-${++issued}"}]}""", HttpStatusCode.OK)
                // What the service answers for an expired JWT: it treats the caller as anonymous.
                request.headers[HttpHeaders.Authorization] == "Bearer jwt-1" ->
                    respond("""{"error":"Forbidden","code":"ROLE_MISSING"}""", HttpStatusCode.Forbidden)
                else -> respond("""{"posts":[],"next":null}""", HttpStatusCode.OK)
            }
        }

        val page = ap.notesFeed(site, credential)

        assertTrue(page.posts.isEmpty())
        assertEquals(2, issued)
        assertEquals(4, requests.size)
        // The refreshed token is reused for later calls.
        ap.notesFeed(site, credential, next = "cursor-2")
        assertEquals(2, issued)
        assertEquals("cursor-2", requests.last().url.parameters["next"])
    }

    @Test
    fun `a refusal unrelated to the token is not retried`() = runTest {
        var issued = 0
        val ap = client { request ->
            if (request.isIdentities) respond("""{"identities":[{"token":"jwt-${++issued}"}]}""", HttpStatusCode.OK)
            else respond("""{"message":"You cannot follow this account"}""", HttpStatusCode.Forbidden)
        }

        try {
            ap.follow(site, credential, "@someone@example.com")
            fail("Expected SocialWebException")
        } catch (e: SocialWebException) {
            assertEquals(403, e.status)
            assertEquals("You cannot follow this account", e.detail)
        }
        assertEquals(1, issued)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a session Ghost no longer accepts surfaces as Unauthorized`() = runTest {
        val ap = client { respond("""{"errors":[{"message":"Authorization failed","type":"NoPermissionError"}]}""", HttpStatusCode.Forbidden) }

        try {
            ap.notesFeed(site, credential)
            fail("Expected Unauthorized")
        } catch (_: GhostException.Unauthorized) {
        }
    }

    @Test
    fun `feed parsing tolerates the gaps remote servers leave`() = runTest {
        val ap = client { request ->
            if (request.isIdentities) {
                respond("""{"identities":[{"token":"jwt"}]}""", HttpStatusCode.OK)
            } else {
                respond(FEED, HttpStatusCode.OK)
            }
        }

        val page = ap.notesFeed(site, credential)

        assertEquals("https://example.com/.ghost/activitypub/v1/feed/notes", requests.last().url.toString())
        assertEquals("application/activity+json", requests.last().headers[HttpHeaders.Accept])
        assertEquals("cursor-2", page.next)
        val (note, repost) = page.posts
        assertEquals("", note.author.name)
        assertNull(note.author.avatarUrl)
        assertEquals("", note.title)
        assertEquals(listOf("A cat"), note.images.map { it.name })
        assertFalse(note.isArticle)
        assertEquals("Alice", repost.repostedBy?.name)
        assertTrue(repost.isArticle)
    }

    @Test
    fun `own account parses with the numeric id the service sends`() = runTest {
        val ap = client { request ->
            if (request.isIdentities) {
                respond("""{"identities":[{"token":"jwt"}]}""", HttpStatusCode.OK)
            } else {
                respond(
                    """{"id":4217,"apId":"https://example.com/.ghost/activitypub/users/index","name":"Example","handle":"@index@example.com","avatarUrl":"","bio":"","url":"https://example.com/","postCount":3}""",
                    HttpStatusCode.OK,
                )
            }
        }

        val account = ap.currentAccount(site, credential)

        assertEquals("4217", account.id)
        assertEquals("@index@example.com", account.handle)
    }

    @Test
    fun `actions post to the post id encoded as a single path segment`() = runTest {
        val ap = client { request ->
            if (request.isIdentities) respond("""{"identities":[{"token":"jwt"}]}""", HttpStatusCode.OK)
            else respond("""{"id":"https://example.com/like/1"}""", HttpStatusCode.OK)
        }
        val id = "https://remote.example/notes/1?x=1"

        ap.like(site, credential, id)
        ap.unlike(site, credential, id)
        ap.repost(site, credential, id)
        ap.derepost(site, credential, id)

        val actions = requests.filterNot { it.isIdentities }
        assertEquals(
            listOf("like", "unlike", "repost", "derepost").map {
                "/.ghost/activitypub/v1/actions/$it/https%3A%2F%2Fremote.example%2Fnotes%2F1%3Fx%3D1"
            },
            actions.map { it.url.encodedPath },
        )
        assertTrue(actions.all { it.method == HttpMethod.Post && it.headers[HttpHeaders.Authorization] == "Bearer jwt" })
    }

    @Test
    fun `reply sends its text as JSON, again after a token refresh`() = runTest {
        var issued = 0
        val ap = client { request ->
            when {
                request.isIdentities -> respond("""{"identities":[{"token":"jwt-${++issued}"}]}""", HttpStatusCode.OK)
                request.headers[HttpHeaders.Authorization] == "Bearer jwt-1" -> respond("", HttpStatusCode.Unauthorized)
                else -> respond("""{"type":"Create"}""", HttpStatusCode.OK)
            }
        }

        ap.reply(site, credential, "https://remote.example/notes/1", "Nice \"one\"\nsecond line")

        val replies = requests.filterNot { it.isIdentities }
        assertEquals(2, replies.size)
        for (request in replies) {
            assertEquals("/.ghost/activitypub/v1/actions/reply/https%3A%2F%2Fremote.example%2Fnotes%2F1", request.url.encodedPath)
            assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
            assertEquals(
                JsonPrimitive("Nice \"one\"\nsecond line"),
                json.parseToJsonElement(String((request.body as TextContent).bytes())).jsonObject["content"],
            )
        }
    }

    @Test
    fun `an image upload sends the whole file again after a token refresh`() = runTest {
        var issued = 0
        val image = ByteArray(70_000) { (it % 251).toByte() }
        val ap = client { request ->
            when {
                request.isIdentities -> respond("""{"identities":[{"token":"jwt-${++issued}"}]}""", HttpStatusCode.OK)
                request.headers[HttpHeaders.Authorization] == "Bearer jwt-1" -> respond("", HttpStatusCode.Unauthorized)
                else -> respond("""{"fileUrl":"https://example.com/content/images/a.jpg"}""", HttpStatusCode.OK)
            }
        }

        val url = ap.uploadImage(site, credential, image, "photo.jpg", "image/jpeg")

        assertEquals("https://example.com/content/images/a.jpg", url)
        val uploads = requests.filterNot { it.isIdentities }
        assertEquals(2, uploads.size)
        for (request in uploads) {
            assertEquals("/.ghost/activitypub/v1/upload/image", request.url.encodedPath)
            val body = request.body.toByteArray()
            val text = String(body, Charsets.ISO_8859_1)
            val headers = text.substringBefore("\r\n\r\n")
            assertTrue(headers, Regex("""Content-Disposition: form-data; name="?file"?; filename="photo.jpg"""").containsMatchIn(headers))
            assertTrue(headers, headers.contains("Content-Type: image/jpeg"))
            // The file itself, byte for byte, sits between the part headers and the closing boundary.
            val start = text.indexOf("\r\n\r\n") + 4
            assertTrue(image.contentEquals(body.copyOfRange(start, start + image.size)))
        }
    }

    @Test
    fun `a new note sends its text and image, and returns the created post`() = runTest {
        val ap = client { request ->
            if (request.isIdentities) {
                respond("""{"identities":[{"token":"jwt"}]}""", HttpStatusCode.OK)
            } else {
                respond(
                    """{"post":{"id":"https://example.com/.ghost/activitypub/note/1","type":0,"content":"<p>Hello</p>",
                        "author":{"id":"1","handle":"@index@example.com"},"authoredByMe":true}}""",
                    HttpStatusCode.OK,
                )
            }
        }

        val post = ap.createNote(site, credential, "Hello", NoteImage("https://example.com/a.jpg", "A cat"))

        assertEquals("https://example.com/.ghost/activitypub/note/1", post.id)
        assertTrue(post.authoredByMe)
        val request = requests.last()
        assertEquals("/.ghost/activitypub/v1/actions/note", request.url.encodedPath)
        val sent = json.parseToJsonElement(String((request.body as TextContent).bytes())).jsonObject
        assertEquals(JsonPrimitive("Hello"), sent["content"])
        assertEquals(JsonPrimitive("https://example.com/a.jpg"), sent["image"]?.jsonObject?.get("url"))
        assertEquals(JsonPrimitive("A cat"), sent["image"]?.jsonObject?.get("altText"))
    }

    private val createdNote = """{"post":{"id":"https://example.com/.ghost/activitypub/note/2","type":0,"content":"<p>Hi</p>",
        "author":{"id":"1","handle":"@index@example.com"},"authoredByMe":true}}"""

    @Test
    fun `a note without an image sends no image at all`() = runTest {
        val ap = client { request ->
            if (request.isIdentities) respond("""{"identities":[{"token":"jwt"}]}""", HttpStatusCode.OK)
            else respond(createdNote, HttpStatusCode.OK)
        }

        ap.createNote(site, credential, "Hi")

        val sent = json.parseToJsonElement(String((requests.last().body as TextContent).bytes())).jsonObject
        assertEquals(JsonPrimitive("Hi"), sent["content"])
        assertFalse(sent.containsKey("image"))
    }

    @Test
    fun `an image without alt text sends its address only`() = runTest {
        val ap = client { request ->
            if (request.isIdentities) respond("""{"identities":[{"token":"jwt"}]}""", HttpStatusCode.OK)
            else respond(createdNote, HttpStatusCode.OK)
        }

        ap.createNote(site, credential, "Hi", NoteImage("https://example.com/quote.png"))

        val image = json.parseToJsonElement(String((requests.last().body as TextContent).bytes())).jsonObject["image"]!!.jsonObject
        assertEquals(JsonPrimitive("https://example.com/quote.png"), image["url"])
        assertFalse(image.containsKey("altText"))
    }

    @Test
    fun `a refused action surfaces the status and the service's message`() = runTest {
        val ap = client { request ->
            if (request.isIdentities) respond("""{"identities":[{"token":"jwt"}]}""", HttpStatusCode.OK)
            else respond("""{"error":"Already liked"}""", HttpStatusCode.Conflict)
        }

        try {
            ap.like(site, credential, "https://remote.example/notes/1")
            fail("Expected SocialWebException")
        } catch (e: SocialWebException) {
            assertEquals(409, e.status)
            assertEquals("Already liked", e.detail)
        }
    }

    @Test
    fun `profiles, their posts and follows address the account by handle`() = runTest {
        val ap = client { request ->
            when {
                request.isIdentities -> respond("""{"identities":[{"token":"jwt"}]}""", HttpStatusCode.OK)
                request.url.encodedPath.contains("/account/") -> respond(PROFILE, HttpStatusCode.OK)
                request.url.encodedPath.contains("/posts/") -> respond("""{"posts":[],"next":null}""", HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.Accepted)
            }
        }
        val handle = "@alice@other.example"

        val profile = ap.profile(site, credential, handle)
        val page = ap.postsBy(site, credential, handle, next = "cursor-2")
        ap.follow(site, credential, handle)
        ap.unfollow(site, credential, handle)

        val calls = requests.filterNot { it.isIdentities }
        assertEquals(
            listOf(
                "/.ghost/activitypub/v1/account/@alice@other.example",
                "/.ghost/activitypub/v1/posts/@alice@other.example",
                "/.ghost/activitypub/v1/actions/follow/@alice@other.example",
                "/.ghost/activitypub/v1/actions/unfollow/@alice@other.example",
            ),
            calls.map { it.url.encodedPath },
        )
        assertEquals("cursor-2", calls[1].url.parameters["next"])
        assertEquals(listOf(HttpMethod.Get, HttpMethod.Get, HttpMethod.Post, HttpMethod.Post), calls.map { it.method })
        assertNull(page.next)
        assertEquals("Alice", profile.name)
        assertEquals(mapOf("Website" to "<a href=\"https://alice.example\">alice.example</a>", "Pronouns" to null), profile.customFields)
        assertTrue(profile.followsMe)
        assertFalse(profile.followedByMe)
        assertEquals(12, profile.followerCount)
    }

    @Test
    fun `follow lists page by cursor and parse the service's account entries`() = runTest {
        val ap = client { request ->
            if (request.isIdentities) {
                respond("""{"identities":[{"token":"jwt"}]}""", HttpStatusCode.OK)
            } else {
                respond(
                    """{"accounts":[{"id":"https://b.example/users/bob","apId":"https://b.example/users/bob","name":"",
                    "handle":"@bob@b.example","avatarUrl":"","isFollowing":true,"followedByMe":true,"blockedByMe":false,
                    "domainBlockedByMe":false}],"next":"20"}""",
                    HttpStatusCode.OK,
                )
            }
        }

        val page = ap.follows(site, credential, "@alice@other.example", FollowList.Followers, next = "0")
        ap.follows(site, credential, "me", FollowList.Following)

        val calls = requests.filterNot { it.isIdentities }
        assertEquals(
            listOf(
                "/.ghost/activitypub/v1/account/@alice@other.example/follows/followers",
                "/.ghost/activitypub/v1/account/me/follows/following",
            ),
            calls.map { it.url.encodedPath },
        )
        assertEquals("0", calls[0].url.parameters["next"])
        assertNull(calls[1].url.parameters["next"])
        assertEquals("20", page.next)
        val bob = page.accounts.single()
        assertEquals("@bob@b.example", bob.handle)
        assertTrue(bob.followedByMe)
        assertTrue(bob.avatarUrl.isNullOrEmpty())
    }

    @Test
    fun `replies parse the conversation above and the reply chains below`() = runTest {
        val ap = client { request ->
            if (request.isIdentities) respond("""{"identities":[{"token":"jwt"}]}""", HttpStatusCode.OK)
            else respond(REPLIES, HttpStatusCode.OK)
        }

        val thread = ap.replies(site, credential, "https://example.com/notes/2")

        assertEquals(
            "/.ghost/activitypub/v1/replies/https%3A%2F%2Fexample.com%2Fnotes%2F2",
            requests.last().url.encodedPath,
        )
        assertEquals(listOf("https://remote.example/notes/1"), thread.ancestors.chain.map { it.id })
        assertTrue(thread.ancestors.hasMore)
        assertEquals("https://example.com/notes/2", thread.post.id)
        val child = thread.children.single()
        assertEquals("https://other.example/notes/3", child.post.id)
        assertEquals(listOf("https://example.com/notes/4"), child.chain.map { it.id })
        assertFalse(child.hasMore)
        assertEquals("cursor-r", thread.next)
    }

    @Test
    fun `a status newer than the app leaves the Bluesky handle readable`() = runTest {
        val ap = client { request ->
            if (request.isIdentities) {
                respond("""{"identities":[{"token":"jwt"}]}""", HttpStatusCode.OK)
            } else {
                respond(
                    """{"enabled":true,"handleConfirmed":true,"handle":"index.example.com.ap.brid.gy",
                    "domainHandle":{"domain":"example.com","status":"paused","reason":"later","automatic":true}}""",
                    HttpStatusCode.OK,
                )
            }
        }

        val status = ap.confirmBlueskyHandle(site, credential)

        assertEquals("index.example.com.ap.brid.gy", status.handle)
        assertEquals(DomainHandle("example.com", DomainHandleStatus.Unknown, automatic = true), status.domainHandle)
    }

    @Test
    fun `posts from a remote outbox accept raw ActivityStreams attachments and links`() = runTest {
        // The service passes these straight from the remote outbox; seen on Murat's Ghost site.
        val body = """
            {"posts": [
              {"id": "https://a.example/n/1", "author": {"id": "a", "handle": "@a@a.example"},
               "attachments": {"type": "Image", "name": "Phone", "url": "https://a.example/1.jpg"}},
              {"id": "https://a.example/n/2",
               "author": {"id": "a", "handle": "@a@a.example",
                          "avatarUrl": {"type": "Image", "url": "https://a.example/me.png"},
                          "url": [{"type": "Link", "href": "https://a.example/@a"}]},
               "url": {"type": "Link", "href": "https://a.example/@a/2"},
               "featureImageUrl": {"type": "Image", "url": "https://a.example/cover.jpg"},
               "attachments": [
                 {"type": "Document", "mediaType": "video/mp4", "url": [{"type": "Link", "href": "https://a.example/v.mp4"}]},
                 {"type": "PropertyValue", "name": "Website", "value": "a.example"}
               ]}
            ], "next": null}
        """.trimIndent()
        val ap = client { request ->
            if (request.isIdentities) respond("""{"identities":[{"token":"jwt"}]}""", HttpStatusCode.OK)
            else respond(body, HttpStatusCode.OK)
        }

        val (single, linked) = ap.postsBy(site, credential, "@a@a.example").posts

        assertEquals(listOf("https://a.example/1.jpg"), single.images.map { it.url })
        assertEquals("https://a.example/@a/2", linked.url)
        assertEquals("https://a.example/cover.jpg", linked.featureImageUrl)
        assertEquals(listOf("https://a.example/v.mp4"), linked.attachments.map { it.url })
        assertEquals("https://a.example/me.png", linked.author.avatarUrl)
        assertEquals("https://a.example/@a", linked.author.url)
    }

    private companion object {
        val FEED = """
            {
              "posts": [
                {
                  "id": "https://remote.example/notes/1",
                  "type": 0,
                  "title": null,
                  "content": "<p>Hello</p>",
                  "url": null,
                  "publishedAt": "2026-11-18T10:00:00.000Z",
                  "attachments": [
                    {"type": "Document", "mediaType": "image/jpeg", "name": "A cat", "url": "https://remote.example/cat.jpg"},
                    {"type": "Document", "mediaType": "video/mp4", "name": null, "url": "https://remote.example/v.mp4"}
                  ],
                  "author": {"id": "https://remote.example/users/bob", "handle": "@bob@remote.example", "name": null, "avatarUrl": null, "url": null},
                  "somethingNew": {"nested": true}
                },
                {
                  "id": "https://example.com/articles/2",
                  "type": 1,
                  "title": "Long read",
                  "readingTimeMinutes": 7,
                  "author": {"id": "https://example.com/users/index", "handle": "@index@example.com", "name": "Example"},
                  "repostedBy": {"id": "https://other.example/users/alice", "handle": "@alice@other.example", "name": "Alice"}
                }
              ],
              "next": "cursor-2"
            }
        """.trimIndent()

        val PROFILE = """
            {
              "id": "https://other.example/users/alice",
              "apId": "https://other.example/users/alice",
              "name": "Alice",
              "handle": "@alice@other.example",
              "avatarUrl": null,
              "bio": "<p>Hi</p>",
              "url": "https://other.example/@alice",
              "bannerImageUrl": null,
              "customFields": {"Website": "<a href=\"https://alice.example\">alice.example</a>", "Pronouns": null},
              "postCount": 40,
              "likedCount": 0,
              "followingCount": 3,
              "followerCount": 12,
              "followsMe": true,
              "followedByMe": false,
              "blockedByMe": false,
              "domainBlockedByMe": false
            }
        """.trimIndent()

        val REPLIES = """
            {
              "ancestors": {
                "chain": [{"id": "https://remote.example/notes/1", "author": {"id": "b", "handle": "@bob@remote.example"}}],
                "hasMore": true
              },
              "post": {"id": "https://example.com/notes/2", "author": {"id": "i", "handle": "@index@example.com"}},
              "children": [
                {
                  "post": {"id": "https://other.example/notes/3", "author": {"id": "a", "handle": "@alice@other.example"}},
                  "chain": [{"id": "https://example.com/notes/4", "author": {"id": "i", "handle": "@index@example.com"}}],
                  "hasMore": false
                }
              ],
              "next": "cursor-r"
            }
        """.trimIndent()
    }
}
