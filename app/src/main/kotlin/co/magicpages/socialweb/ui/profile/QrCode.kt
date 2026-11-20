package co.magicpages.socialweb.ui.profile

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code as its dark and light modules, without any quiet zone; [size] modules on each side. */
class QrMatrix internal constructor(val size: Int, private val dark: BooleanArray) {
    /** Whether the module at column [x], row [y] is dark; everything outside the code is light. */
    operator fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && dark[y * size + x]

    /** Pixels along one side of [pixels]. */
    fun side(scale: Int, quiet: Int = QuietZone): Int = (size + 2 * quiet) * scale

    /**
     * The code as ARGB pixels, row by row: each module [scale] pixels wide, with [quiet] light modules
     * around it. Scanners need that margin to find the code; the spec asks for 4.
     */
    fun pixels(scale: Int, darkColor: Int, lightColor: Int, quiet: Int = QuietZone): IntArray {
        val side = side(scale, quiet)
        val out = IntArray(side * side) { lightColor }
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (!this[x, y]) continue
                for (dy in 0 until scale) {
                    val row = ((y + quiet) * scale + dy) * side + (x + quiet) * scale
                    for (dx in 0 until scale) out[row + dx] = darkColor
                }
            }
        }
        return out
    }

    companion object {
        /** The quiet zone, in modules, that the QR specification asks for. */
        const val QuietZone = 4
    }
}

object QrCode {
    /**
     * [text] as a QR code with medium error correction, which keeps a profile link small enough to
     * scan from across a table. Null when the text is too long for any QR code.
     */
    fun encode(text: String): QrMatrix? {
        if (text.isEmpty()) return null
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // The quiet zone is added when drawing, so the matrix is the bare modules.
            EncodeHintType.MARGIN to 0,
        )
        val bits = try {
            // Width and height 0: the smallest output, one pixel per module.
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        } catch (_: WriterException) {
            return null
        }
        val size = bits.width
        val modules = BooleanArray(size * size)
        for (y in 0 until size) for (x in 0 until size) modules[y * size + x] = bits.get(x, y)
        return QrMatrix(size, modules)
    }
}
