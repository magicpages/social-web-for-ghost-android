package co.magicpages.socialweb.di

import co.magicpages.socialweb.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Interceptor

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun httpClient(interceptors: Set<@JvmSuppressWildcards Interceptor>): HttpClient = HttpClient(OkHttp) {
        engine { for (interceptor in interceptors) addInterceptor(interceptor) }
        expectSuccess = false
        install(UserAgent) { agent = "SocialWebForGhost-Android/${BuildConfig.VERSION_NAME}" }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
    }
}
