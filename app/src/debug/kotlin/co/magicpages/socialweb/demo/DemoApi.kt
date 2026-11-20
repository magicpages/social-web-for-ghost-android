package co.magicpages.socialweb.demo

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import co.magicpages.socialweb.data.activitypub.AccountSearchResults
import co.magicpages.socialweb.data.activitypub.Attachment
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.FollowsPage
import co.magicpages.socialweb.data.activitypub.Notification
import co.magicpages.socialweb.data.activitypub.NotificationPost
import co.magicpages.socialweb.data.activitypub.NotificationsPage
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.PostsPage
import co.magicpages.socialweb.data.activitypub.Profile
import co.magicpages.socialweb.data.activitypub.ReplyChain
import co.magicpages.socialweb.data.activitypub.SuggestedAccount
import co.magicpages.socialweb.data.activitypub.SuggestedAccountsPage
import co.magicpages.socialweb.data.activitypub.Topic
import co.magicpages.socialweb.data.activitypub.TopicsList
import co.magicpages.socialweb.data.ghost.AdminRecommendation
import co.magicpages.socialweb.data.ghost.isSameSite
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * The server behind demo mode: answers every request the app makes (Social Web service, Ghost Admin
 * API, public site pages, pictures) from [DemoContent] and a little in-memory state, so likes,
 * follows, replies, new notes and picture uploads work for as long as the process lives.
 *
 * Only reached through [DemoModule]'s interceptor while [DemoMode] is on. A request to a host outside
 * the demo world (the `.example` hosts, the signed-in site's own domain and KLIPY's API, answered
 * from bundled GIFs) fails like a missing network, and the demo world's hosts are answered here, so
 * a demo never leaks anything.
 */
@Singleton
class DemoApi @Inject constructor(@ApplicationContext private val context: Context) {
    private class Answer(val code: Int, val body: ByteArray, val type: String = JsonType)

    private class Upload(val bytes: ByteArray, val mime: String)

    private class FilePart(val mime: String, val bytes: ByteArray)

    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    private val posts = LinkedHashMap(DemoContent.posts)
    private val created = HashMap<String, Instant>()
    private val children = HashMap<String, MutableList<String>>().also { map ->
        for ((parent, ids) in DemoContent.replies) map[parent] = ids.toMutableList()
    }
    private val parents = HashMap(DemoContent.parents)
    private val following = DemoContent.everyone.filter { it.followedByMe }.mapTo(HashSet()) { it.handle }
    private val newNotes = ArrayList<String>()
    /** How often the Notes feed was loaded from its start; later loads find the notes in [DemoContent.arrivals]. */
    private var notesLoads = 0
    private val feedCursors = HashMap<String, String>()
    private val recommendations = DemoContent.defaultRecommendations.toMutableList()
    private val uploads = LinkedHashMap<String, Upload>()
    private var unread = 3
    private var nextUpload = 1
    private var nextPost = 1

    /** Answers [request], or throws an [IOException] for a host outside the demo world. */
    fun respond(request: Request): Response {
        val host = request.url.host
        if (!host.endsWith(".example") && host != DemoContent.me.domain && host != KlipyHost) {
            throw IOException("Demo mode has no network access to $host")
        }
        val path = request.url.encodedPath
        val answer = image(host, path) ?: synchronized(this) { route(request, host, path) }
        val now = System.currentTimeMillis()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(answer.code)
            .message(if (answer.code in 200..299) "OK" else "Error")
            .header("Content-Type", answer.type)
            .body(answer.body.toResponseBody(answer.type.toMediaType()))
            .sentRequestAtMillis(now)
            .receivedResponseAtMillis(now)
            .build()
    }

    // MARK: Routing

