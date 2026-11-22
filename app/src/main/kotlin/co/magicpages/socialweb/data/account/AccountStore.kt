package co.magicpages.socialweb.data.account

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Lets a debug build wrap the accounts' storage (the demo mode swaps it for an in-memory one). Release
 * builds bind none, so [AccountStore] uses the encrypted file as is.
 */
fun interface AccountStorageDecorator {
    fun decorate(storage: DataStore<AccountsFile>): DataStore<AccountsFile>
}

/**
 * Keeps the signed-in accounts (each with its Ghost session cookie or staff token) and which one
 * is active on disk, encrypted with AES-GCM under a key that never leaves the Android Keystore.
 */
@Singleton
class AccountStore @Inject constructor(
    @ApplicationContext context: Context,
    json: Json,
    decorators: Set<@JvmSuppressWildcards AccountStorageDecorator>,
) {
    private val dataStore: DataStore<AccountsFile> = decorators.fold(
        DataStoreFactory.create(
            // Keystore work happens lazily on DataStore's IO thread, not during injection.
            serializer = EncryptedJsonSerializer(lazy { aead(context) }, json),
            // Undecryptable data (e.g. after the Keystore key was wiped) just means "signed out".
            corruptionHandler = ReplaceFileCorruptionHandler { AccountsFile() },
            produceFile = { File(context.filesDir, "datastore/accounts.bin") },
        ),
    ) { storage, decorator -> decorator.decorate(storage) }

    /** The active account: what the screens show and every request is made as. Null when signed out. */
    val account: Flow<StoredAccount?> = dataStore.data.map { it.active }

    /** The id of the active account. Emits when the account changes (sign-in, switch, sign-out), not when its credential is replaced. */
    val activeId: Flow<String?> = dataStore.data.map { it.active?.id }.distinctUntilChanged()

    /** Every signed-in account, in the order they were added. */
    val accounts: Flow<List<StoredAccount>> = dataStore.data.map { it.accounts }

    /** The signed-in account with [id]; null once it has been signed out. */
    suspend fun signedIn(id: String): StoredAccount? = accounts.first().firstOrNull { it.id == id }

    /** Stores [account] and makes it the active one; an account with the same site and user is replaced. */
    suspend fun add(account: StoredAccount) {
        dataStore.updateData { it.withAdded(account) }
    }

    /** Makes the account with [id] the active one. False when there is no such account. */
    suspend fun switchTo(id: String): Boolean = dataStore.updateData { it.withActive(id) }.activeId == id

    /** Forgets the account with [id]. If it was the active one, another stored account becomes active. */
    suspend fun remove(id: String) {
        dataStore.updateData { it.withRemoved(id) }
    }

    /** Records the accent colour Ghost now reports for the site at [siteUrl]. */
    suspend fun updateAccentColor(siteUrl: String, accentColor: String?) {
        dataStore.updateData { it.withAccentColor(siteUrl, accentColor) }
    }

    /** Records the profile the site now has for the account [id]; the id stays the same even when the handle changed. */
    suspend fun updateProfile(id: String, profile: Profile) {
        dataStore.updateData { it.withProfile(id, profile) }
    }

    private class EncryptedJsonSerializer(aead: Lazy<Aead>, private val json: Json) : Serializer<AccountsFile> {
        private val aead by aead

        override val defaultValue = AccountsFile()

        override suspend fun readFrom(input: InputStream): AccountsFile {
            val bytes = input.readBytes()
            if (bytes.isEmpty()) return defaultValue
            return try {
                json.decodeFromString<AccountsFile>(aead.decrypt(bytes, ASSOCIATED_DATA).decodeToString()).migrated()
            } catch (e: GeneralSecurityException) {
                throw CorruptionException("Cannot decrypt accounts", e)
            } catch (e: SerializationException) {
                throw CorruptionException("Cannot parse accounts", e)
            }
        }

        override suspend fun writeTo(t: AccountsFile, output: OutputStream) {
            output.write(aead.encrypt(json.encodeToString(t).encodeToByteArray(), ASSOCIATED_DATA))
        }
    }

    private companion object {
        val ASSOCIATED_DATA = "accounts.bin".encodeToByteArray()

        fun aead(context: Context): Aead {
            AeadConfig.register()
            return AndroidKeysetManager.Builder()
                .withSharedPref(context, "accounts_keyset", "co.magicpages.socialweb.keys")
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri("android-keystore://co.magicpages.socialweb.accounts")
                .build()
                .keysetHandle
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
    }
}
