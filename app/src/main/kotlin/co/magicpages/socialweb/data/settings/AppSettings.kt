package co.magicpages.socialweb.data.settings

import android.content.Context
import androidx.core.content.edit
import co.magicpages.socialweb.data.activitypub.Notification
import co.magicpages.socialweb.data.feed.SavedFeedPosition
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Choices from the Settings screen: device-wide ones, and each account's colours. Nothing here is secret, so plain preferences do. */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _autoplayVideos = MutableStateFlow(prefs.getBoolean(KeyAutoplay, true))

    /** Whether timeline videos play muted on their own while on screen. */
    val autoplayVideos: StateFlow<Boolean> = _autoplayVideos.asStateFlow()

    fun setAutoplayVideos(enabled: Boolean) {
        prefs.edit { putBoolean(KeyAutoplay, enabled) }
        _autoplayVideos.value = enabled
    }

    private val _announcedTypes = MutableStateFlow(readAnnouncedTypes())

    /** Which kinds of notification the background check turns into system notifications. */
    val announcedTypes: StateFlow<Set<Notification.Type>> = _announcedTypes.asStateFlow()

    fun setAnnounced(type: Notification.Type, enabled: Boolean) {
        val next = if (enabled) _announcedTypes.value + type else _announcedTypes.value - type
        prefs.edit { putStringSet(KeyAnnounced, next.mapTo(mutableSetOf()) { it.name }) }
        _announcedTypes.value = next
    }

    private fun readAnnouncedTypes(): Set<Notification.Type> {
        val stored = prefs.getStringSet(KeyAnnounced, null) ?: return AnnounceableTypes
        return stored.mapNotNullTo(mutableSetOf()) { name -> Notification.Type.entries.firstOrNull { it.name == name } }
    }

    private val _accountColors = MutableStateFlow(readAccountColors())

    /** Each account's colour choice by account id. An account that isn't listed uses [AccountColors] defaults. */
    val accountColors: StateFlow<Map<String, AccountColors>> = _accountColors.asStateFlow()

    fun setAccountColors(accountId: String, colors: AccountColors) {
        prefs.edit {
            putString(KeyColorSource + accountId, colors.source.name)
            if (colors.custom != null) putInt(KeyCustomColor + accountId, colors.custom) else remove(KeyCustomColor + accountId)
        }
        _accountColors.value += accountId to colors
    }

    /** Drops what was stored for an account that was signed out of. */
    fun forgetAccount(accountId: String) {
        prefs.edit {
            remove(KeyColorSource + accountId)
            remove(KeyCustomColor + accountId)
            prefs.all.keys
                .filter { it.startsWith("$KeyFeedPosition$accountId:") }
                .forEach { remove(it) }
        }
        _accountColors.value -= accountId
    }

    /** Where [accountId] left the feed named [feed] (a `Feed` name), or null before the first time. */
    fun feedPosition(accountId: String, feed: String): SavedFeedPosition? =
        SavedFeedPosition.decode(prefs.getString("$KeyFeedPosition$accountId:$feed", null))

    fun saveFeedPosition(accountId: String, feed: String, position: SavedFeedPosition) {
        prefs.edit { putString("$KeyFeedPosition$accountId:$feed", position.encode()) }
    }

    private fun readAccountColors(): Map<String, AccountColors> =
        prefs.all.keys.filter { it.startsWith(KeyColorSource) }.associate { key ->
            val id = key.removePrefix(KeyColorSource)
            val source = ColorSource.entries.firstOrNull { it.name == prefs.getString(key, null) } ?: ColorSource.Site
            val custom = if (prefs.contains(KeyCustomColor + id)) prefs.getInt(KeyCustomColor + id, 0) else null
            id to AccountColors(source, custom)
        }

    companion object {
        /** The kinds a user can switch on or off; all are on until they choose otherwise. */
        val AnnounceableTypes: Set<Notification.Type> = Notification.Type.entries.toSet() - Notification.Type.Unknown

        private const val KeyAutoplay = "autoplay_videos"
        private const val KeyAnnounced = "announced_notification_types"
        private const val KeyColorSource = "color_source:"
        private const val KeyCustomColor = "custom_color:"
        private const val KeyFeedPosition = "feed_position:"
    }
}
