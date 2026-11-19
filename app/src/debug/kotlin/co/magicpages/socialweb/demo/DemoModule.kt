package co.magicpages.socialweb.demo

import androidx.datastore.core.DataStore
import co.magicpages.socialweb.data.account.AccountStorageDecorator
import co.magicpages.socialweb.data.account.AccountsFile
import co.magicpages.socialweb.data.account.Credential
import co.magicpages.socialweb.data.account.GhostSite
import co.magicpages.socialweb.data.account.Profile
import co.magicpages.socialweb.data.account.StoredAccount
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor

/** Plugs demo mode into the hooks of [co.magicpages.socialweb.di.DebugHooksModule]; release builds never see it. */
@Module
@InstallIn(SingletonComponent::class)
object DemoModule {
    /** While [DemoMode] is on the request never leaves the app; otherwise it goes out untouched. */
    @Provides
    @IntoSet
    fun demoInterceptor(api: DemoApi): Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (DemoMode.enabled.value) api.respond(request) else chain.proceed(request)
    }

    @Provides
    @IntoSet
    fun demoAccountStorage(): AccountStorageDecorator = AccountStorageDecorator { real -> DemoAccountStorage(real) }
}

/**
 * The accounts' storage with a second, in-memory file for demo mode. Reads and writes go to the demo
 * file while [DemoMode] is on and to the [real] one otherwise, so demo mode can't touch a real
 * account, and switching it on or off swaps the signed-in account at once.
 */
internal class DemoAccountStorage(private val real: DataStore<AccountsFile>) : DataStore<AccountsFile> {
    private val demo = MutableStateFlow(AccountsFile(accounts = listOf(DemoAccount), activeId = DemoAccount.id))
    private val lock = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val data: Flow<AccountsFile> = DemoMode.enabled.flatMapLatest { if (it) demo else real.data }

    override suspend fun updateData(transform: suspend (t: AccountsFile) -> AccountsFile): AccountsFile =
        if (DemoMode.enabled.value) {
            lock.withLock { transform(demo.value).also { demo.value = it } }
        } else {
            real.updateData(transform)
        }

    private companion object {
        /** The signed-in site. Its session is never checked: the demo Ghost Admin API accepts anything. */
        val DemoAccount = DemoContent.me.let { me ->
            StoredAccount(
                site = GhostSite(
                    title = me.name,
                    siteUrl = "https://${me.domain}/",
                    adminApiUrl = "https://${me.domain}/ghost/api/admin/",
                    version = "6.10.0",
                    iconUrl = me.avatar,
                    accentColor = DemoContent.AccentColor,
                ),
                credential = Credential.Session(mapOf("ghost-admin-api-session" to "demo")),
                profile = Profile(name = me.name, handle = me.handle, avatarUrl = me.avatar),
            )
        }
    }
}
