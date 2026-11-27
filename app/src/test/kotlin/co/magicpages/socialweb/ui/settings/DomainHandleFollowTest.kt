package co.magicpages.socialweb.ui.settings

import co.magicpages.socialweb.data.activitypub.BlueskyStatus
import co.magicpages.socialweb.data.activitypub.DomainHandle
import co.magicpages.socialweb.data.activitypub.DomainHandleStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainHandleFollowTest {
    private val bridged = "index.example.com.ap.brid.gy"

    private fun status(handle: String?, domain: DomainHandleStatus?, automatic: Boolean = true) = BlueskyStatus(
        enabled = true,
        handleConfirmed = handle != null,
        handle = handle,
        domainHandle = domain?.let { DomainHandle("example.com", it, automatic = automatic) },
    )

    @Test
    fun `enabling waits for the domain handle, then shows the default handle while a slow switch goes on`() = runTest {
        val first = status(bridged, DomainHandleStatus.Claiming)
        var asked = 0
        val shown = mutableListOf<BlueskyPhase?>()

        assertEquals(BlueskyPhase.Connecting, confirmedPhase(first, takingLong = false))
        followDomainHandle(
            first,
            confirm = { if (++asked < 30) first else status("example.com", DomainHandleStatus.Active) },
            show = { status, takingLong -> shown += confirmedPhase(status, takingLong) },
        )

        // Two minutes of asks every 5 seconds count as connecting.
        assertEquals(List(23) { BlueskyPhase.Connecting }, shown.take(23))
        assertEquals(BlueskyPhase.On("@$bridged"), shown[23])
        assertEquals(BlueskyPhase.On("@example.com"), shown.last())
        assertEquals(30, asked)
        assertEquals(24 * 5_000L + 6 * 30_000L, currentTime)
    }

    @Test
    fun `connecting ends two minutes in, even while an ask is still waiting for its answer`() = runTest {
        val first = status(bridged, DomainHandleStatus.Claiming)
        val shown = mutableListOf<Pair<Long, BlueskyPhase?>>()

        followDomainHandle(
            first,
            // Each check takes 25 seconds; the fifth runs from 105 to 125 seconds.
            confirm = { delay(20_000); first },
            show = { status, takingLong -> shown += currentTime to confirmedPhase(status, takingLong) },
        )

        assertEquals(List(4) { BlueskyPhase.Connecting }, shown.take(4).map { it.second })
        assertEquals(120_000L to BlueskyPhase.On("@$bridged"), shown[4])
    }

    @Test
    fun `a claim that has to come before the handle is followed until Bluesky accepts the domain`() = runTest {
        // Re-enabling after Bluesky refused the kept handle: the service claims the domain first.
        val claiming = status(handle = null, DomainHandleStatus.Claiming)
        var asked = 0
        val shown = mutableListOf<BlueskyPhase?>()

        val last = followDomainHandle(
            claiming,
            confirm = { if (++asked < 3) claiming else status("example.com", DomainHandleStatus.Active) },
            show = { status, takingLong -> shown += confirmedPhase(status, takingLong) },
        )

        assertEquals(listOf(null, null, BlueskyPhase.On("@example.com")), shown)
        assertEquals("@example.com", last.confirmedHandle)
    }

    @Test
    fun `a claim before the handle that fails leaves no handle`() = runTest {
        val last = followDomainHandle(
            status(handle = null, DomainHandleStatus.Claiming),
            confirm = { status(handle = null, DomainHandleStatus.Unavailable) },
            show = { _, _ -> },
        )

        assertNull(last.confirmedHandle)
    }

    @Test
    fun `enabling into a claim that comes before the handle follows it from the first answer`() = runTest {
        // Re-enabling after Bluesky refused the kept handle: the service claims the domain first.
        val claiming = status(handle = null, DomainHandleStatus.Claiming)
        var followedAt = -1L

        val connected = awaitHandle(
            claiming,
            confirm = { null },
            connected = {
                followedAt = currentTime
                status("example.com", DomainHandleStatus.Active)
            },
        )

        assertTrue(connected)
        assertEquals(0L, followedAt)
    }

    @Test
    fun `enabling gives up after a minute without a handle or a claim`() = runTest {
        var asked = 0

        val connected = awaitHandle(
            status(handle = null, domain = null),
            confirm = { asked++; status(handle = null, domain = null) },
            connected = { error("Nothing to follow") },
        )

        assertFalse(connected)
        assertEquals(12, asked)
        assertEquals(60_000L, currentTime)
    }

    @Test
    fun `a switch the publisher asked for shows the handle it has, and failed asks keep it`() = runTest {
        val first = status("@$bridged", DomainHandleStatus.Claiming, automatic = false)
        var asked = 0
        val shown = mutableListOf<BlueskyPhase?>()

        followDomainHandle(
            first,
            confirm = { if (++asked < 3) null else status("example.com", DomainHandleStatus.Active, automatic = false) },
            show = { status, takingLong -> shown += confirmedPhase(status, takingLong) },
        )

        assertEquals(listOf(BlueskyPhase.On("@$bridged"), BlueskyPhase.On("@$bridged"), BlueskyPhase.On("@example.com")), shown)
    }

    @Test
    fun `asking stops once the service would have given up on the switch`() = runTest {
        val claiming = status(bridged, DomainHandleStatus.Claiming)
        var asked = 0

        followDomainHandle(claiming, confirm = { asked++; claiming }, show = { _, _ -> })

        assertEquals(44, asked)
        assertEquals(24 * 5_000L + 20 * 30_000L, currentTime)
    }

    @Test
    fun `a service without domain handles is not asked again`() = runTest {
        var asked = 0

        followDomainHandle(status(bridged, domain = null), confirm = { asked++; null }, show = { _, _ -> })

        assertEquals(0, asked)
        assertEquals(BlueskyPhase.On("@$bridged"), confirmedPhase(status(bridged, domain = null), takingLong = false))
    }
}
