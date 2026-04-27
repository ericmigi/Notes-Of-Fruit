package com.example.applenotes.auth

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private const val TAG = "AppleNotesAuth"

const val USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.4 Safari/605.1.15"

val LENIENT_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * What we get back from setup.icloud.com — everything CloudKit needs.
 */
data class ICloudSession(
    val dsid: String,
    val ckdatabasewsUrl: String,
    val cookieHeader: String,
    val clientId: String,
    val locale: String = "en_US",
    val timezone: String = "US/Pacific",
)

class AppleNotesSession(
    private val httpClient: HttpClient,
    private val cookieReader: CookieReader,
) {
    /**
     * Bootstrap the CloudKit session from the WebView cookie jar.
     *
     * POSTs `setup.icloud.com/setup/ws/1/validate` (the endpoint icloud.com's
     * SPA uses to refresh its session given cookies). Body is the literal
     * string "null"; cookies + clientId in the query string do all the auth.
     */
    suspend fun bootstrap(): Result<ICloudSession> = runCatching {
        val cookieMap = cookieReader.cookieMap()
        val cookieHeader = cookieReader.cookieHeader()
            ?: throw IllegalStateException("No icloud.com cookies in WebView jar.")

        if ("X-APPLE-WEBAUTH-TOKEN" !in cookieMap) {
            throw IllegalStateException(
                "X-APPLE-WEBAUTH-TOKEN cookie missing — user hasn't finished " +
                    "logging in to iCloud. Cookies seen: ${cookieMap.keys}"
            )
        }

        val clientId = UUID.randomUUID().toString().uppercase()

        val response = httpClient.post("https://setup.icloud.com/setup/ws/1/validate") {
            url {
                parameters.append("clientBuildNumber", "2612Build21")
                parameters.append("clientMasteringNumber", "2612Build21")
                parameters.append("clientId", clientId)
            }
            headers {
                append(HttpHeaders.Cookie, cookieHeader)
                append(HttpHeaders.Origin, "https://www.icloud.com")
                append(HttpHeaders.Referrer, "https://www.icloud.com/")
                append(HttpHeaders.UserAgent, USER_AGENT)
            }
            contentType(ContentType.Application.Json)
            setBody("null")
        }

        val raw = response.body<String>()
        Log.i(TAG, "validate response: HTTP ${response.status.value}, body[0..400]=${raw.take(400)}")
        val parsed = LENIENT_JSON.decodeFromString(AccountLoginResponse.serializer(), raw)

        val dsid = parsed.dsInfo?.dsid
            ?: throw IllegalStateException(
                "setup.icloud.com /validate missing dsInfo.dsid (HTTP ${response.status.value}): ${raw.take(800)}"
            )
        val ckUrl = parsed.webservices?.ckdatabasews?.url
            ?: throw IllegalStateException(
                "setup.icloud.com /validate missing webservices.ckdatabasews (HTTP ${response.status.value}): ${raw.take(800)}"
            )

        val refreshedHeader = cookieReader.cookieHeader() ?: cookieHeader

        ICloudSession(
            dsid = dsid,
            ckdatabasewsUrl = ckUrl.trimEnd('/'),
            cookieHeader = refreshedHeader,
            clientId = clientId,
            locale = parsed.dsInfo.locale ?: "en_US",
            timezone = parsed.dsInfo.timezone ?: "US/Pacific",
        )
    }
}

@Serializable
internal data class AccountLoginResponse(
    val dsInfo: DsInfo? = null,
    val webservices: Webservices? = null,
)

@Serializable
internal data class DsInfo(
    val dsid: String? = null,
    val locale: String? = null,
    @SerialName("appleId") val appleId: String? = null,
    @SerialName("primaryEmail") val primaryEmail: String? = null,
    @SerialName("fullName") val fullName: String? = null,
    @SerialName("languageCode") val languageCode: String? = null,
    @SerialName("countryCode") val countryCode: String? = null,
    val timezone: String? = null,
)

@Serializable
internal data class Webservices(
    val ckdatabasews: WebserviceEndpoint? = null,
)

@Serializable
internal data class WebserviceEndpoint(
    val url: String? = null,
    val status: String? = null,
)
