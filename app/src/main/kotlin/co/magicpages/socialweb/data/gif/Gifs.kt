package co.magicpages.socialweb.data.gif

import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.ImageRejectedException
import co.magicpages.socialweb.data.ghost.GhostAdminClient
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.media.LocalImages
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GIFs for the composers, the way the signed-in site's editor offers them: only when its Ghost has a
 * KLIPY key, searched with that key, and attached as the post's image.
 */
@Singleton
class Gifs @Inject constructor(
    private val accounts: AccountStore,
    private val ghost: GhostAdminClient,
    private val klipy: KlipyClient,
    private val localImages: LocalImages,
) {
    private val lock = Mutex()

    /** What each account's site answered, null for no key. Asked once per app run; a failure isn't remembered. */
    private val configs = HashMap<String, KlipyConfig?>()

    /** The signed-in site's KLIPY settings; null when it has no GIFs or couldn't be asked. */
    suspend fun config(): KlipyConfig? {
        val account = accounts.account.first() ?: return null
        lock.withLock { if (account.id in configs) return configs[account.id] }
        val config = try {
            ghost.klipyConfig(account.site, account.credential)
        } catch (_: GhostException) {
            return null
        }
        lock.withLock { configs[account.id] = config }
        return config
    }

    /** See [KlipyClient.gifs]. */
    suspend fun page(config: KlipyConfig, term: String, next: String?): KlipyPage = klipy.gifs(config, term, next)

    /**
     * Downloads [gif] in the largest size the site takes and saves it where the composers read picked
     * images from. Returns its `file:` Uri; throws [KlipyException] or [ImageRejectedException].
     */
    suspend fun attach(gif: KlipyGif): String {
        val bytes = klipy.download(gif, ActivityPubClient.MAX_IMAGE_BYTES)
        return when (val staged = localImages.stage(bytes, "image/gif")) {
            is LocalImages.Staged.Ready -> staged.uri
            is LocalImages.Staged.Rejected -> throw ImageRejectedException(staged.reason)
        }
    }
}
