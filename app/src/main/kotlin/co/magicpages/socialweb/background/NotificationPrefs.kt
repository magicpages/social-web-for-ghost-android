package co.magicpages.socialweb.background

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.Notification
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Small bits of notification state; nothing here is secret, so plain preferences do. */
@Singleton
class NotificationPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("notification_state", Context.MODE_PRIVATE)
    private val markers = LastSeenMarkers(SharedPreferencesValues(prefs))

    /**
     * Decides the background check of [account] over its newest-first [page] against the newest
     * notification already announced, or seen in the app, for that account, and advances that
     * marker. See [checkFor].
     */
    internal fun check(account: StoredAccount, page: List<Notification>, announced: Set<Notification.Type>): CheckOutcome =
        page.checkFor(account, markers, announced)

    /** Forgets what was seen for [account], e.g. when it is signed out. */
    fun clearLastSeenId(account: StoredAccount) = markers.forget(account)

    /** Whether the system permission dialog has been shown already; it is only ever shown once on its own. */
    var permissionRequested: Boolean
        get() = prefs.getBoolean(KeyPermissionRequested, false)
        set(value) = prefs.edit { putBoolean(KeyPermissionRequested, value) }

    private companion object {
        const val KeyPermissionRequested = "permission_requested"
    }
}

/** String storage under [LastSeenMarkers]: preferences in the app, a map in tests. */
internal interface KeyValues {
    operator fun get(key: String): String?

    /** Applies all [changes] together; a null value removes the key. */
    fun set(vararg changes: Pair<String, String?>)
}

private class SharedPreferencesValues(private val prefs: SharedPreferences) : KeyValues {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(vararg changes: Pair<String, String?>) {
        prefs.edit {
            for ((key, value) in changes) if (value == null) remove(key) else putString(key, value)
        }
    }
}

/**
 * The newest notification id of each account that was announced or seen in the app, so a check
 * announces only what came after. Kept per account: [StoredAccount.id].
 *
 * Before several accounts could be signed in there was one marker, stored with the handle of the
 * account it belonged to. It is moved to that account the first time it is asked for.
 */
internal class LastSeenMarkers(private val values: KeyValues) {
    fun get(account: StoredAccount): String? {
        values[key(account)]?.let { return it }
        val legacy = legacyMarker(account) ?: return null
        values.set(key(account) to legacy, LegacyHandle to null, LegacyId to null)
        return legacy
    }

    fun set(account: StoredAccount, id: String) {
        if (legacyMarker(account) != null) {
            values.set(key(account) to id, LegacyHandle to null, LegacyId to null)
        } else {
            values.set(key(account) to id)
        }
    }

    fun forget(account: StoredAccount) {
        if (legacyMarker(account) != null) {
            values.set(key(account) to null, LegacyHandle to null, LegacyId to null)
        } else {
            values.set(key(account) to null)
        }
    }

    /** The single marker of earlier versions, when it belongs to [account]. */
    private fun legacyMarker(account: StoredAccount): String? =
        values[LegacyId]?.takeIf { values[LegacyHandle] == account.profile.handle }

    private fun key(account: StoredAccount) = KeyPrefix + account.id

    private companion object {
        const val KeyPrefix = "last_seen:"
        const val LegacyHandle = "last_seen_handle"
        const val LegacyId = "last_seen_id"
    }
}
