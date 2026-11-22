package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.account.Credential
import co.magicpages.socialweb.data.account.GhostSite
import co.magicpages.socialweb.data.ghost.GhostAdminClient
import co.magicpages.socialweb.data.ghost.GhostException
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Client for the site's social web API under `/.ghost/activitypub/v1/`.
 * Authenticates with the identity JWT from [GhostAdminClient.identityToken], refreshing it once
 * when the service rejects it.
 */
@Singleton
class ActivityPubClient @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
    private val ghost: GhostAdminClient,
    // Every profile the client loads teaches this map the account's current avatar.
    private val avatars: FreshAvatars = FreshAvatars(),
) {
    private val tokenLock = Mutex()
    private var cachedToken: CachedToken? = null

    suspend fun notesFeed(site: GhostSite, credential: Credential, next: String? = null): PostsPage =
        decode(send(site, credential, HttpMethod.Get, "feed/notes", params = arrayOf("next" to next)))

    /** The Reader: articles (long-form posts) from followed accounts. */
    suspend fun readerFeed(site: GhostSite, credential: Credential, next: String? = null): PostsPage =
        decode(send(site, credential, HttpMethod.Get, "feed/reader", params = arrayOf("next" to next)))

    /** Newest first. [limit] defaults to the service's 20 and is capped at 100 there. */
    suspend fun notifications(site: GhostSite, credential: Credential, next: String? = null, limit: Int? = null): NotificationsPage =
        decode(send(site, credential, HttpMethod.Get, "notifications", params = arrayOf("next" to next, "limit" to limit?.toString())))

    suspend fun unreadNotificationCount(site: GhostSite, credential: Credential): Int =
        decode<UnreadCount>(send(site, credential, HttpMethod.Get, "notifications/unread/count")).count

    suspend fun markNotificationsRead(site: GhostSite, credential: Credential) {
        send(site, credential, HttpMethod.Put, "notifications/unread/reset")
    }

    /**
     * Stores an image for a note and returns its URL. The service takes JPEG, PNG, WebP, GIF and
     * HEIC up to [MAX_IMAGE_BYTES]; it answers 413 for larger files and 415 for other types.
     */
    suspend fun uploadImage(site: GhostSite, credential: Credential, bytes: ByteArray, fileName: String, mimeType: String): String {
        val form = MultiPartFormDataContent(
            formData {
                append(
                    "file",
                    bytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, mimeType)
                        append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                    },
                )
            },
        )
        return decode<UploadedImage>(send(site, credential, HttpMethod.Post, "upload/image", body = form)).fileUrl
    }

    /** Publishes a note from plain text; the service adds paragraphs, links and mentions. */
    suspend fun createNote(site: GhostSite, credential: Credential, content: String, image: NoteImage? = null): Post =
        decode<CreatedNote>(
            send(site, credential, HttpMethod.Post, "actions/note", body = jsonBody(NoteBody.serializer(), NoteBody(content, image))),
        ).post

    /** The site's own fediverse account. Fails when the social web is off for the site. */
    suspend fun currentAccount(site: GhostSite, credential: Credential): Account =
        decode<Account>(send(site, credential, HttpMethod.Get, "account/me")).also { avatars.record(it) }

    /**
     * Replaces the site's own profile (`PUT account`, Owner and Administrator only). The service takes
     * every field each time and has no partial update: [avatarUrl] and [bannerImageUrl] are
     * uploaded-image URLs, or "" for none. 400 means a field was missing or not text.
     */
    suspend fun updateAccount(
        site: GhostSite,
        credential: Credential,
        name: String,
        username: String,
        bio: String,
        avatarUrl: String,
        bannerImageUrl: String,
    ) {
        send(
            site,
            credential,
            HttpMethod.Put,
            "account",
            body = jsonBody(AccountUpdateBody.serializer(), AccountUpdateBody(name, bio, username, avatarUrl, bannerImageUrl)),
        )
    }

    /** [handle] is `@user@domain`, or `me` for the site's own account. */
    suspend fun profile(site: GhostSite, credential: Credential, handle: String): Profile =
        decode<Profile>(send(site, credential, HttpMethod.Get, "account/${handle.encodeURLPathPart()}")).also { avatars.record(it) }

    /** Posts and reposts by [handle] (or `me`), newest first. */
    suspend fun postsBy(site: GhostSite, credential: Credential, handle: String, next: String? = null): PostsPage =
        decode(send(site, credential, HttpMethod.Get, "posts/${handle.encodeURLPathPart()}", params = arrayOf("next" to next)))

    /** Posts the site's own account liked. */
    suspend fun likedPosts(site: GhostSite, credential: Credential, next: String? = null): PostsPage =
        decode(send(site, credential, HttpMethod.Get, "posts/me/liked", params = arrayOf("next" to next)))

    /**
     * Who follows [handle] (or `me`), or whom it follows. Remote servers that hide these lists
     * come back as an empty page.
     */
    suspend fun follows(site: GhostSite, credential: Credential, handle: String, list: FollowList, next: String? = null): FollowsPage =
        decode(send(site, credential, HttpMethod.Get, "account/${handle.encodeURLPathPart()}/follows/${list.path}", params = arrayOf("next" to next)))

    /**
     * Accounts the service knows whose name, username or domain starts a word with [query] (two
     * characters at least), at most 20. A full `@user@domain` the service hasn't met is looked up
     * on its server.
     */
    suspend fun searchAccounts(site: GhostSite, credential: Credential, query: String): List<Author> =
        decode<AccountSearchResults>(send(site, credential, HttpMethod.Get, "actions/search", params = arrayOf("query" to query))).accounts

    /**
     * One post by its ActivityPub id or web URL. The service fetches posts it hasn't seen from
     * their server; ones it can't reach or that aren't posts come back as 404.
     */
    suspend fun post(site: GhostSite, credential: Credential, id: String): Post =
        decode(send(site, credential, HttpMethod.Get, "post/${id.encodeURLParameter()}"))

    /** A post with its ancestors and a page of replies; [next] pages through the replies. */
    suspend fun replies(site: GhostSite, credential: Credential, postId: String, next: String? = null): ReplyChain =
        decode(send(site, credential, HttpMethod.Get, "replies/${postId.encodeURLParameter()}", params = arrayOf("next" to next)))

    suspend fun follow(site: GhostSite, credential: Credential, handle: String) {
        send(site, credential, HttpMethod.Post, "actions/follow/${handle.encodeURLPathPart()}")
    }

    suspend fun unfollow(site: GhostSite, credential: Credential, handle: String) {
        send(site, credential, HttpMethod.Post, "actions/unfollow/${handle.encodeURLPathPart()}")
    }

    suspend fun like(site: GhostSite, credential: Credential, postId: String) {
        send(site, credential, HttpMethod.Post, "actions/like/${postId.encodeURLParameter()}")
    }

    suspend fun unlike(site: GhostSite, credential: Credential, postId: String) {
        send(site, credential, HttpMethod.Post, "actions/unlike/${postId.encodeURLParameter()}")
    }

    suspend fun repost(site: GhostSite, credential: Credential, postId: String) {
        send(site, credential, HttpMethod.Post, "actions/repost/${postId.encodeURLParameter()}")
    }

    suspend fun derepost(site: GhostSite, credential: Credential, postId: String) {
        send(site, credential, HttpMethod.Post, "actions/derepost/${postId.encodeURLParameter()}")
    }

    /**
     * Replies with plain text and, optionally, an uploaded [image]; the service escapes the text,
     * adds paragraphs and links, and mentions the author.
     */
    suspend fun reply(site: GhostSite, credential: Credential, postId: String, content: String, image: NoteImage? = null) {
        send(
            site,
            credential,
            HttpMethod.Post,
            "actions/reply/${postId.encodeURLParameter()}",
            body = jsonBody(ReplyBody.serializer(), ReplyBody(content, image)),
        )
    }

    /** Deletes one of the site's own posts; the service refuses (403) for anyone else's. */
    suspend fun deletePost(site: GhostSite, credential: Credential, postId: String) {
        send(site, credential, HttpMethod.Delete, "post/${postId.encodeURLParameter()}")
    }

    /**
     * Blocks the account with ActivityPub id [accountId]. The service also ends follows both
     * ways and drops the account's notifications.
     */
    suspend fun block(site: GhostSite, credential: Credential, accountId: String) {
        send(site, credential, HttpMethod.Post, "actions/block/${accountId.encodeURLParameter()}")
    }

    suspend fun unblock(site: GhostSite, credential: Credential, accountId: String) {
        send(site, credential, HttpMethod.Post, "actions/unblock/${accountId.encodeURLParameter()}")
    }

    /** Blocks every account on [host]; the service also ends follows both ways with them. */
    suspend fun blockDomain(site: GhostSite, credential: Credential, host: String) {
        send(site, credential, HttpMethod.Post, "actions/block/domain/${"https://$host".encodeURLParameter()}")
    }

    suspend fun unblockDomain(site: GhostSite, credential: Credential, host: String) {
        send(site, credential, HttpMethod.Post, "actions/unblock/domain/${"https://$host".encodeURLParameter()}")
    }

    /** Every blocked account, unpaged. */
    suspend fun blockedAccounts(site: GhostSite, credential: Credential): List<Author> =
        decode<BlockedAccounts>(send(site, credential, HttpMethod.Get, "blocks/accounts")).accounts

    /** The hosts of every blocked server, unpaged. */
    suspend fun blockedDomains(site: GhostSite, credential: Credential): List<String> =
        decode<BlockedDomains>(send(site, credential, HttpMethod.Get, "blocks/domains")).domains
            .map { Url(it.url).host }

    /** The groups of suggested accounts, in the service's order. */
    suspend fun topics(site: GhostSite, credential: Credential): List<Topic> =
        decode<TopicsList>(send(site, credential, HttpMethod.Get, "topics")).topics

    /** A page of the accounts suggested under [topic]; [next] is the previous page's offset. */
    suspend fun explore(site: GhostSite, credential: Credential, topic: String, next: String? = null): SuggestedAccountsPage =
        decode(send(site, credential, HttpMethod.Get, "explore/${topic.encodeURLPathPart()}", params = arrayOf("next" to next)))

    /** A page of the posts the service files under [topic], newest first; [next] is the previous page's cursor. */
    suspend fun discoverFeed(site: GhostSite, credential: Credential, topic: String, next: String? = null): PostsPage =
        decode(send(site, credential, HttpMethod.Get, "feed/discover/${topic.encodeURLPathPart()}", params = arrayOf("next" to next)))

    /** Accounts suggested for the site, from its topics first; none it already follows. */
    suspend fun recommendations(site: GhostSite, credential: Credential, limit: Int = 20): List<SuggestedAccount> =
        decode<SuggestedAccountsPage>(
            send(site, credential, HttpMethod.Get, "recommendations", params = arrayOf("limit" to limit.toString())),
        ).accounts

    /** Whether the account wants media marked sensitive shown without a tap (shared with Ghost Admin). */
    suspend fun preferences(site: GhostSite, credential: Credential): Preferences =
        decode(send(site, credential, HttpMethod.Get, "preferences"))

    /** Saves whether to show sensitive media; the service answers with the preferences it stored. */
    suspend fun setShowSensitiveMedia(site: GhostSite, credential: Credential, show: Boolean): Preferences =
        decode(
            send(site, credential, HttpMethod.Put, "preferences", body = jsonBody(PreferencesBody.serializer(), PreferencesBody(show))),
        )

    /**
     * Starts bridging the site's account to Bluesky. The Bluesky handle isn't known yet in the
     * answer: Bridgy Fed creates the account in its own time, see [confirmBlueskyHandle].
     */
    suspend fun enableBluesky(site: GhostSite, credential: Credential): BlueskyStatus =
        decode(send(site, credential, HttpMethod.Post, "actions/bluesky/enable", version = "v2"))

    /** Asks whether Bridgy Fed has created the Bluesky account yet; 400 when sharing isn't enabled. */
    suspend fun confirmBlueskyHandle(site: GhostSite, credential: Credential): BlueskyStatus =
        decode(send(site, credential, HttpMethod.Post, "actions/bluesky/confirm-handle", version = "v2"))

    suspend fun disableBluesky(site: GhostSite, credential: Credential) {
        send(site, credential, HttpMethod.Post, "actions/bluesky/disable", version = "v2")
    }

    private suspend fun send(
        site: GhostSite,
        credential: Credential,
        method: HttpMethod,
        path: String,
        body: OutgoingContent? = null,
        params: Array<out Pair<String, String?>> = emptyArray(),
        version: String = "v1",
    ): HttpResponse {
        val url = "${site.siteUrl}.ghost/activitypub/$version/$path"
        val token = token(site, credential, rejected = null)
        var response = request(method, url, token, body, params)
        if (response.rejectsToken()) {
            response = request(method, url, token(site, credential, rejected = token), body, params)
        }
        if (!response.status.isSuccess()) {
            throw SocialWebException(response.status.value, response.errorMessage())
        }
        return response
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T = try {
        json.decodeFromString<T>(response.bodyAsText())
    } catch (e: SerializationException) {
        throw SocialWebException(response.status.value, e.message)
    }

    private fun <T> jsonBody(serializer: KSerializer<T>, value: T): OutgoingContent =
        TextContent(json.encodeToString(serializer, value), ContentType.Application.Json)

    private suspend fun request(
        method: HttpMethod,
        url: String,
        token: String,
        body: OutgoingContent?,
        params: Array<out Pair<String, String?>>,
    ): HttpResponse = try {
        http.request(url) {
            this.method = method
            bearerAuth(token)
            header(HttpHeaders.Accept, "application/activity+json")
            for ((key, value) in params) if (value != null) parameter(key, value)
            if (body != null) setBody(body)
        }
    } catch (e: IOException) {
        throw GhostException.Network(e)
    }

    /** The cached token, or a fresh one if there is none or it is the [rejected] one. */
    private suspend fun token(site: GhostSite, credential: Credential, rejected: String?): String =
        tokenLock.withLock {
            val cached = cachedToken
            // Parallel requests that all hit the same expired token share one refresh.
            if (cached != null && cached.site == site && cached.credential == credential && cached.token != rejected) {
                return@withLock cached.token
            }
            ghost.identityToken(site, credential).also { cachedToken = CachedToken(site, credential, it) }
        }

    /**
     * Identity JWTs live for minutes. The service doesn't answer an expired one with 401: it treats
     * the caller as anonymous and refuses with 403 `ROLE_MISSING`, the same as for a bad role.
     */
    private suspend fun HttpResponse.rejectsToken(): Boolean = when (status) {
        HttpStatusCode.Unauthorized -> true
        HttpStatusCode.Forbidden -> errorBody()?.code == "ROLE_MISSING"
        else -> false
    }

    private suspend fun HttpResponse.errorMessage(): String? = errorBody()?.let { it.message ?: it.error }

    private suspend fun HttpResponse.errorBody(): ErrorBody? = try {
        json.decodeFromString<ErrorBody>(bodyAsText())
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    @Serializable
    private data class ErrorBody(val message: String? = null, val error: String? = null, val code: String? = null)

    @Serializable
    private data class ReplyBody(val content: String, val image: NoteImage? = null)

    @Serializable
    private data class NoteBody(val content: String, val image: NoteImage? = null)

    @Serializable
    private data class CreatedNote(val post: Post)

    @Serializable
    private data class UploadedImage(val fileUrl: String)

    @Serializable
    private data class UnreadCount(val count: Int = 0)

    @Serializable
    private data class AccountUpdateBody(
        val name: String,
        val bio: String,
        val username: String,
        val avatarUrl: String,
        val bannerImageUrl: String,
    )

    /** No default, so the flag is always sent: the service rejects a body without it. */
    @Serializable
    private data class PreferencesBody(val showSensitiveMedia: Boolean)

    private data class CachedToken(val site: GhostSite, val credential: Credential, val token: String)

    companion object {
        const val MAX_IMAGE_BYTES = 5L * 1024 * 1024
    }
}

class SocialWebException(val status: Int, val detail: String?) : Exception("HTTP $status: $detail")
