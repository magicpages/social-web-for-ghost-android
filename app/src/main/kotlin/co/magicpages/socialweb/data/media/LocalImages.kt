package co.magicpages.socialweb.data.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import co.magicpages.socialweb.data.activitypub.ImageRejection
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An image read from a `content://` Uri, ready to upload. */
class LocalImage(val bytes: ByteArray, val mimeType: String, val fileName: String)

/** Reads images the user picked, off the main thread and without ever holding more than a limit. */
@Singleton
class LocalImages @Inject constructor(@ApplicationContext private val context: Context) {
    sealed interface Result {
        class Loaded(val image: LocalImage) : Result
        data object TooLarge : Result
        data object UnsupportedType : Result
        data object Unreadable : Result
    }

    /** A shared image copied into the cache, or why it was turned away. */
    sealed interface Staged {
        /** [uri] is a `file:` Uri that [load] reads. */
        class Ready(val uri: String) : Staged
        class Rejected(val reason: ImageRejection) : Staged
    }

    /**
     * Copies the image behind a shared `content://` [uri] into the app's cache. The sender's grant lasts
     * only as long as the receiving activity, and a note can outlive it.
     */
    suspend fun stage(uri: String, maxBytes: Long): Staged {
        val image = when (val result = load(uri, maxBytes)) {
            is Result.Loaded -> result.image
            Result.TooLarge -> return Staged.Rejected(ImageRejection.TooLarge)
            Result.UnsupportedType -> return Staged.Rejected(ImageRejection.UnsupportedType)
            Result.Unreadable -> return Staged.Rejected(ImageRejection.Unreadable)
        }
        return withContext(Dispatchers.IO) {
            try {
                val dir = stagingDir().apply { mkdirs() }
                val cutoff = System.currentTimeMillis() - STAGING_MAX_AGE_MS
                dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
                val file = File(dir, "${UUID.randomUUID()}.${FILE_EXTENSIONS.getValue(image.mimeType)}")
                file.writeBytes(image.bytes)
                Staged.Ready(Uri.fromFile(file).toString())
            } catch (_: IOException) {
                Staged.Rejected(ImageRejection.Unreadable)
            }
        }
    }

    private fun stagingDir() = File(context.cacheDir, STAGING_DIR)

    /** The type of a `file:` Uri that [stage] made; the cache holds nothing else the app may read this way. */
    private fun stagedType(uri: Uri): String? {
        val file = File(uri.path ?: return null).canonicalFile
        if (file.parentFile != stagingDir().canonicalFile) return null
        return TYPES_BY_EXTENSION[file.extension.lowercase()]
    }

    /** Loads [uri], rejecting types the service won't take and files over [maxBytes] before reading them whole. */
    suspend fun load(uri: String, maxBytes: Long): Result = withContext(Dispatchers.IO) {
        try {
            val parsed = uri.toUri()
            val resolver = context.contentResolver
            val mimeType = (if (parsed.scheme == ContentResolver.SCHEME_FILE) stagedType(parsed) else resolver.getType(parsed))?.lowercase()
            if (mimeType == null || mimeType !in SUPPORTED_TYPES) return@withContext Result.UnsupportedType

            var name: String? = null
            var size: Long? = null
            resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getStringOrNull(OpenableColumns.DISPLAY_NAME)
                    size = cursor.getLongOrNull(OpenableColumns.SIZE)
                }
            }
            if ((size ?: 0L) > maxBytes) return@withContext Result.TooLarge

            // The reported size can be missing or wrong, so the read enforces the limit itself.
            val bytes = resolver.openInputStream(parsed)?.use { input ->
                val out = ByteArrayOutputStream(size?.toInt()?.coerceAtLeast(DEFAULT_BUFFER) ?: DEFAULT_BUFFER)
                val buffer = ByteArray(DEFAULT_BUFFER)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) return@withContext Result.TooLarge
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            } ?: return@withContext Result.Unreadable

            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "img"
            Result.Loaded(LocalImage(bytes, mimeType, name?.takeIf { it.isNotBlank() } ?: "image.$extension"))
        } catch (_: IOException) {
            Result.Unreadable
        } catch (_: SecurityException) {
            Result.Unreadable
        }
    }

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun android.database.Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private companion object {
        const val DEFAULT_BUFFER = 8 * 1024
        const val STAGING_DIR = "shared-images"
        const val STAGING_MAX_AGE_MS = 24L * 60 * 60 * 1000

        /** What the service accepts, with the extension staged copies are saved under. */
        val FILE_EXTENSIONS = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/heic" to "heic",
            "image/heif" to "heif",
            "image/gif" to "gif",
        )
        val SUPPORTED_TYPES = FILE_EXTENSIONS.keys
        val TYPES_BY_EXTENSION = FILE_EXTENSIONS.entries.associate { it.value to it.key }
    }
}
