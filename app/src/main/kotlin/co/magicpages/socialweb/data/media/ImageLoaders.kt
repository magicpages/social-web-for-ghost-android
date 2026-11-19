@file:OptIn(ExperimentalCoilApi::class)

package co.magicpages.socialweb.data.media

import android.content.Context
import co.magicpages.socialweb.data.activitypub.AvatarRefresher
import co.magicpages.socialweb.data.activitypub.FreshAvatars
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.Options
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okio.Path.Companion.toOkioPath

/**
 * The app's two image loaders. They share one memory cache and one HTTP client but keep separate
 * disk caches, which is how the two retention rules are told apart: Coil's disk cache is fixed
 * per loader, and its size limit evicts the least recently used file whatever it holds, so
 * mixing both kinds in one cache would let a week of feed images push profile photos out.
 *
 * - [avatars]: profile photos. Entries never expire and the cache is capped at [AVATAR_CACHE_BYTES].
 *   [co.magicpages.socialweb.ui.components.Avatar] uses it, and it replaces timeline avatars the
 *   service holds stale (see [FreshAvatars]).
 * - [general]: everything else (post images, banners, emoji). The app-wide default loader.
 *   Coil's default [CacheStrategy] obeys the origin's `Cache-Control`, which for static media is
 *   anything from "no-store" to a year, so [ExpiringCacheStrategy] replaces it: whatever the origin
 *   says, a cached image is served for [GENERAL_TTL_MILLIS] after it was fetched and then
 *   downloaded again. Entries nobody asks for again are left to the [GENERAL_CACHE_BYTES] cap.
 */
@Singleton
class ImageLoaders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: HttpClient,
    private val freshAvatars: FreshAvatars,
    private val avatarRefresher: AvatarRefresher,
) {
    private val memoryCache = MemoryCache.Builder().maxSizePercent(context).build()

    val general: ImageLoader by lazy {
        create("image_cache", GENERAL_CACHE_BYTES, ExpiringCacheStrategy(GENERAL_TTL_MILLIS)) {}
    }

    val avatars: ImageLoader by lazy {
        create("avatar_cache", AVATAR_CACHE_BYTES, ForeverCacheStrategy) {
            add(FreshAvatarInterceptor(freshAvatars, avatarRefresher))
        }
    }

    private fun create(
        directory: String,
        maxBytes: Long,
        strategy: CacheStrategy,
        extraComponents: coil3.ComponentRegistry.Builder.() -> Unit,
    ): ImageLoader = ImageLoader.Builder(context)
        .memoryCache(memoryCache)
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve(directory).toOkioPath())
                .maxSizeBytes(maxBytes)
                .build()
        }
        .components {
            add(KtorNetworkFetcherFactory({ http }, { strategy }))
            extraComponents()
        }
        .build()

    companion object {
        const val AVATAR_CACHE_BYTES = 200L * 1024 * 1024
        const val GENERAL_CACHE_BYTES = 250L * 1024 * 1024
        const val GENERAL_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

/** Lets composables, which can't be injected, reach [ImageLoaders]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImageLoadersEntryPoint {
    fun imageLoaders(): ImageLoaders
}

/** Only successful responses are worth keeping; nothing else about the origin's headers matters. */
private fun NetworkResponse.isStorable() = code in 200..299

/**
 * Serves a cached image until it is [ttlMillis] old, then fetches it afresh (without asking the
 * origin whether it changed) and stores the new copy. [now] is for tests.
 */
internal class ExpiringCacheStrategy(
    private val ttlMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) : CacheStrategy {
    override suspend fun read(cacheResponse: NetworkResponse, networkRequest: NetworkRequest, options: Options) =
        if (now() - cacheResponse.responseMillis >= ttlMillis) {
            CacheStrategy.ReadResult(networkRequest)
        } else {
            CacheStrategy.ReadResult(cacheResponse)
        }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ) = if (networkResponse.isStorable()) CacheStrategy.WriteResult(networkResponse) else CacheStrategy.WriteResult.DISABLED
}

/** Serves a cached image for as long as it stays on disk; only the size cap removes it. */
internal object ForeverCacheStrategy : CacheStrategy {
    override suspend fun read(cacheResponse: NetworkResponse, networkRequest: NetworkRequest, options: Options) =
        CacheStrategy.ReadResult(cacheResponse)

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ) = if (networkResponse.isStorable()) CacheStrategy.WriteResult(networkResponse) else CacheStrategy.WriteResult.DISABLED
}

/**
 * Shows the current avatar of an account whose timeline avatar is out of date: straight away when
 * it is known, otherwise after the stale address fails and the account's profile has been looked up.
 */
private class FreshAvatarInterceptor(
    private val avatars: FreshAvatars,
    private val refresher: AvatarRefresher,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        avatars.replacementFor(url)?.let { return chain.withRequest(request.withData(it)).proceed() }

        val result = chain.proceed()
        // A stale avatar is gone (404) or unreadable; being offline says nothing about it.
        if (result !is ErrorResult || result.throwable is IOException) return result
        val replacement = refresher.replacementFor(url) ?: return result
        return chain.withRequest(request.withData(replacement)).proceed()
    }

    private fun ImageRequest.withData(data: Any) = ImageRequest.Builder(this).data(data).build()
}
