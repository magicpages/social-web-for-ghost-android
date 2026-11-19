package co.magicpages.socialweb.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountColorsTest {
    private val pink = 0xFFFF1A75.toInt()
    private val blue = 0xFF1E88E5.toInt()

    @Test
    fun `accent colors as Ghost may send them become opaque colors`() {
        assertEquals(pink, parseHexColor("#FF1A75"))
        assertEquals(pink, parseHexColor("ff1a75"))
        assertEquals(0xFFFFAA00.toInt(), parseHexColor(" #fa0 "))
    }

    @Test
    fun `anything that isn't a hex color is no color`() {
        assertNull(parseHexColor(null))
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#"))
        assertNull(parseHexColor("#ff1a7"))
        assertNull(parseHexColor("#80ff1a75"))
        assertNull(parseHexColor("#gg1a75"))
        assertNull(parseHexColor("pink"))
    }

    @Test
    fun `the site color themes the app unless the account chose otherwise`() {
        assertEquals(pink, themeSeed("#ff1a75", AccountColors()))
        assertNull(themeSeed("#ff1a75", AccountColors(ColorSource.Device, custom = blue)))
        assertEquals(blue, themeSeed("#ff1a75", AccountColors(ColorSource.Custom, custom = blue)))
    }

    @Test
    fun `a site without an accent color gets the device colors`() {
        assertNull(themeSeed(null, AccountColors()))
        assertNull(themeSeed("", AccountColors(ColorSource.Site, custom = blue)))
    }

    @Test
    fun `a custom choice without a picked color falls back to the site color`() {
        assertEquals(pink, themeSeed("#ff1a75", AccountColors(ColorSource.Custom)))
    }
}
