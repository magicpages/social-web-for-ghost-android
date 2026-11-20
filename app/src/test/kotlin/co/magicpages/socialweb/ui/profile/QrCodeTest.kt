package co.magicpages.socialweb.ui.profile

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    private val dark = 0xFF000000.toInt()
    private val light = 0xFFFFFFFF.toInt()

    /** What a phone's scanner would read from the code once it is drawn. */
    private fun scan(matrix: QrMatrix, scale: Int): String {
        val side = matrix.side(scale)
        val source = RGBLuminanceSource(side, side, matrix.pixels(scale, dark, light))
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun `a profile link scans back to the same link`() {
        val url = "https://fedi.pub/@index@site.example"
        val matrix = QrCode.encode(url)!!
        assertEquals(url, scan(matrix, scale = 6))
    }

    @Test
    fun `non ASCII text survives`() {
        val url = "https://example.com/@zoë?q=ü"
        assertEquals(url, scan(QrCode.encode(url)!!, scale = 6))
    }

    @Test
    fun `a longer link makes a bigger code`() {
        val short = QrCode.encode("https://a.example/")!!
        val long = QrCode.encode("https://a.example/" + "path/".repeat(30))!!
        assertTrue(long.size > short.size)
    }

    @Test
    fun `the code has the three finder squares and a quiet zone in its pixels`() {
        val matrix = QrCode.encode("https://a.example/")!!
        // Finder patterns: dark corners at the top left, top right and bottom left.
        assertTrue(matrix[0, 0])
        assertTrue(matrix[matrix.size - 1, 0])
        assertTrue(matrix[0, matrix.size - 1])
        val pixels = matrix.pixels(scale = 2, darkColor = dark, lightColor = light)
        val side = matrix.side(2)
        assertEquals((matrix.size + 2 * QrMatrix.QuietZone) * 2, side)
        assertEquals(side * side, pixels.size)
        // Every pixel of the margin is light.
        val margin = QrMatrix.QuietZone * 2
        for (i in 0 until side) {
            assertEquals(light, pixels[i])
            assertEquals(light, pixels[i * side])
        }
        assertEquals(dark, pixels[margin * side + margin])
    }

    @Test
    fun `nothing is encoded for empty or impossibly long text`() {
        assertNull(QrCode.encode(""))
        assertNull(QrCode.encode("x".repeat(10_000)))
    }
}
