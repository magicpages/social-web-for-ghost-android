package co.magicpages.socialweb.data.translation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One file of a language model, as Mozilla's catalog lists it: [type] is "model", "lex", "vocab", "srcvocab" or "trgvocab". */
internal data class ModelFile(val type: String, val name: String, val location: String, val sha256: String, val size: Long)

/** What translating [from] one language [to] another takes: Firefox's model for the pair, in its newest [version]. */
internal data class LanguageModel(val from: String, val to: String, val version: String, val files: List<ModelFile>)

/** A language model on the device, its files by type. */
data class InstalledModel(val from: String, val to: String, val intgemm8: Boolean, val files: Map<String, File>)

/**
 * The files on-device translation runs on: Bergamot, Firefox's translation engine, and a language
 * model per pair of languages (from or to English), as Firefox downloads them from Mozilla. Nothing
 * comes with the app, nothing is downloaded without the user asking, and every file is checked
 * against its SHA-256 before it is used.
 */
@Singleton
class TranslationFiles @Inject constructor(
    @ApplicationContext context: Context,
    private val http: HttpClient,
    private val json: Json,
) {
    // Big and downloaded again on demand: kept out of backups.
    private val root = File(context.noBackupFilesDir, "translator")
    private val mutex = Mutex()
    private var catalog: Map<Pair<String, String>, LanguageModel>? = null

    /** The engine; the JavaScript that loads it comes with the app (assets/translator, from Firefox) and is built for this version. */
    val engine: File get() = File(root, "engine-$EngineVersion.wasm")

    private val engineInstalled: Boolean get() = engine.length() == EngineSize

    /** The model for [from] → [to] when it's on the device. */
    suspend fun installed(from: String, to: String): InstalledModel? = withContext(Dispatchers.IO) {
        val dir = modelDir(from, to)
        val manifest = try {
            json.decodeFromString<Manifest>(File(dir, ManifestName).readText())
        } catch (_: Exception) {
            return@withContext null
        }
        val files = manifest.types.associateWith { File(dir, it) }
        if (!engineInstalled || files.values.any { !it.isFile }) return@withContext null
        InstalledModel(from, to, manifest.intgemm8, files)
    }

    /**
     * The model for [from] → [to] from Mozilla's catalog; null when there is none for the pair.
     *
     * @throws IOException when the catalog can't be loaded
     */
    internal suspend fun available(from: String, to: String): LanguageModel? = loadCatalog()[from to to]

    /** How many bytes downloading [models] takes: their files not yet on the device, and the engine if it isn't. */
    internal suspend fun downloadSize(models: List<LanguageModel>): Long = withContext(Dispatchers.IO) {
        val missing = models.filter { installed(it.from, it.to) == null }.sumOf { model -> model.files.sumOf { it.size } }
        missing + if (engineInstalled) 0 else EngineSize
    }

    /**
     * Downloads the engine and [models], once: a model that is already there isn't downloaded again.
     *
     * @throws IOException when a file can't be downloaded or doesn't match its checksum
     */
    internal suspend fun download(models: List<LanguageModel>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            root.mkdirs()
            if (!engineInstalled) fetch(AttachmentsUrl + EngineLocation, EngineSha256, EngineSize, engine)
            for (model in models) {
                if (installed(model.from, model.to) != null) continue
                val dir = modelDir(model.from, model.to)
                dir.deleteRecursively()
                dir.mkdirs()
                for (file in model.files) fetch(AttachmentsUrl + file.location, file.sha256, file.size, File(dir, file.type))
                val manifest = Manifest(model.version, model.files.single { it.type == "model" }.name.endsWith("intgemm8.bin"), model.files.map { it.type })
                // Written last: a model is only there once all of its files are.
                File(dir, ManifestName).writeText(json.encodeToString(manifest))
            }
        }
    }

    /** The pairs of languages ([from] to [to]) whose models are on the device. */
    suspend fun installedPairs(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        File(root, "models").listFiles().orEmpty().mapNotNull { dir ->
            val from = dir.name.substringBefore('-')
            val to = dir.name.removePrefix("$from-")
            // "zh-Hans-en": the script belongs to the first language.
            val pair = if (to.startsWith("Hans-") || to.startsWith("Hant-")) {
                "$from-${to.substringBefore('-')}" to to.substringAfter('-')
            } else {
                from to to
            }
            pair.takeIf { installed(it.first, it.second) != null }
        }
    }

    /**
     * Every pair of languages Mozilla has a model for.
     *
     * @throws IOException when the catalog can't be loaded
     */
    internal suspend fun availablePairs(): Set<Pair<String, String>> = loadCatalog().keys

    /** Deletes the models for [pairs]; the engine goes too once no model is left. */
    suspend fun delete(pairs: Collection<Pair<String, String>>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            for ((from, to) in pairs) modelDir(from, to).deleteRecursively()
            if (File(root, "models").listFiles().isNullOrEmpty()) engine.delete()
        }
    }

    /** Deletes everything translation downloaded. */
    suspend fun deleteAll() = mutex.withLock {
        withContext(Dispatchers.IO) { root.deleteRecursively() }
    }

    /** How many bytes the downloads take on the device. */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) {
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** How many bytes the model for [from] → [to] takes on the device. */
    suspend fun modelBytes(from: String, to: String): Long = withContext(Dispatchers.IO) {
        modelDir(from, to).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private suspend fun loadCatalog(): Map<Pair<String, String>, LanguageModel> = mutex.withLock {
        catalog ?: run {
            val response = http.get(CatalogUrl)
            if (!response.status.isSuccess()) throw IOException("Catalog: HTTP ${response.status.value}")
            val records = withContext(Dispatchers.Default) {
                languageModels(Json.parseToJsonElement(response.bodyAsText()))
            }
            records.also { catalog = it }
        }
    }

    /** Downloads [url] into [target] through a temporary file, checking [size] and [sha256] first. */
    private suspend fun fetch(url: String, sha256: String, size: Long, target: File) {
        val partial = File(target.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        http.prepareGet(url) {
            // A model is tens of megabytes: the client's 30 seconds for a whole request are too few.
            timeout { requestTimeoutMillis = DownloadTimeoutMillis }
        }.execute { response ->
            if (!response.status.isSuccess()) throw IOException("$url: HTTP ${response.status.value}")
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            partial.outputStream().use { out ->
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (partial.length() != size || !hash.equals(sha256, ignoreCase = true)) {
            partial.delete()
            throw IOException("$url doesn't match its checksum")
        }
        if (!partial.renameTo(target)) throw IOException("Couldn't move $partial into place")
    }

    private fun modelDir(from: String, to: String) = File(root, "models/$from-$to")

    @Serializable
    private class Manifest(val version: String, val intgemm8: Boolean, val types: List<String>)

    private companion object {
        private const val ManifestName = "manifest.json"
        private const val DownloadTimeoutMillis = 10 * 60_000L

        private const val CatalogUrl =
            "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records"
        private const val AttachmentsUrl = "https://firefox-settings-attachments.cdn.mozilla.net/"

        // Bergamot 3.0 (release v0.6.0) from the translations-wasm collection; pinned, as the loader in
        // the assets is built for it.
        private const val EngineVersion = "3.0"
        private const val EngineLocation = "main-workspace/translations-wasm/05082c31-aee8-4249-9e01-c1865afd7520.wasm"
        private const val EngineSha256 = "a3a89d9ad0a4ed8f27bf3e403701b23f5709816f6376438503f2fa5b0182c2dc"
        private const val EngineSize = 4_960_506L
    }
}

/**
 * The newest usable model for every pair of languages in Mozilla's catalog (`{"data": [records]}`).
 * Like Firefox for Android's release channel, records for other platforms and channels are left out,
 * and so are prereleases ("2.0a1") and models newer than the engine runs (3.x and up need Bergamot 4).
 */
internal fun languageModels(catalog: JsonElement): Map<Pair<String, String>, LanguageModel> {
    val records = ((catalog as? JsonObject)?.get("data") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val files = records.mapNotNull { record ->
        val filter = record.string("filter_expression").orEmpty().trim()
        if (filter.isNotEmpty() && filter != AndroidOnly) return@mapNotNull null
        val version = record.string("version")?.takeIf { releaseVersion.matches(it) } ?: return@mapNotNull null
        if (version.substringBefore('.').toInt() !in SupportedMajorVersions) return@mapNotNull null
        val from = record.string("fromLang") ?: return@mapNotNull null
        val to = record.string("toLang") ?: return@mapNotNull null
        val attachment = record["attachment"] as? JsonObject ?: return@mapNotNull null
        val file = ModelFile(
            type = record.string("fileType") ?: return@mapNotNull null,
            name = record.string("name") ?: return@mapNotNull null,
            location = attachment.string("location") ?: return@mapNotNull null,
            sha256 = attachment.string("hash") ?: return@mapNotNull null,
            size = (attachment["size"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
        )
        Triple(from to to, version, file)
    }
    return files.groupBy({ it.first }) { it.second to it.third }.mapNotNull { (pair, versions) ->
        versions.groupBy({ it.first }) { it.second }
            .filterValues { it.isComplete() }
            .maxByOrNull { (version, _) -> version.split('.').let { it[0].toInt() * 10_000 + it[1].toInt() } }
            ?.let { (version, list) -> pair to LanguageModel(pair.first, pair.second, version, list.distinctBy { it.type }) }
    }.toMap()
}

/** A model, and either one vocabulary or one for each language; the shortlist is optional. */
private fun List<ModelFile>.isComplete(): Boolean {
    val types = map { it.type }.toSet()
    return "model" in types && ("vocab" in types || ("srcvocab" in types && "trgvocab" in types))
}

private const val AndroidOnly = "env.appinfo.OS == 'Android'"
private val releaseVersion = Regex("""\d+\.\d+""")

/** The model versions Bergamot 3 runs. */
private val SupportedMajorVersions = 1..2

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
