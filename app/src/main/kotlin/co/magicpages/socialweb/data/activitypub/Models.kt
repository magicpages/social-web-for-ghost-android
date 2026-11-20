package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.ghost.fediPubNoteUrl
import co.magicpages.socialweb.data.ghost.isGhostActivityPubUrl
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// Shapes follow the PostDTO/AccountDTO types of Ghost's ActivityPub service
// (TryGhost/ActivityPub src/http/api/types.ts). Fields are lenient: remote servers
// leave plenty of them empty.

/**
 * The service's TypeScript types say ids are strings, but `account/:handle` sends the
 * internal account id as a JSON number while feeds send strings. Accept both.
 */
internal object FlexibleIdSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleId", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String =
        (decoder as? JsonDecoder)?.decodeJsonElement()?.jsonPrimitive?.content ?: decoder.decodeString()
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/**
 * For posts it fetches from a remote outbox, the service passes raw ActivityStreams values
 * through. JSON-LD writes a single attachment as a bare object rather than a one-item array,
 * and URLs may be Link/Image objects or lists of them. Normalise all of that, and drop
 * attachments with no usable URL.
 */
internal object AttachmentListSerializer :
    JsonTransformingSerializer<List<Attachment>>(ListSerializer(Attachment.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val items = when (element) {
            is JsonArray -> element
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        return JsonArray(
            items.mapNotNull { item ->
                if (item !is JsonObject) return@mapNotNull null
                val url = linkHref(item["url"]) ?: return@mapNotNull null
                JsonObject(item + ("url" to JsonPrimitive(url)))
            },
        )
    }
}

/**
 * `metadata.ghostAuthors`: keeps the entries that are objects with a usable name, so one odd
 * entry (a null, a number) costs a name rather than the whole post.
 */
internal object GhostAuthorListSerializer :
    JsonTransformingSerializer<List<GhostAuthor>>(ListSerializer(GhostAuthor.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val items = element as? JsonArray ?: return JsonArray(emptyList())
        return JsonArray(
            items.filter { item ->
                val name = (item as? JsonObject)?.get("name") as? JsonPrimitive
                name != null && name.isString && name.content.isNotBlank()
            },
        )
    }
}

/** A URL that may arrive as a string, a Link/Image object, or a list of either. */
internal object LinkUrlSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("LinkUrl", PrimitiveKind.STRING).nullable
    override fun deserialize(decoder: Decoder): String? =
        if (decoder is JsonDecoder) linkHref(decoder.decodeJsonElement()) else decoder.decodeString()
    override fun serialize(encoder: Encoder, value: String?) =
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
}

/** [LinkUrlSerializer] for fields that fall back to an empty string. */
internal object LinkUrlOrEmptySerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("LinkUrlOrEmpty", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String = LinkUrlSerializer.deserialize(decoder).orEmpty()
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** A pixel size that may be a number, a decimal or text; anything unusable is `null`, never a decode failure. */
internal object LenientDimensionSerializer : KSerializer<Int?> {
    override val descriptor = PrimitiveSerialDescriptor("LenientDimension", PrimitiveKind.INT).nullable
    override fun deserialize(decoder: Decoder): Int? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeInt()
        return (element as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1 }?.toInt()
    }
    override fun serialize(encoder: Encoder, value: Int?) =
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
}

private fun linkHref(url: JsonElement?): String? = when (url) {
    is JsonPrimitive -> url.contentOrNull?.takeIf { url.isString && it.isNotBlank() }
    is JsonObject -> linkHref(url["href"] ?: url["url"])
    is JsonArray -> url.firstNotNullOfOrNull { linkHref(it) }
    else -> null
}

@Serializable
data class PostsPage(
    val posts: List<Post> = emptyList(),
    val next: String? = null,
)