    /** Bundled placeholders and pictures uploaded in this session. Needs no state lock for the former. */
    private fun image(host: String, path: String): Answer? {
        DemoContent.imageName("https://$host$path")?.let { name ->
            if (!name.matches(Regex("[a-z0-9-]+"))) return notFound()
            return try {
                Answer(200, context.assets.open("demo/$name.webp").use { it.readBytes() }, "image/webp")
            } catch (_: IOException) {
                notFound()
            }
        }
        DemoContent.gifName("https://$host$path")?.let { name ->
            if (!name.matches(Regex("[a-z0-9-]+"))) return notFound()
            return try {
                Answer(200, context.assets.open("demo/gifs/$name.gif").use { it.readBytes() }, "image/gif")
            } catch (_: IOException) {
                notFound()
            }
        }
        if (path.startsWith("/content/images/")) {
            val upload = synchronized(this) { uploads[path.removePrefix("/content/images/")] } ?: return notFound()
            return Answer(200, upload.bytes, upload.mime)
        }
        return null
    }

    private fun route(request: Request, host: String, path: String): Answer = when {
        host == KlipyHost -> klipy(request, path)
        path.startsWith(ActivityPubPath) -> activityPub(request, host, path.removePrefix(ActivityPubPath))
        path.startsWith(AdminPath) -> admin(request, host, path.removePrefix(AdminPath))
        else -> publicPage(request, host, path)
    }

    /**
     * KLIPY's `featured` and `search`, from [DemoContent.gifs]: a search finds the GIFs whose
     * description has the term in it. Everything fits on one page.
     */
    private fun klipy(request: Request, path: String): Answer {
        val term = request.url.queryParameter("q").orEmpty().trim().lowercase()
        val found = when (path) {
            "/v2/featured" -> DemoContent.gifs
            "/v2/search" -> DemoContent.gifs.filter { (_, title) -> term in title.lowercase() }
            else -> return notFound()
        }
        return answerJson(
            buildJsonObject {
                putJsonArray("results") {
                    for ((name, title) in found) {
                        val media = buildJsonObject {
                            put("url", DemoContent.gif(name))
                            putJsonArray("dims") {
                                add(JsonPrimitive(200))
                                add(JsonPrimitive(150))
                            }
                        }
                        add(
                            buildJsonObject {
                                put("id", name)
                                put("content_description", title)
                                put("media_formats", buildJsonObject { put("gif", media) })
                            },
                        )
                    }
                }
                put("next", "")
            },
        )
    }

    private fun activityPub(request: Request, host: String, rest: String): Answer {
        val parts = rest.trim('/').split('/').map(Uri::decode)
        val first = parts[0]
        val second = parts.getOrNull(1).orEmpty()
        return if (request.method == "GET") {
            when (first) {
                "feed" -> when (second) {
                    "notes" -> {
                        val next = request.url.queryParameter("next")
                        if (next == null) notesLoads++
                        ok(PostsPage.serializer(), feedPage(notesEntries(), next))
                    }
                    "reader" -> ok(PostsPage.serializer(), feedPage(DemoContent.readerFeed, request.url.queryParameter("next")))
                    "discover" -> discover(parts.getOrNull(2).orEmpty())?.let { ok(PostsPage.serializer(), PostsPage(it)) } ?: notFound()
                    else -> notFound()
                }
                "posts" -> if (second == "me" && parts.getOrNull(2) == "liked") {
                    ok(PostsPage.serializer(), PostsPage(posts.keys.filter { posts.getValue(it).likedByMe }.sortedBy(::age).mapNotNull(::view)))
                } else {
                    ok(PostsPage.serializer(), PostsPage(postsBy(second)))
                }
                "notifications" -> if (second == "unread") {
                    answerJson(buildJsonObject { put("count", unread) })
                } else {
                    ok(NotificationsPage.serializer(), notifications())
                }
                "account" -> account(host, second, parts.getOrNull(3))
                "post" -> view(second)?.let { ok(Post.serializer(), it) } ?: notFound()
                "replies" -> replies(second)?.let { ok(ReplyChain.serializer(), it) } ?: notFound()
                "topics" -> ok(TopicsList.serializer(), TopicsList(DemoContent.topics.map { Topic(it.slug, it.name) }))
                "explore" -> ok(SuggestedAccountsPage.serializer(), SuggestedAccountsPage(explore(second)))
                "recommendations" -> ok(SuggestedAccountsPage.serializer(), SuggestedAccountsPage(explore("top")))
                "blocks" -> answerJson(
                    buildJsonObject {
                        putJsonArray(if (second == "domains") "blocked_domains" else "blocked_accounts") {}
                    },
                )
                "actions" -> ok(AccountSearchResults.serializer(), AccountSearchResults(search(request.url.queryParameter("query").orEmpty())))
                else -> notFound()
            }
        } else {
            mutate(request, host, request.method, parts)
        }
    }

