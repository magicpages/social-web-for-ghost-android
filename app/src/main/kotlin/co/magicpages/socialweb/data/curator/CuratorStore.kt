package co.magicpages.socialweb.data.curator

import android.content.Context
import androidx.core.content.edit
import co.magicpages.socialweb.data.activitypub.Post
import co.magicpages.socialweb.data.activitypub.Topic
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One day's posts for the curator desk: the ones picked for [date] (the device's local date, ISO), and how far
 * the writer got through them.
 */
@Serializable
data class CuratorDay(
    val date: String,
    /** The day's posts, without their bodies; null until they have been picked, empty when there was nothing to pick. */
    val picks: List<Post>? = null,
    /** How many of [picks] were answered or skipped; the next one is the one on show. */
    val handled: Int = 0,
    /** Reposts made from the desk today, which is what tips it towards a thought instead. */
    val reposts: Int = 0,
    /** The picks come from the default topic because the chosen ones had nothing new. */
    val fellBack: Boolean = false,
    /** The [CuratorPickRules] the picks were chosen by; a day picked by older rules is picked again. */
    val rules: Int = 0,
) {
    /** Every pick has been handled; there has to be one, or there was no day to finish. */
    val finished: Boolean get() = !picks.isNullOrEmpty() && handled >= picks.size
}

/** What one site's curator desk remembers on this device. */
@Serializable
data class CuratorState(
    /** What the writer writes about; empty until they said. */
    val topics: List<Topic> = emptyList(),
    val day: CuratorDay? = null,
    /** The posts the desk showed, by id, and when (epoch milliseconds), so none comes back for a while. */
    val seen: Map<String, Long> = emptyMap(),
    /** The local date (ISO) up to which the writer sent the desk away; it is back the next day. */
    val hiddenOn: String? = null,
    /** The sites (handle domains) the writer never wants on the desk again. */
    val hiddenSites: Set<String> = emptySet(),
)

/**
 * The curator desk's memory, one entry per account id, as JSON in plain preferences: nothing here is secret. Kept on
 * this device only, since what was shown is a matter of this phone's day. The state is read once and then served
 * from memory, so [states] is always current.
 */
@Singleton
class CuratorStore @Inject constructor(@ApplicationContext context: Context, private val json: Json) {
    private val prefs = context.getSharedPreferences("curator_desk", Context.MODE_PRIVATE)

    private val _states = MutableStateFlow(readAll())

    /** Each account's state by account id; an account that isn't listed has the default one. */
    val states: StateFlow<Map<String, CuratorState>> = _states.asStateFlow()

    /** Replaces the state of [accountId] with what [change] makes of it, and keeps it. */
    @Synchronized
    fun update(accountId: String, change: (CuratorState) -> CuratorState) {
        val current = _states.value[accountId] ?: CuratorState()
        val next = change(current)
        if (next == current) return
        prefs.edit { putString(KeyPrefix + accountId, json.encodeToString(next)) }
        _states.value += accountId to next
    }

    /** Drops what was stored for an account that was signed out of. */
    @Synchronized
    fun forgetAccount(accountId: String) {
        prefs.edit { remove(KeyPrefix + accountId) }
        _states.value -= accountId
    }

    private fun readAll(): Map<String, CuratorState> =
        prefs.all.keys.filter { it.startsWith(KeyPrefix) }.mapNotNull { key ->
            val state = try {
                prefs.getString(key, null)?.let { json.decodeFromString<CuratorState>(it) }
            } catch (_: SerializationException) {
                // A state that can't be read is as good as none: the writer picks their topics again.
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            state?.let { key.removePrefix(KeyPrefix) to it }
        }.toMap()

    private companion object {
        const val KeyPrefix = "state:"
    }
}