@Serializable
data class Post(
    val id: String,
    val type: Int = TYPE_NOTE,
    val title: String = "",
    val excerpt: String = "",
    val summary: String? = null,
    val sensitive: Boolean = false,
    val contentWarning: String? = null,
    val content: String = "",
    @Serializable(with = LinkUrlOrEmptySerializer::class) val url: String = "",
    @Serializable(with = LinkUrlSerializer::class) val featureImageUrl: String? = null,
    val publishedAt: String? = null,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
    val replyCount: Int = 0,
    val readingTimeMinutes: Int = 0,
    @Serializable(with = AttachmentListSerializer::class) val attachments: List<Attachment> = emptyList(),
    val author: Author,
    val authoredByMe: Boolean = false,
    val repostCount: Int = 0,
    val repostedByMe: Boolean = false,
    val repostedBy: Author? = null,
    /** Extra data on the site's own Ghost articles (their writers); only the single-post route sends it. */
    val metadata: PostMetadata? = null,
) {
    val isArticle: Boolean get() = type == TYPE_ARTICLE
    val ghostAuthors: List<GhostAuthor> get() = metadata?.ghostAuthors.orEmpty()
    val isDeleted: Boolean get() = type == TYPE_TOMBSTONE
    val images: List<Attachment> get() = attachments.filter { it.mediaType?.startsWith("image/") == true || it.type == "Image" }

    /**
     * A page a browser can show, or null. A Ghost note's [url] is its ActivityPub ID, which serves
     * JSON; articles and posts from other servers (Mastodon and others) have real pages.
     */
    val webUrl: String? get() = url.takeIf { it.isNotBlank() && !isGhostActivityPubUrl(it) }

    /** The link to share: [webUrl], or for a Ghost note, which has none, its page on fedi.pub. */
    val shareUrl: String? get() = webUrl ?: fediPubNoteUrl(id, author.handle)

    companion object {
        const val TYPE_NOTE = 0
        const val TYPE_ARTICLE = 1
        const val TYPE_TOMBSTONE = 2
    }
}

/** Only filled for articles published by the signed-in user's own Ghost site; extra keys are ignored. */
@Serializable
data class PostMetadata(
    @Serializable(with = GhostAuthorListSerializer::class) val ghostAuthors: List<GhostAuthor> = emptyList(),
)

/** A staff user who wrote an article of the site; the post's `author` is the site's account. */
@Serializable
data class GhostAuthor(
    val name: String,
    @SerialName("profile_image") @Serializable(with = LinkUrlSerializer::class) val profileImage: String? = null,
)

@Serializable
data class Attachment(
    val type: String? = null,
    val mediaType: String? = null,
    /** Alt text. */
    val name: String? = null,
    val url: String,
    /** A video's poster, as Bridgy Fed sends it (`image`: a Link/Image object). */
    @SerialName("image") @Serializable(with = LinkUrlSerializer::class) val posterUrl: String? = null,
    /** Pixel size, which Mastodon sends for images and videos; some servers write it as text or a decimal. */
    @Serializable(with = LenientDimensionSerializer::class) val width: Int? = null,
    @Serializable(with = LenientDimensionSerializer::class) val height: Int? = null,
)

@Serializable
data class Author(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    val handle: String,
    val name: String = "",
    // Remote outbox pages pass the actor's icon and url through raw, so either may be a Link.
    @Serializable(with = LinkUrlSerializer::class) val avatarUrl: String? = null,
    @Serializable(with = LinkUrlSerializer::class) val url: String? = null,
    val followedByMe: Boolean = false,
)

/** A page of `account/:handle/follows/:type`; the accounts are the service's MinimalAccountDTO. */
@Serializable
data class FollowsPage(
    val accounts: List<Author> = emptyList(),
    val next: String? = null,
)

/** `actions/search`; the accounts carry id, name, handle, avatar and whether we follow them. */
@Serializable
data class AccountSearchResults(val accounts: List<Author> = emptyList())

/** Which side of a follow relationship to list. [path] is the API's `:type` segment. */
enum class FollowList(val path: String) { Followers("followers"), Following("following") }

/** `blocks/accounts`: everyone the site's account blocked, as MinimalAccountDTOs. */
@Serializable
data class BlockedAccounts(@SerialName("blocked_accounts") val accounts: List<Author> = emptyList())

