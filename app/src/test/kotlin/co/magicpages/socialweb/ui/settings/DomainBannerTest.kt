package co.magicpages.socialweb.ui.settings

import co.magicpages.socialweb.data.activitypub.DomainHandle
import co.magicpages.socialweb.data.activitypub.DomainHandleCheck
import co.magicpages.socialweb.data.activitypub.DomainHandleReason
import co.magicpages.socialweb.data.activitypub.DomainHandleStatus
import co.magicpages.socialweb.ui.settings.DomainBanner.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainBannerTest {
    private val bridged = BlueskyPhase.On("@index.example.com.ap.brid.gy")

    private fun ui(
        domain: DomainSwitch,
        bluesky: BlueskyPhase = bridged,
        domainHandles: Boolean = true,
    ) = NetworkUi(bluesky = bluesky, handle = "@index@example.com", domainHandles = domainHandles, domain = domain)

    private fun handle(status: DomainHandleStatus, reason: DomainHandleReason? = null, automatic: Boolean = false) =
        DomainHandle("example.com", status, reason, automatic)

    private val available = DomainHandleCheck("example.com", available = true)

    @Test
    fun `the domain is offered only while it isn't the handle and the check says it would work`() {
        assertEquals(DomainBanner(Kind.Offer, "example.com"), domainBanner(ui(DomainSwitch(check = available))))
        // An earlier attempt that failed doesn't stop the offer once the domain works.
        val failedBefore = DomainSwitch(handle = handle(DomainHandleStatus.Unavailable, DomainHandleReason.NotReachable), check = available)
        assertEquals(Kind.Offer, domainBanner(ui(failedBefore))?.kind)

        assertNull(domainBanner(ui(DomainSwitch(check = DomainHandleCheck("example.com", available = false)))))
        assertNull(domainBanner(ui(DomainSwitch(check = available), bluesky = BlueskyPhase.On("@example.com"))))
        assertNull(domainBanner(ui(DomainSwitch(check = available), domainHandles = false)))
        assertNull(domainBanner(ui(DomainSwitch(check = available), bluesky = BlueskyPhase.Connecting)))
    }

    @Test
    fun `a switch the publisher asked for shows how it goes, one set up by enabling doesn't`() {
        val asked = DomainSwitch(requested = true)

        assertEquals(Kind.Switching, domainBanner(ui(asked.copy(requesting = true)))?.kind)
        assertEquals(Kind.Switching, domainBanner(ui(asked.copy(handle = handle(DomainHandleStatus.Claiming))))?.kind)
        assertEquals(Kind.Switched, domainBanner(ui(asked.copy(handle = handle(DomainHandleStatus.Active))))?.kind)
        assertEquals(Kind.Taken, domainBanner(ui(asked.copy(handle = handle(DomainHandleStatus.Unavailable, DomainHandleReason.Taken))))?.kind)
        assertEquals(Kind.Failed, domainBanner(ui(asked.copy(handle = handle(DomainHandleStatus.Unavailable, DomainHandleReason.Rejected))))?.kind)
        assertEquals(Kind.Failed, domainBanner(ui(asked.copy(requestFailed = true)))?.kind)

        assertNull(domainBanner(ui(DomainSwitch(handle = handle(DomainHandleStatus.Claiming, automatic = true), check = available))))
        assertNull(domainBanner(ui(DomainSwitch(handle = handle(DomainHandleStatus.Active)))))
    }

    @Test
    fun `the banner names the domain that was tried, or the one the check would use`() {
        val tried = DomainSwitch(handle = DomainHandle("old.example", DomainHandleStatus.Unavailable, DomainHandleReason.Taken), requested = true)
        val moved = DomainSwitch(handle = DomainHandle("old.example", DomainHandleStatus.Released), check = available)

        assertEquals(DomainBanner(Kind.Taken, "old.example"), domainBanner(ui(tried)))
        assertEquals(DomainBanner(Kind.Offer, "example.com"), domainBanner(ui(moved)))
        assertEquals(DomainBanner(Kind.Failed, "example.com"), domainBanner(ui(DomainSwitch(requested = true, requestFailed = true))))
    }
}
