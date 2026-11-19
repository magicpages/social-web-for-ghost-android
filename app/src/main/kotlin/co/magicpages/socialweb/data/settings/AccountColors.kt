package co.magicpages.socialweb.data.settings

import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Where an account's colours come from. */
enum class ColorSource {
    /** The accent colour set for the site in Ghost Admin. */
    Site,

    /** Android's colours: from the wallpaper on Android 12 and later. */
    Device,

    /** A colour picked in Settings. */
    Custom,
}

/** One account's colour choice. [custom] (ARGB) is the picked colour; it is kept while another source is chosen. */
data class AccountColors(val source: ColorSource = ColorSource.Site, val custom: Int? = null)

/**
 * The colour the theme is built from, as ARGB, or null for Android's own colours. A site without a
 * (readable) accent colour falls back to Android's colours too.
 */
fun themeSeed(siteAccent: String?, colors: AccountColors): Int? = when (colors.source) {
    ColorSource.Site -> parseHexColor(siteAccent)
    ColorSource.Device -> null
    ColorSource.Custom -> colors.custom ?: parseHexColor(siteAccent)
}

/** `#rrggbb` or `#rgb` (the `#` optional, as Ghost doesn't insist on it) as opaque ARGB; null for anything else. */
fun parseHexColor(hex: String?): Int? {
    val digits = hex?.trim()?.removePrefix("#") ?: return null
    if (digits.any { Character.digit(it, 16) < 0 }) return null
    val rgb = when (digits.length) {
        6 -> digits
        3 -> digits.map { "$it$it" }.joinToString("")
        else -> return null
    }
    return (0xFF000000 or rgb.toLong(16)).toInt()
}

/** The active account's theme colour, for every activity's theme. */
@Singleton
class ThemeColors @Inject constructor(
    accounts: AccountStore,
    settings: AppSettings,
    @ApplicationScope scope: CoroutineScope,
) {
    /** ARGB seed for the active account, or null for Android's colours (also while signed out). */
    val seed: StateFlow<Int?> = combine(accounts.account, settings.accountColors) { account, choices ->
        account?.let { themeSeed(it.site.accentColor, choices[it.id] ?: AccountColors()) }
    }.stateIn(scope, SharingStarted.Eagerly, null)
}
