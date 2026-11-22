package co.magicpages.socialweb.data.translation

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers, per website, whether its articles are read translated: the last choice made on a site
 * applies to its next article. Nothing here is secret, so plain preferences do.
 */
@Singleton
class TranslationSites @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("translation_sites", Context.MODE_PRIVATE)

    /** Whether the articles of the site [articleUrl] is on are read translated, going by the last choice made there. */
    fun isOn(articleUrl: String?): Boolean {
        val site = siteOf(articleUrl) ?: return false
        return site in sites(KeySites)
    }

    /** Whether the last choice on the site [articleUrl] is on was "Show original": automatic translation leaves its articles. */
    fun isOff(articleUrl: String?): Boolean {
        val site = siteOf(articleUrl) ?: return false
        return site in sites(KeyOffSites)
    }

    fun set(articleUrl: String?, on: Boolean) {
        val site = siteOf(articleUrl) ?: return
        prefs.edit {
            putStringSet(KeySites, if (on) sites(KeySites) + site else sites(KeySites) - site)
            putStringSet(KeyOffSites, if (on) sites(KeyOffSites) - site else sites(KeyOffSites) + site)
        }
    }

    // A copy: the set `getStringSet` returns must not be modified.
    private fun sites(key: String): Set<String> = prefs.getStringSet(key, null)?.toSet() ?: emptySet()

    private companion object {
        const val KeySites = "translated_sites"
        const val KeyOffSites = "original_sites"
    }
}

/** The host of [url] without "www.", so example.com and www.example.com are one site; null when [url] has none. */
fun siteOf(url: String?): String? {
    val host = try {
        URI(url?.trim().orEmpty()).host
    } catch (_: URISyntaxException) {
        null
    }
    val site = host?.lowercase()?.removePrefix("www.")
    return site?.takeIf { it.isNotEmpty() }
}