    private fun mutate(request: Request, host: String, method: String, parts: List<String>): Answer {
        val first = parts[0]
        val verb = parts.getOrNull(1).orEmpty()
        val target = parts.getOrNull(2).orEmpty()
        when {
            first == "upload" -> return upload(request, host)
            first == "notifications" -> unread = 0
            first == "post" && method == "DELETE" -> delete(verb)
            first == "actions" -> when (verb) {
                "note" -> return createNote(request)
                "reply" -> return createReply(request, target)
                "like", "unlike" -> update(target) { it.copy(likedByMe = verb == "like", likeCount = (it.likeCount + if (verb == "like") 1 else -1).coerceAtLeast(0)) }
                "repost", "derepost" -> update(target) { it.copy(repostedByMe = verb == "repost", repostCount = (it.repostCount + if (verb == "repost") 1 else -1).coerceAtLeast(0)) }
                "follow" -> following += target
                "unfollow" -> following -= target
            }
        }
        return answerJson(buildJsonObject {})
    }

    // MARK: Posts

    private fun age(id: String): Long =
        created[id]?.let { Duration.between(it, Instant.now()).toMinutes() } ?: DemoContent.ageMinutes(id)

    /** [id] as the app sees it now: its own state plus a publishing time relative to now. */
    private fun view(id: String): Post? {
        val post = posts[id] ?: return null
        return post.copy(
            publishedAt = (created[id] ?: Instant.now().minusSeconds(DemoContent.ageMinutes(id) * 60)).toString(),
            author = post.author.copy(followedByMe = post.author.handle in following),
        )
    }

    /** The entries of the Notes feed now: what arrived since it was first loaded, the user's own notes, then the rest. */
    private fun notesEntries(): List<DemoContent.Entry> {
        val arrived = DemoContent.arrivals.take((notesLoads - 1).coerceIn(0, DemoContent.arrivals.size)).asReversed()
        return arrived + newNotes.map { DemoContent.Entry(it) } + DemoContent.notesFeed
    }

    private fun entryKey(entry: DemoContent.Entry) = entry.postId + "|" + entry.repostedBy?.handle

    /** One page of a feed. [next] is a cursor an earlier page handed out: the time of its last entry, as the service does. */
    private fun feedPage(entries: List<DemoContent.Entry>, next: String?): PostsPage {
        val start = if (next == null) {
            0
        } else {
            val last = feedCursors[next] ?: return PostsPage()
            entries.indexOfFirst { entryKey(it) == last } + 1
        }
        val page = entries.drop(start).take(FeedPageSize)
        val posts = page.mapNotNull { entry ->
            view(entry.postId)?.copy(repostedBy = entry.repostedBy?.author(entry.repostedBy.handle in following))
        }
        val last = page.lastOrNull()
        val cursor = if (last != null && start + page.size < entries.size) view(last.postId)?.publishedAt else null
        if (cursor != null && last != null) feedCursors[cursor] = entryKey(last)
        return PostsPage(posts, cursor)
    }

    private fun postsBy(handle: String): List<Post> {
        val wanted = if (handle == "me") DemoContent.me.handle else handle
        return posts.keys
            .filter { it !in parents && posts.getValue(it).author.handle == wanted }
            .sortedBy(::age)
            .mapNotNull(::view)
    }

    private fun update(id: String, change: (Post) -> Post) {
        posts[id]?.let { posts[id] = change(it) }
    }

    private fun delete(id: String) {
        posts.remove(id)
        newNotes.remove(id)
        parents.remove(id)?.let { parent ->
            children[parent]?.remove(id)
            update(parent) { it.copy(replyCount = (it.replyCount - 1).coerceAtLeast(0)) }
        }
    }

