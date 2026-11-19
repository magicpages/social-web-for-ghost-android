package co.magicpages.socialweb.data.ghost

import co.magicpages.socialweb.data.account.Credential
import co.magicpages.socialweb.data.account.GhostSite
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Talks to the Ghost Admin API: site lookup, staff sign-in, the social web identity token and the site's recommendations. */
@Singleton
class GhostAdminClient @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) {
    /**
     * Resolves what the user typed (a domain, site URL or admin URL) to a Ghost site.
     * Follows redirects so a separate admin domain ends up in [GhostSite.adminApiUrl].
     */
    suspend fun lookUpSite(input: String): GhostSite {
        val base = normalizeSiteInput(input) ?: throw GhostException.NotAGhostSite
        val response = try {
            http.get("${base}ghost/api/admin/site/") { acceptVersion() }
        } catch (e: IOException) {
            throw GhostException.Network(e)
        }
        if (!response.status.isSuccess()) throw GhostException.NotAGhostSite
        val site = try {
            json.decodeFromString<SiteEnvelope>(response.bodyAsText()).site
        } catch (_: SerializationException) {
            throw GhostException.NotAGhostSite
        } catch (_: IllegalArgumentException) {
            throw GhostException.NotAGhostSite
        }
        val major = site.version.substringBefore('.').toIntOrNull() ?: 0
        if (major < 6) throw GhostException.UnsupportedVersion(site.version)

        val finalUrl = response.call.request.url.toString()
        return GhostSite(
            title = site.title,
            siteUrl = site.url.ensureTrailingSlash(),
            adminApiUrl = finalUrl.substringBeforeLast("site/"),
            version = site.version,
            iconUrl = site.icon,
            accentColor = site.accentColor,
        )
    }

    /**
     * The site's accent colour as set in Ghost Admin (e.g. `#ff1a75`); null when the site has none.
     * Read again when an account is opened, so a colour changed in Ghost Admin reaches the app.
     */
    suspend fun accentColor(site: GhostSite): String? {
        val response = send { http.get("${site.adminApiUrl}site/") { acceptVersion() } }
        if (!response.status.isSuccess()) throw response.toException()
        return try {
            json.decodeFromString<SiteEnvelope>(response.bodyAsText()).site.accentColor
        } catch (e: SerializationException) {
            throw GhostException.Unexpected(response.status.value, e.message)
        } catch (e: IllegalArgumentException) {
            throw GhostException.Unexpected(response.status.value, e.message)
        }
    }

