package co.magicpages.socialweb.data.ghost

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs short-lived Admin API JWTs from a Staff Access Token (`{id}:{hex secret}`),
 * as described in Ghost's Admin API token authentication docs.
 */
object StaffTokenJwt {
    private val format = Regex("^([0-9a-fA-F]+):((?:[0-9a-fA-F]{2})+)$")
    private const val LIFETIME_SECONDS = 5 * 60

    fun isValid(token: String): Boolean = format.matches(token.trim())

    fun sign(token: String, nowEpochSeconds: Long): String {
        val match = requireNotNull(format.matchEntire(token.trim())) { "Not a Staff Access Token" }
        val (id, hexSecret) = match.destructured
        val header = """{"alg":"HS256","typ":"JWT","kid":"$id"}"""
        val payload = """{"iat":$nowEpochSeconds,"exp":${nowEpochSeconds + LIFETIME_SECONDS},"aud":"/admin/"}"""
        val signingInput = "${header.base64Url()}.${payload.base64Url()}"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(hexSecret.hexToByteArray(), "HmacSHA256"))
        }
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(signingInput.encodeToByteArray()))
        return "$signingInput.$signature"
    }

    private fun String.base64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(encodeToByteArray())
}