    private fun replies(id: String): ReplyChain? {
        val focus = view(id) ?: return null
        val ancestors = generateSequence(parents[id]) { parents[it] }.toList().reversed().mapNotNull(::view)
        val replies = children[id].orEmpty().mapNotNull { childId ->
            val child = view(childId) ?: return@mapNotNull null
            val chain = generateSequence(children[childId]?.firstOrNull()) { children[it]?.firstOrNull() }
                .take(3).toList().mapNotNull(::view)
            ReplyChain.Child(child, chain)
        }
        return ReplyChain(ReplyChain.Ancestors(ancestors), focus, replies)
    }

    private fun createNote(request: Request): Answer {
        val body = request.jsonBody()
        val post = publish(body.text("content"), body.image())
        newNotes.add(0, post.id)
        return answerJson(buildJsonObject { put("post", json.encodeToJsonElement(Post.serializer(), post)) })
    }

    private fun createReply(request: Request, parentId: String): Answer {
        val parent = posts[parentId] ?: return notFound()
        val body = request.jsonBody()
        val mention = DemoContent.byHandle[parent.author.handle]?.takeIf { it !== DemoContent.me }?.mention()
        val post = publish(body.text("content"), body.image(), mention)
        children.getOrPut(parentId) { ArrayList() }.add(post.id)
        parents[post.id] = parentId
        update(parentId) { it.copy(replyCount = it.replyCount + 1) }
        return answerJson(buildJsonObject { put("post", json.encodeToJsonElement(Post.serializer(), post)) })
    }

    /** Stores a note or reply from the signed-in site; the service turns plain text into HTML paragraphs and links. */
    private fun publish(text: String, image: Attachment?, mention: String? = null): Post {
        val id = "https://${DemoContent.me.domain}/.ghost/activitypub/note/demo-${nextPost++}"
        val html = text.trim().split(Regex("\n\\s*\n")).filter { it.isNotBlank() }.mapIndexed { index, paragraph ->
            val linked = escape(paragraph.trim()).replace(Regex("https?://[^\\s<]+")) { """<a href="${it.value}">${it.value}</a>""" }
            "<p>${if (index == 0 && mention != null) "$mention " else ""}${linked.replace("\n", "<br>")}</p>"
        }.joinToString("")
        val post = Post(
            id = id, content = html, url = id, attachments = listOfNotNull(image),
            author = DemoContent.me.author(), authoredByMe = true,
        )
        posts[id] = post
        created[id] = Instant.now()
        return post
    }