    /** Starts a staff session. Ghost often asks for an emailed code before the session is usable. */
    suspend fun signIn(site: GhostSite, email: String, password: String): SignInResult {
        val response = send {
            http.post("${site.adminApiUrl}session/") {
                sessionHeaders(site, cookies = emptyMap())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(SessionRequest(email.trim(), password)))
            }
        }
        val cookies = response.mergeCookies(emptyMap())
        return when {
            response.status.isSuccess() -> SignInResult.SignedIn(Credential.Session(cookies))
            response.status.value == 403 && response.ghostError()?.needsVerification == true ->
                SignInResult.NeedsVerification(cookies)
            else -> throw response.toException()
        }
    }

    /** Completes a session that is waiting for the emailed verification code. */
    suspend fun verifySession(site: GhostSite, pendingCookies: Map<String, String>, code: String): Credential.Session {
        val response = send {
            http.put("${site.adminApiUrl}session/verify/") {
                sessionHeaders(site, pendingCookies)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(VerifyRequest(code.trim())))
            }
        }
        if (!response.status.isSuccess()) throw response.toException()
        return Credential.Session(response.mergeCookies(pendingCookies))
    }

    /** Emails a fresh verification code for a pending session. */
    suspend fun resendVerificationCode(site: GhostSite, pendingCookies: Map<String, String>) {
        val response = send {
            http.post("${site.adminApiUrl}session/verify/") {
                sessionHeaders(site, pendingCookies)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }
        if (!response.status.isSuccess()) throw response.toException()
    }

    /** Ends the staff session on the server. Staff tokens have no server-side session to end. */
    suspend fun signOut(site: GhostSite, credential: Credential) {
        if (credential !is Credential.Session) return
        send { http.delete("${site.adminApiUrl}session/") { sessionHeaders(site, credential.cookies) } }
    }

    /**
     * Exchanges the staff credential for the JWT the social web API expects as a Bearer token.
     * Throws [GhostException.Unauthorized] when Ghost rejects the credential.
     */
    suspend fun identityToken(site: GhostSite, credential: Credential): String {
        val response = send {
            http.get("${site.adminApiUrl}identities/") { authenticate(site, credential) }
        }
        if (!response.status.isSuccess()) throw response.toException()
        val token = try {
            json.decodeFromString<IdentitiesEnvelope>(response.bodyAsText()).identities.firstOrNull()?.token
        } catch (e: SerializationException) {
            throw GhostException.Unexpected(response.status.value, e.message)
        }
        return token ?: throw GhostException.Unexpected(response.status.value, "No identity token in response")
    }

    /** The sites this Ghost site recommends to its readers (`GET recommendations/?limit=all`). */
    suspend fun recommendations(site: GhostSite, credential: Credential): List<AdminRecommendation> {
        val response = send {
            http.get("${site.adminApiUrl}recommendations/?limit=all") { authenticate(site, credential) }
        }
        if (!response.status.isSuccess()) throw response.toException()
        return response.decodeRecommendations()
    }

    /**
     * Recommends [url] the way Ghost Admin does: `POST recommendations/check/` reads the site's title, icon and
     * excerpt (and answers with the existing entry when it is recommended already), then `POST recommendations/`
     * adds it. Ghost then lets the recommended site know. Returns the stored recommendation.
     */
    suspend fun recommend(site: GhostSite, credential: Credential, url: String): AdminRecommendation {
        val check = send {
            http.post("${site.adminApiUrl}recommendations/check/") {
                authenticate(site, credential)
                contentType(ContentType.Application.Json)
                setBody(recommendationCheckBody(url))
            }
        }
        if (!check.status.isSuccess()) throw check.toException()
        val draft = check.decodeRecommendations().firstOrNull()
            ?: throw GhostException.Unexpected(check.status.value, "No recommendation in response")
        if (draft.id != null) return draft

        val added = send {
            http.post("${site.adminApiUrl}recommendations/") {
                authenticate(site, credential)
                contentType(ContentType.Application.Json)
                setBody(recommendationAddBody(draft, url))
            }
        }
        if (!added.status.isSuccess()) throw added.toException()
        return added.decodeRecommendations().firstOrNull()
            ?: throw GhostException.Unexpected(added.status.value, "No recommendation in response")
    }

    /** Stops recommending the site stored under [id] (`DELETE recommendations/:id/`). */
    suspend fun removeRecommendation(site: GhostSite, credential: Credential, id: String) {
        val response = send {
            http.delete("${site.adminApiUrl}recommendations/${id.encodeURLPathPart()}/") { authenticate(site, credential) }
        }
        if (!response.status.isSuccess()) throw response.toException()
    }

    private suspend fun HttpResponse.decodeRecommendations(): List<AdminRecommendation> = try {
        json.decodeFromString<RecommendationsEnvelope>(bodyAsText()).recommendations
    } catch (e: SerializationException) {
        throw GhostException.Unexpected(status.value, e.message)
    } catch (e: IllegalArgumentException) {
        throw GhostException.Unexpected(status.value, e.message)
    }

    private fun HttpRequestBuilder.authenticate(site: GhostSite, credential: Credential) {
        when (credential) {
            is Credential.Session -> sessionHeaders(site, credential.cookies)
            is Credential.StaffToken -> {
                acceptVersion()
                header(HttpHeaders.Authorization, "Ghost ${StaffTokenJwt.sign(credential.token, System.currentTimeMillis() / 1000)}")
            }
        }
    }

    private fun HttpRequestBuilder.sessionHeaders(site: GhostSite, cookies: Map<String, String>) {
        acceptVersion()
        // Ghost rejects session requests whose Origin doesn't match the one the session was created with.
        header(HttpHeaders.Origin, site.adminOrigin)
        if (cookies.isNotEmpty()) {
            header(HttpHeaders.Cookie, cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
    }

    private fun HttpRequestBuilder.acceptVersion() = header("Accept-Version", "v6.0")

    private suspend fun send(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: IOException) {
        throw GhostException.Network(e)
    }

    private suspend fun HttpResponse.ghostError(): GhostError? = try {
        json.decodeFromString<ErrorsEnvelope>(bodyAsText()).errors.firstOrNull()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private suspend fun HttpResponse.toException(): GhostException {
        val error = ghostError()
        return when (status.value) {
            401, 403 -> GhostException.Unauthorized(error?.message)
            400, 404, 422 -> GhostException.Rejected(error?.message)
            429 -> GhostException.TooManyAttempts(error?.message)
            else -> GhostException.Unexpected(status.value, error?.message)
        }
    }

    /** Applies `Set-Cookie` headers to [current], keeping raw values so they round-trip byte for byte. */
    private fun HttpResponse.mergeCookies(current: Map<String, String>): Map<String, String> {
        val merged = current.toMutableMap()
        for (header in headers.getAll(HttpHeaders.SetCookie).orEmpty()) {
            val parts = header.split(';').map { it.trim() }
            val name = parts.first().substringBefore('=', missingDelimiterValue = "").trim()
            if (name.isEmpty()) continue
            val value = parts.first().substringAfter('=')
            val expired = value.isEmpty() || parts.drop(1).any { it.equals("Max-Age=0", ignoreCase = true) }
            if (expired) merged.remove(name) else merged[name] = value
        }
        return merged
    }

    @Serializable private data class SiteEnvelope(val site: SiteInfo)

    @Serializable
    private data class SiteInfo(
        val title: String,
        val url: String,
        val version: String,
        val icon: String? = null,
        @SerialName("accent_color") val accentColor: String? = null,
    )

    @Serializable private data class SessionRequest(val username: String, val password: String)

    @Serializable private data class VerifyRequest(val token: String)

    @Serializable private data class IdentitiesEnvelope(val identities: List<Identity> = emptyList())

    @Serializable private data class RecommendationsEnvelope(val recommendations: List<AdminRecommendation> = emptyList())

    @Serializable private data class Identity(val token: String? = null)

    @Serializable private data class ErrorsEnvelope(val errors: List<GhostError> = emptyList())

    @Serializable
    private data class GhostError(val message: String? = null, val type: String? = null, val code: String? = null) {
        val needsVerification: Boolean
            get() = type == "Needs2FAError" || code?.startsWith("2FA_") == true
    }
}

sealed interface SignInResult {
    data class SignedIn(val credential: Credential.Session) : SignInResult

    /** Ghost emailed a code; finish with [GhostAdminClient.verifySession] using these cookies. */
    data class NeedsVerification(val pendingCookies: Map<String, String>) : SignInResult
}

sealed class GhostException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    data object NotAGhostSite : GhostException()

    class UnsupportedVersion(val version: String) : GhostException("Ghost $version")

    class Network(cause: IOException) : GhostException(cause.message, cause)

    /** Credentials or session rejected (HTTP 401/403). */
    class Unauthorized(val ghostMessage: String?) : GhostException(ghostMessage)

    /** Input rejected, e.g. unknown email, wrong password or code (HTTP 400/404/422). */
    class Rejected(val ghostMessage: String?) : GhostException(ghostMessage)

    class TooManyAttempts(val ghostMessage: String?) : GhostException(ghostMessage)

    class Unexpected(val status: Int, detail: String?) : GhostException("HTTP $status: $detail")
}

/**
 * Turns "example.com", "https://example.com/blog" or a pasted admin URL like
 * "https://example.com/ghost/#/dashboard" into a base URL with a trailing slash.
 * Returns null when nothing usable is left.
 */
internal fun normalizeSiteInput(input: String): String? {
    var url = input.trim()
    if (url.isEmpty() || url.any { it.isWhitespace() }) return null
    if (!url.contains("://")) url = "https://$url"
    url = url.substringBefore('#').substringBefore('?')
    val ghostIndex = url.indexOf("/ghost", startIndex = url.indexOf("://") + 3)
    if (ghostIndex >= 0 && (url.length == ghostIndex + 6 || url[ghostIndex + 6] == '/')) {
        url = url.substring(0, ghostIndex)
    }
    // A mistyped host or port ("example.com:80x") would otherwise crash the HTTP client's URL parser.
    val parsed = try {
        Url(url)
    } catch (_: IllegalArgumentException) {
        return null
    } catch (_: IllegalStateException) { // URLParserException
        return null
    }
    if (parsed.protocol.name !in setOf("http", "https") || parsed.host.isEmpty() || parsed.port !in 1..65_535) return null
    return url.ensureTrailingSlash()
}

private fun String.ensureTrailingSlash() = if (endsWith('/')) this else "$this/"

/** One of the site's own recommendations as the Admin API returns it. [id] is null for a site that was checked but isn't recommended. */
@Serializable
data class AdminRecommendation(
    val id: String? = null,
    val url: String? = null,
    val title: String? = null,
    val description: String? = null,
    val excerpt: String? = null,
    @SerialName("featured_image") val featuredImage: String? = null,
    val favicon: String? = null,
    @SerialName("one_click_subscribe") val oneClickSubscribe: Boolean = false,
)

/** The body of `POST recommendations/check/`. */
internal fun recommendationCheckBody(url: String): String = buildJsonObject {
    putJsonArray("recommendations") { add(buildJsonObject { put("url", url) }) }
}.toString()

/** The body of `POST recommendations/`: the details the check found, with the address that was asked for. */
internal fun recommendationAddBody(draft: AdminRecommendation, requested: String): String = buildJsonObject {
    putJsonArray("recommendations") {
        add(
            buildJsonObject {
                put("url", draft.url ?: requested)
                put("title", draft.title?.takeIf { it.isNotBlank() } ?: parseWebUri(requested)?.host ?: requested)
                put("one_click_subscribe", draft.oneClickSubscribe)
                put("description", draft.description)
                put("excerpt", draft.excerpt)
                put("featured_image", draft.featuredImage)
                put("favicon", draft.favicon)
            },
        )
    }
}.toString()