/** `blocks/domains`: every blocked server as `https://<host>`. */
@Serializable
data class BlockedDomains(@SerialName("blocked_domains") val domains: List<BlockedDomain> = emptyList())

@Serializable
data class BlockedDomain(val url: String)

/** A group of accounts the service suggests following; `slug` names it in `explore/:slug`. */
@Serializable
data class Topic(val slug: String, val name: String)

@Serializable
data class TopicsList(val topics: List<Topic> = emptyList())

/** An account the service suggests following (its ExploreAccountDTO); [id] is its ActivityPub id. */
@Serializable
data class SuggestedAccount(
    val id: String,
    val handle: String,
    val name: String = "",
    @Serializable(with = LinkUrlSerializer::class) val avatarUrl: String? = null,
    /** HTML. */
    val bio: String = "",
    @Serializable(with = LinkUrlSerializer::class) val url: String? = null,
    val followedByMe: Boolean = false,
) {
    fun toAuthor() = Author(id = id, handle = handle, name = name, avatarUrl = avatarUrl, url = url, followedByMe = followedByMe)
}

/** `explore/:slug` and `recommendations`; [next] is an offset, null on the last page. */
@Serializable
data class SuggestedAccountsPage(
    val accounts: List<SuggestedAccount> = emptyList(),
    val next: String? = null,
)

@Serializable
data class Account(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    val name: String = "",
    val handle: String,
    val avatarUrl: String? = null,
    /** Whether the site's account is bridged to Bluesky; the handle may not be known yet, see [blueskyHandleConfirmed]. */
    val blueskyEnabled: Boolean = false,
    val blueskyHandleConfirmed: Boolean = false,
    /** The bridged account's Bluesky handle, with its leading `@`; null until confirmed. */
    val blueskyHandle: String? = null,
)

/** The site's own account settings, `v1/preferences`. */
@Serializable
data class Preferences(val showSensitiveMedia: Boolean = false)

/** Where Bluesky sharing stands, as `v2/actions/bluesky/enable` and `confirm-handle` answer it. */
@Serializable
data class BlueskyStatus(
    val enabled: Boolean = false,
    val handleConfirmed: Boolean = false,
    val handle: String? = null,
)

/** Full profile from `account/:handle` (the service's AccountDTO). */
@Serializable
data class Profile(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    val apId: String = "",
    val name: String = "",
    val handle: String,
    val avatarUrl: String? = null,
    /** HTML. */
    val bio: String = "",
    val url: String? = null,
    val bannerImageUrl: String? = null,
    /** Label to HTML value, e.g. "Website" to a link. */
    val customFields: Map<String, String?> = emptyMap(),
    val postCount: Int = 0,
    val likedCount: Int = 0,
    val followingCount: Int = 0,
    val followerCount: Int = 0,
    val followsMe: Boolean = false,
    val followedByMe: Boolean = false,
    val blockedByMe: Boolean = false,
    /** Whether the account's server is blocked, which hides it as much as blocking it. */
    val domainBlockedByMe: Boolean = false,
)

/** `replies/:id`: the post, the posts above it, and its direct replies with their own follow-ups. */
@Serializable
data class ReplyChain(
    val ancestors: Ancestors = Ancestors(),
    val post: Post,
    val children: List<Child> = emptyList(),
    val next: String? = null,
) {
    @Serializable
    data class Ancestors(val chain: List<Post> = emptyList(), val hasMore: Boolean = false)

    @Serializable
    data class Child(val post: Post, val chain: List<Post> = emptyList(), val hasMore: Boolean = false)
}

/** `notifications`: newest first, paged with the opaque [next] cursor. */
@Serializable
data class NotificationsPage(
    val notifications: List<Notification> = emptyList(),
    val next: String? = null,
)

