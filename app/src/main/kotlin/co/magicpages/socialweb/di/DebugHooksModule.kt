package co.magicpages.socialweb.di

import co.magicpages.socialweb.data.account.AccountStorageDecorator
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import okhttp3.Interceptor

/**
 * Extension points the debug build fills in (see the demo mode in `src/debug`). Declared here, empty,
 * so the release build, which adds nothing, still has the sets to inject.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DebugHooksModule {
    /** Application interceptors of the HTTP client's OkHttp engine, in the order they are added. */
    @Multibinds
    abstract fun httpInterceptors(): Set<Interceptor>

    @Multibinds
    abstract fun accountStorageDecorators(): Set<AccountStorageDecorator>
}
