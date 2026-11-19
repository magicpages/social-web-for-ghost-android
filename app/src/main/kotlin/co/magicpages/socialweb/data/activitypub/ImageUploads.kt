package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.account.Credential
import co.magicpages.socialweb.data.account.GhostSite
import co.magicpages.socialweb.data.media.LocalImages
import javax.inject.Inject
import javax.inject.Singleton

/** A picture the writer picked for a reply: a `content://` Uri, read only when it is sent. */
data class PickedImage(val uri: String, val altText: String = "")

/** Why a picture can't go with a note or reply. */
enum class ImageRejection { TooLarge, UnsupportedType, Unreadable }

/** The picture was turned down, by the app or by the site, for [reason]. */
class ImageRejectedException(val reason: ImageRejection) : Exception("Image rejected: $reason")

/** Puts pictures for notes and replies on the account's site. */
@Singleton
class ImageUploads @Inject constructor(private val client: ActivityPubClient, private val images: LocalImages) {
    /** Uploads [image] and returns it as a post's attachment. Throws [ImageRejectedException]. */
    suspend fun upload(site: GhostSite, credential: Credential, image: PickedImage): NoteImage {
        val file = when (val loaded = images.load(image.uri, ActivityPubClient.MAX_IMAGE_BYTES)) {
            is LocalImages.Result.Loaded -> loaded.image
            LocalImages.Result.TooLarge -> throw ImageRejectedException(ImageRejection.TooLarge)
            LocalImages.Result.UnsupportedType -> throw ImageRejectedException(ImageRejection.UnsupportedType)
            LocalImages.Result.Unreadable -> throw ImageRejectedException(ImageRejection.Unreadable)
        }
        val url = upload(site, credential, file.bytes, file.fileName, file.mimeType)
        return NoteImage(url, image.altText.trim().ifBlank { null })
    }

    /** Uploads [bytes] and returns the URL. Throws [ImageRejectedException] when the site refuses their size or type. */
    suspend fun upload(site: GhostSite, credential: Credential, bytes: ByteArray, fileName: String, mimeType: String): String =
        try {
            client.uploadImage(site, credential, bytes, fileName, mimeType)
        } catch (e: SocialWebException) {
            throw when (e.status) {
                413 -> ImageRejectedException(ImageRejection.TooLarge)
                415 -> ImageRejectedException(ImageRejection.UnsupportedType)
                else -> e
            }
        }
}