    private fun Request.jsonBody(): JsonObject {
        val buffer = Buffer()
        body?.writeTo(buffer)
        return runCatching { json.parseToJsonElement(buffer.readUtf8()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** The `image` of a note or reply body, as the attachment the service would store for it. */
    private fun JsonObject.image(): Attachment? {
        val image = this["image"] as? JsonObject ?: return null
        val url = image.text("url").takeIf { it.isNotBlank() } ?: return null
        val mime = uploads[url.substringAfterLast('/')]?.mime ?: "image/jpeg"
        return Attachment(type = "Image", mediaType = mime, name = image.text("altText").takeIf { it.isNotBlank() }, url = url)
    }

    // MARK: Pictures

    private fun upload(request: Request, host: String): Answer {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        val part = firstFilePart(buffer.readByteArray()) ?: return Answer(400, "{}".encodeToByteArray())
        val extension = when (part.mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val name = "demo-upload-${nextUpload++}.$extension"
        uploads[name] = Upload(part.bytes, part.mime)
        // A few uploads are plenty for a demo; older ones go so memory stays flat.
        while (uploads.size > MaxUploads) uploads.remove(uploads.keys.first())
        return answerJson(buildJsonObject { put("fileUrl", "https://$host/content/images/$name") })
    }

    /** The first part of a `multipart/form-data` body: the delimiter is its first line, so no header is needed. */
    private fun firstFilePart(body: ByteArray): FilePart? {
        val crlf = "\r\n".encodeToByteArray()
        val lineEnd = indexOf(body, crlf, 0).takeIf { it > 0 } ?: return null
        val delimiter = body.copyOfRange(0, lineEnd)
        val headersEnd = indexOf(body, "\r\n\r\n".encodeToByteArray(), lineEnd).takeIf { it >= 0 } ?: return null
        val headers = body.copyOfRange(lineEnd + 2, headersEnd).decodeToString()
        val mime = headers.lineSequence()
            .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
            ?.substringAfter(':')?.substringBefore(';')?.trim() ?: "application/octet-stream"
        val start = headersEnd + 4
        val end = indexOf(body, crlf + delimiter, start).takeIf { it >= 0 } ?: return null
        return FilePart(mime, body.copyOfRange(start, end))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // MARK: Accounts and discovery

    private fun account(host: String, who: String, relation: String?): Answer {
        val persona = if (who == "me") DemoContent.byDomain[host] ?: DemoContent.me else DemoContent.byHandle[who] ?: return notFound()
        if (relation != null) {
            val accounts = DemoContent.everyone.filter { it !== DemoContent.me && it !== persona }.take(9)
                .map { it.author(it.handle in following) }
            return ok(FollowsPage.serializer(), FollowsPage(accounts))
        }
        val followed = persona.handle in following
        val delta = if (persona === DemoContent.me || followed == persona.followedByMe) 0 else if (followed) 1 else -1
        val profile = Profile(
            id = (DemoContent.everyone.indexOf(persona) + 1).toString(), apId = persona.apId, name = persona.name, handle = persona.handle,
            avatarUrl = persona.avatar, bio = persona.bio, url = persona.url, bannerImageUrl = persona.bannerUrl,
            customFields = if (persona === DemoContent.elena) {
                mapOf("Website" to """<a href="https://elenamarsh.example/">elenamarsh.example</a>""")
            } else {
                emptyMap()
            },
            postCount = persona.posts, likedCount = 56, followingCount = persona.following,
            followerCount = persona.followers + delta, followsMe = persona !== DemoContent.me, followedByMe = followed,
        )
        return ok(Profile.serializer(), profile)
    }

    private fun suggested(persona: DemoContent.Persona) = SuggestedAccount(
        id = persona.apId, handle = persona.handle, name = persona.name, avatarUrl = persona.avatar,
        bio = persona.bio, url = persona.url, followedByMe = persona.handle in following,
    )

    private fun explore(slug: String) = DemoContent.topics.firstOrNull { it.slug == slug }?.people.orEmpty().map(::suggested)

    /** The posts of a topic: what the topic's people published, newest first; null for an unknown topic. */
    private fun discover(slug: String): List<Post>? {
        val handles = DemoContent.topics.firstOrNull { it.slug == slug }?.people?.map { it.handle } ?: return null
        return posts.keys
            .filter { it !in parents && posts.getValue(it).author.handle in handles }
            .sortedBy(::age)
            .mapNotNull(::view)
    }

    private fun search(query: String): List<Author> {
        val q = query.trim().removePrefix("@").lowercase()
        if (q.isEmpty()) return emptyList()
        return DemoContent.everyone.filter { persona ->
            persona !== DemoContent.me && (
                persona.name.lowercase().split(' ').any { it.startsWith(q) } ||
                    persona.handle.lowercase().contains(q)
                )
        }.map { it.author(it.handle in following) }
    }

    private fun notifications(): NotificationsPage {
        val issue = DemoContent.winterFerries.id
        // Likes, a reply, reposts, a mention and follows: one of each kind, newest first.
        fun summary(id: String): NotificationPost? = view(id)?.let {
            NotificationPost(
                id = it.id, type = if (it.isArticle) "article" else "note", title = it.title, content = it.content, url = it.url,
                likeCount = it.likeCount, replyCount = it.replyCount, repostCount = it.repostCount, likedByMe = it.likedByMe,
                repostedByMe = it.repostedByMe, attachments = it.attachments, sensitive = it.sensitive, contentWarning = it.contentWarning,
            )
        }
        var index = 0
        fun entry(type: Notification.Type, actor: DemoContent.Persona, minutes: Long, post: String? = null, inReplyTo: String? = null) =
            Notification(
                id = "demo-notification-${index++}", createdAt = Instant.now().minusSeconds(minutes * 60).toString(), type = type,
                actor = actor.author(actor.handle in following), post = post?.let(::summary), inReplyTo = inReplyTo?.let(::summary),
            )

        val likers = listOf(DemoContent.elena, DemoContent.kofi, DemoContent.hana, DemoContent.priya, DemoContent.lucas, DemoContent.noor)
        val list = buildList {
            add(entry(Notification.Type.Reply, DemoContent.ines, 18, children[issue]?.firstOrNull(), inReplyTo = issue))
            likers.forEachIndexed { n, who -> add(entry(Notification.Type.Like, who, 25L + n * 7, issue)) }
            add(entry(Notification.Type.Repost, DemoContent.kerning, 95, DemoContent.myArticle.id))
            add(entry(Notification.Type.Repost, DemoContent.byteSized, 130, DemoContent.myArticle.id))
            add(entry(Notification.Type.Mention, DemoContent.sam, 400, DemoContent.samMention.id))
            listOf(DemoContent.priya, DemoContent.lucas, DemoContent.noor).forEachIndexed { n, who ->
                add(entry(Notification.Type.Follow, who, 600L + n * 55))
            }
        }
        return NotificationsPage(list)
    }

    // MARK: Ghost Admin API

    private fun admin(request: Request, host: String, rest: String): Answer {
        val persona = DemoContent.byDomain[host]?.takeIf { it.isGhost } ?: return notFound()
        val parts = rest.trim('/').split('/').map(Uri::decode)
        return when (parts[0]) {
            "site" -> answerJson(
                buildJsonObject {
                    put(
                        "site",
                        buildJsonObject {
                            put("title", persona.name)
                            put("url", "https://${persona.domain}/")
                            put("version", "6.10.0")
                            put("icon", persona.avatar)
                            put("accent_color", DemoContent.AccentColor)
                        },
                    )
                },
            )
            "identities" -> answerJson(
                buildJsonObject {
                    putJsonArray("identities") { add(buildJsonObject { put("token", "demo.demo.demo") }) }
                },
            )
            // A KLIPY key turns on the composers' GIF button; the demo's KLIPY is answered by [klipy].
            "config" -> answerJson(
                buildJsonObject {
                    put(
                        "config",
                        buildJsonObject {
                            put("klipy", buildJsonObject { put("apiKey", "demo"); put("contentFilter", "off") })
                        },
                    )
                },
            )
            "recommendations" -> adminRecommendations(request, parts)
            else -> notFound()
        }
    }

    private fun adminRecommendations(request: Request, parts: List<String>): Answer {
        fun item(url: String, stored: Boolean) = AdminRecommendation(
            id = if (stored) url.toUri().host else null, url = url, title = titleOf(url),
            excerpt = DemoContent.byDomain[url.toUri().host]?.bio?.replace(Regex("<[^>]+>"), ""),
        )

        fun list(items: List<AdminRecommendation>) = answerJson(
            buildJsonObject {
                putJsonArray("recommendations") { items.forEach { add(json.encodeToJsonElement(AdminRecommendation.serializer(), it)) } }
            },
        )

        fun requested(): String? {
            val entries = request.jsonBody()["recommendations"] as? JsonArray
            return (entries?.firstOrNull() as? JsonObject)?.text("url")?.takeIf { it.isNotBlank() }
        }

        return when {
            request.method == "GET" -> list(recommendations.map { item(it, stored = true) })
            request.method == "DELETE" -> {
                recommendations.removeAll { it.toUri().host == parts.getOrNull(1) }
                Answer(200, "{}".encodeToByteArray())
            }
            parts.getOrNull(1) == "check" -> {
                val url = requested() ?: return Answer(422, "{}".encodeToByteArray())
                list(listOf(item(url, stored = recommendations.any { isSameSite(it, url) })))
            }
            else -> {
                val url = requested() ?: return Answer(422, "{}".encodeToByteArray())
                if (recommendations.none { isSameSite(it, url) }) recommendations += url
                list(listOf(item(url, stored = true)))
            }
        }
    }

    private fun titleOf(url: String): String {
        val host = url.toUri().host.orEmpty()
        return DemoContent.byDomain[host]?.name
            ?: (DemoContent.websites + DemoContent.quietPress).firstOrNull { it.domain == host }?.name
            ?: host
    }

    // MARK: Public pages

    private fun publicPage(request: Request, host: String, path: String): Answer {
        val persona = DemoContent.byDomain[host]
        val trimmed = path.trimEnd('/')
        return when {
            trimmed == "/.well-known/recommendations.json" -> {
                if (persona == null || !persona.isGhost) return notFound()
                val urls = if (persona === DemoContent.me) recommendations else DemoContent.defaultRecommendations.filterNot { isSameSite(it, persona.url) }
                answerJson(buildJsonArray { urls.forEach { add(buildJsonObject { put("url", it) }) } })
            }
            trimmed == "/members/api/site" ->
                if (persona?.isGhost == true || host == DemoContent.quietPress.domain) {
                    answerJson(
                        buildJsonObject {
                            put(
                                "site",
                                buildJsonObject {
                                    put("title", persona?.name ?: DemoContent.quietPress.name)
                                    put("url", "https://$host/")
                                    put("allow_self_signup", true)
                                    put("allow_external_signup", true)
                                },
                            )
                        },
                    )
                } else {
                    notFound()
                }
            trimmed == "/.well-known/webfinger" -> {
                if (persona == null || !persona.isGhost) return notFound()
                answerJson(
                    buildJsonObject {
                        put("subject", "acct:${persona.user}@${persona.domain}")
                        putJsonArray("links") {
                            add(
                                buildJsonObject {
                                    put("rel", "self")
                                    put("type", "application/activity+json")
                                    put("href", persona.apId)
                                },
                            )
                        }
                    },
                )
            }
            else -> page(host, path, persona)
        }
    }

    /** A page with the tags link cards and site lookups read; the body is a stub nobody sees. */
    private fun page(host: String, path: String, persona: DemoContent.Persona?): Answer {
        val article = posts.values.firstOrNull { it.isArticle && it.url.toUri().let { uri -> uri.host == host && uri.path == path } }
        val link = DemoContent.linkPages["$host$path"]
        val site = DemoContent.websites.firstOrNull { it.domain == host } ?: DemoContent.quietPress.takeIf { it.domain == host }
        val head = when {
            article != null -> Head(article.title, article.excerpt, article.featureImageUrl, article.author.name, persona?.avatar)
            link != null -> Head(link.title, link.description, DemoContent.image(link.image), link.site, null)
            path == "/" && persona != null -> Head(persona.name, persona.bio.replace(Regex("<[^>]+>"), ""), persona.bannerUrl, persona.name, persona.avatar)
            path == "/" && site != null -> Head(site.name, "A site on the demo web.", null, site.name, null)
            else -> return notFound()
        }
        val html = buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\"><title>${escape(head.title)}</title>")
            append("""<meta property="og:title" content="${escape(head.title)}">""")
            append("""<meta property="og:description" content="${escape(head.description)}">""")
            append("""<meta property="og:site_name" content="${escape(head.site)}">""")
            head.image?.let { append("""<meta property="og:image" content="${escape(it)}">""") }
            head.icon?.let { append("""<link rel="icon" href="${escape(it)}">""") }
            append("""<meta name="description" content="${escape(head.description)}">""")
            append("</head><body><h1>${escape(head.title)}</h1></body></html>")
        }
        return Answer(200, html.encodeToByteArray(), "text/html; charset=utf-8")
    }

    private class Head(val title: String, val description: String, val image: String?, val site: String, val icon: String?)

    // MARK: Helpers

    private fun <T> ok(serializer: KSerializer<T>, value: T) = answerJson(json.encodeToJsonElement(serializer, value))

    private fun answerJson(element: JsonElement) = Answer(200, element.toString().encodeToByteArray())

    private fun notFound() = Answer(404, "{}".encodeToByteArray())

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        /** Entries per page of the Notes and Reader feeds, so that they page like the real ones. */
        const val FeedPageSize = 8
        const val ActivityPubPath = "/.ghost/activitypub/v1/"
        const val AdminPath = "/ghost/api/admin/"
        const val JsonType = "application/json"
        const val KlipyHost = "api.klipy.com"
        const val MaxUploads = 16
    }
}