/** The service's NotificationDTO. */
@Serializable
data class Notification(
    @Serializable(with = FlexibleIdSerializer::class) val id: String,
    val createdAt: String? = null,
    val type: Type = Type.Unknown,
    val actor: Author,
    /** What was liked or reposted, or the reply or mention itself. Null for follows. */
    val post: NotificationPost? = null,
    /** For replies: the post that was replied to, usually one of ours. */
    val inReplyTo: NotificationPost? = null,
) {
    @Serializable
    enum class Type {
        @SerialName("like") Like,
        @SerialName("repost") Repost,
        @SerialName("reply") Reply,
        @SerialName("follow") Follow,
        @SerialName("mention") Mention,
        /** A kind this version doesn't know; `coerceInputValues` maps it here. */
        Unknown,
    }
}

/** The part of a post a notification carries. [type] is `note` or `article`. */
@Serializable
data class NotificationPost(
    val id: String,
    val type: String = "note",
    val title: String = "",
    /** HTML. */
    val content: String = "",
    @Serializable(with = LinkUrlOrEmptySerializer::class) val url: String = "",
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val repostCount: Int = 0,
    val likedByMe: Boolean = false,
    val repostedByMe: Boolean = false,
    @Serializable(with = AttachmentListSerializer::class) val attachments: List<Attachment> = emptyList(),
    val sensitive: Boolean = false,
    val contentWarning: String? = null,
) {
    val isArticle: Boolean get() = type == "article"
}

/** An image already stored with [ActivityPubClient.uploadImage], to attach to a new note. */
@Serializable
data class NoteImage(val url: String, val altText: String? = null)

/**
 * The few fields of a raw ActivityStreams object, fetched from its own server, that the service's
 * DTOs leave out. [url] is the human-readable page; [name] is the title of videos, articles and
 * the like, absent on notes, and the display name of accounts. [emojis] are the custom emoji its
 * text and name use.
 */
@Serializable
data class RemoteObject(
    val name: String? = null,
    @Serializable(with = LinkUrlSerializer::class) val url: String? = null,
    @Serializable(with = ObjectIdSerializer::class) val inReplyTo: String? = null,
    @SerialName("tag") @Serializable(with = EmojiTagsSerializer::class) val emojis: List<CustomEmoji> = emptyList(),
)

/** A server's own emoji, written `:[shortcode]:` in text and shown as the image at [url]. */
@Serializable
data class CustomEmoji(val shortcode: String, val url: String)

/** The `Emoji` entries of an object's `tag`, which may be one tag or a list; other tags are skipped. */
internal object EmojiTagsSerializer : KSerializer<List<CustomEmoji>> {
    private val delegate = ListSerializer(CustomEmoji.serializer())
    override val descriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): List<CustomEmoji> {
        if (decoder !is JsonDecoder) return delegate.deserialize(decoder)
        val tags = when (val element = decoder.decodeJsonElement()) {
            is JsonArray -> element
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        return tags.mapNotNull { tag ->
            if (tag !is JsonObject || (tag["type"] as? JsonPrimitive)?.contentOrNull != "Emoji") return@mapNotNull null
            val shortcode = (tag["name"] as? JsonPrimitive)?.contentOrNull?.trim(':')?.takeIf { it.isNotBlank() }
            val icon = tag["icon"].let { if (it is JsonArray) it.firstOrNull() else it } as? JsonObject
            val url = objectId(icon?.get("url"))?.takeIf { it.startsWith("https://") }
            if (shortcode == null || url == null) null else CustomEmoji(shortcode, url)
        }
    }
    override fun serialize(encoder: Encoder, value: List<CustomEmoji>) = delegate.serialize(encoder, value)
}

/** A reference to another object: its id as a string, or an object or list carrying one. */
internal object ObjectIdSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("ObjectId", PrimitiveKind.STRING).nullable
    override fun deserialize(decoder: Decoder): String? =
        if (decoder is JsonDecoder) objectId(decoder.decodeJsonElement()) else decoder.decodeString()
    override fun serialize(encoder: Encoder, value: String?) =
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
}

internal fun objectId(element: JsonElement?): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull?.takeIf { element.isString && it.isNotBlank() }
    is JsonObject -> objectId(element["id"] ?: element["href"])
    is JsonArray -> element.firstNotNullOfOrNull { objectId(it) }
    else -> null
}
