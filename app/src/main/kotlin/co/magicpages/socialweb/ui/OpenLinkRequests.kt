package co.magicpages.socialweb.ui

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A web address another app asked to open here (the "Open in Social Web" share target). Stays pending
 * until the signed-in screens exist to take it, which may be after a sign-in or a cold start, and so
 * opens for whichever account is active then, as a share to the composer does.
 */
@Singleton
class OpenLinkRequests @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun submit(url: String) {
        _pending.value = url
    }

    /** Forgets [url] unless a newer request has replaced it meanwhile. */
    fun consume(url: String) {
        _pending.compareAndSet(url, null)
    }
}
