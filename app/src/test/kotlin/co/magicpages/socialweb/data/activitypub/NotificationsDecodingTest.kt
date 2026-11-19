package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.account.Credential
import co.magicpages.socialweb.data.account.GhostSite
import co.magicpages.socialweb.data.ghost.GhostAdminClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsDecodingTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val requests = mutableListOf<HttpRequestData>()
    private val site = GhostSite(
        title = "Example",
        siteUrl = "https://example.com/",
        adminApiUrl = "https://example.com/ghost/api/admin/",
        version = "6.78",
    )
    private val credential = Credential.Session(mapOf("ghost-admin-api-session" to "abc"))

    @Test
    fun `notifications map their kinds and carry the posts they are about`() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                requests += request
                if (request.url.encodedPath.endsWith("/identities/")) {
                    respond("""{"identities":[{"token":"jwt"}]}""", HttpStatusCode.OK)
                } else {
                    respond(NOTIFICATIONS, HttpStatusCode.OK)
                }
            },
        ) { expectSuccess = false }
        val ap = ActivityPubClient(http, json, GhostAdminClient(http, json))

        val page = ap.notifications(site, credential, next = "cursor-1")

        assertEquals("cursor-1", requests.last().url.parameters["next"])
        assertEquals("/.ghost/activitypub/v1/notifications", requests.last().url.encodedPath)
        assertEquals("cursor-2", page.next)
        assertEquals(
            listOf(
                Notification.Type.Reply,
                Notification.Type.Follow,
                Notification.Type.Like,
                Notification.Type.Repost,
                Notification.Type.Mention,
                Notification.Type.Unknown,
            ),
            page.notifications.map { it.type },
        )
        // Ids may come as numbers or strings; both read as text.
        assertEquals(listOf("12", "11", "10", "9", "8", "7"), page.notifications.map { it.id })

        val reply = page.notifications[0]
        assertEquals("@sturmsucht@mastodon.social", reply.actor.handle)
        assertEquals("Chris", reply.actor.name)
        assertFalse(reply.actor.followedByMe)
        assertEquals("https://mastodon.social/users/sturmsucht/statuses/1", reply.post?.id)
        assertEquals("<p>Nice one!</p>", reply.post?.content)
        assertEquals("https://mastodon.social/@sturmsucht/1", reply.post?.url)
        val parent = reply.inReplyTo
        assertEquals("https://example.com/.ghost/activitypub/note/abc", parent?.id)
        assertEquals("<p>Our note</p>", parent?.content)

        val follow = page.notifications[1]
        assertNull(follow.post)
        assertNull(follow.inReplyTo)
        assertTrue(follow.actor.followedByMe)

        val like = page.notifications[2]
        assertTrue(like.post?.isArticle == true)
        assertEquals("Our article", like.post?.title)
        assertEquals(3, like.post?.likeCount)
        assertTrue(like.post?.likedByMe == true)
        assertNull(like.inReplyTo)

        val mention = page.notifications[4]
        assertTrue(mention.post?.sensitive == true)
        assertEquals("Spoilers", mention.post?.contentWarning)

        // A kind from a newer service version still decodes, so one new type can't fail the whole page.
        assertEquals(Notification.Type.Unknown, page.notifications[5].type)
    }

    private companion object {
        val NOTIFICATIONS = """
            {
              "notifications": [
                {
                  "id": "12",
                  "createdAt": "2026-11-18T10:22:00.000Z",
                  "type": "reply",
                  "actor": {"id": "5", "name": "Chris", "url": "https://mastodon.social/@sturmsucht",
                            "handle": "@sturmsucht@mastodon.social", "avatarUrl": "https://mastodon.social/a.png",
                            "followedByMe": false},
                  "post": {"id": "https://mastodon.social/users/sturmsucht/statuses/1", "type": "note", "title": "",
                           "sensitive": false, "contentWarning": null, "content": "<p>Nice one!</p>",
                           "url": "https://mastodon.social/@sturmsucht/1", "likeCount": 0, "likedByMe": false,
                           "replyCount": 0, "repostCount": 0, "repostedByMe": false, "attachments": []},
                  "inReplyTo": {"id": "https://example.com/.ghost/activitypub/note/abc", "type": "note", "title": "",
                                "sensitive": false, "contentWarning": null, "content": "<p>Our note</p>",
                                "url": "https://example.com/note/abc"}
                },
                {
                  "id": 11,
                  "createdAt": "2026-11-18T09:00:00.000Z",
                  "type": "follow",
                  "actor": {"id": "6", "name": "", "url": "https://b.example/@bob", "handle": "@bob@b.example",
                            "avatarUrl": "", "followedByMe": true},
                  "post": null,
                  "inReplyTo": null
                },
                {
                  "id": "10",
                  "createdAt": "2026-11-17T09:00:00.000Z",
                  "type": "like",
                  "actor": {"id": "7", "name": "Dana", "url": null, "handle": "@dana@c.example", "avatarUrl": null,
                            "followedByMe": false},
                  "post": {"id": "https://example.com/.ghost/activitypub/article/x", "type": "article",
                           "title": "Our article", "sensitive": false, "contentWarning": null, "content": "<p>Body</p>",
                           "url": "https://example.com/our-article/", "likeCount": 3, "likedByMe": true,
                           "replyCount": 1, "repostCount": 0, "repostedByMe": false, "attachments": []},
                  "inReplyTo": null
                },
                {
                  "id": "9",
                  "createdAt": "2026-11-17T08:00:00.000Z",
                  "type": "repost",
                  "actor": {"id": "8", "name": "Eli", "url": null, "handle": "@eli@d.example", "avatarUrl": null,
                            "followedByMe": false},
                  "post": {"id": "https://example.com/.ghost/activitypub/note/q", "type": "note", "title": "",
                           "sensitive": false, "contentWarning": null, "content": "<p>Ours</p>", "url": "",
                           "attachments": []},
                  "inReplyTo": null
                },
                {
                  "id": "8",
                  "createdAt": "2026-11-16T08:00:00.000Z",
                  "type": "mention",
                  "actor": {"id": "9", "name": "Fay", "url": null, "handle": "@fay@e.example", "avatarUrl": null,
                            "followedByMe": false},
                  "post": {"id": "https://e.example/notes/m", "type": "note", "title": "", "sensitive": true,
                           "contentWarning": "Spoilers", "content": "<p>@you hi</p>", "url": "https://e.example/notes/m",
                           "attachments": []},
                  "inReplyTo": null
                },
                {
                  "id": "7",
                  "createdAt": "2026-11-15T08:00:00.000Z",
                  "type": "quote",
                  "actor": {"id": "10", "name": "Gil", "url": null, "handle": "@gil@f.example", "avatarUrl": null,
                            "followedByMe": false},
                  "post": null,
                  "inReplyTo": null
                }
              ],
              "next": "cursor-2"
            }
        """.trimIndent()
    }
}
