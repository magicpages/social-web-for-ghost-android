package co.magicpages.socialweb.data.ghost

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffTokenJwtTest {
    private val id = "5f4e3d2c1b0a998877665544"
    private val secretHex = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

    @Test
    fun `signs a five minute admin token keyed by the decoded hex secret`() {
        val jwt = StaffTokenJwt.sign("$id:$secretHex", nowEpochSeconds = 1_700_000_000)
        val (header, payload, signature) = jwt.split('.')

        assertEquals("""{"alg":"HS256","typ":"JWT","kid":"$id"}""", header.decodeBase64Url())
        assertEquals("""{"iat":1700000000,"exp":1700000300,"aud":"/admin/"}""", payload.decodeBase64Url())

        // Ghost verifies with the secret's bytes, not its hex text.
        val key = secretHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val expected = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal("$header.$payload".toByteArray())
        }
        assertArrayEquals(expected, Base64.getUrlDecoder().decode(signature))
    }

    @Test
    fun `accepts tokens with surrounding whitespace and rejects malformed ones`() {
        assertTrue(StaffTokenJwt.isValid("  $id:$secretHex\n"))
        assertFalse(StaffTokenJwt.isValid(id))
        assertFalse(StaffTokenJwt.isValid("$id:not-hex"))
        assertFalse(StaffTokenJwt.isValid("$id:abc")) // odd-length hex can't be decoded to bytes
    }

    private fun String.decodeBase64Url() = String(Base64.getUrlDecoder().decode(this))
}
