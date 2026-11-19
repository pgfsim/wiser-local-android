package dev.wiserlocal.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The server refused the password or the saved sign-in; the user has to sign in again. */
class CloudAuthException(message: String) : IOException(message)

/** Who is signed in, from the login response. */
data class CloudAccount(
    val email: String?,
    val firstName: String?,
    val lastName: String?,
    val countryCode: String?,
    val timeZoneId: String?,
    val hubModel: String?,
)

data class CloudSession(
    val accessToken: String,
    val refreshToken: String,
    /** When [accessToken] stops working, in phone time. */
    val expiresAtMs: Long,
    val account: CloudAccount,
)

/**
 * Wiser's cloud service, used only for things the hub does not keep (the heat
 * report). Requests copy what the official app sends; the account password goes
 * only to [login] and is never stored.
 */
class WiserCloud(private val base: String = DEFAULT_BASE) {

    fun login(email: String, password: String): CloudSession = tokenRequest(
        mapOf("grant_type" to "password", "username" to email, "password" to password, "remember" to "true"),
        previousRefresh = null,
    )

    /** Gets a fresh access token; the server may also hand back a new refresh token. */
    fun refresh(refreshToken: String): CloudSession = tokenRequest(
        mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken),
        previousRefresh = refreshToken,
    )

    /** Ends the session on the server. As captured, the access token goes in a "Basic" header here. */
    fun logout(session: CloudSession) {
        post(
            "/api/account/logout",
            mapOf("grant_type" to "refresh_token", "refresh_token" to session.refreshToken),
            "Basic ${session.accessToken}",
        )
    }

    private fun tokenRequest(form: Map<String, String>, previousRefresh: String?): CloudSession {
        val json = JSONObject(post("/Login", form, "Basic $CLIENT_CREDENTIALS"))
        val access = json.optString("access_token").ifEmpty { throw IOException("Wiser did not return a sign-in token") }
        val refresh = json.optString("refresh_token").ifEmpty { previousRefresh ?: throw IOException("Wiser did not return a refresh token") }
        // Renew a minute early so a request never goes out with a token about to lapse.
        val expiresIn = json.optLong("expires_in", 3600)
        fun field(name: String) = json.optString(name).takeIf { it.isNotEmpty() }
        return CloudSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMs = System.currentTimeMillis() + (expiresIn - 60).coerceAtLeast(60) * 1000,
            account = CloudAccount(
                email = field("CurrentEmail"),
                firstName = field("FirstName"),
                lastName = field("LastName"),
                countryCode = field("CountryCode"),
                timeZoneId = field("TimeZoneId"),
                hubModel = field("WiserHeatHubModelNumber"),
            ),
        )
    }

    private fun post(path: String, form: Map<String, String>, authorization: String): String {
        val body = form.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Authorization", authorization)
            conn.setRequestProperty("Wiser-app-version", APP_VERSION)
            // User-Agent and gzip are left to HttpURLConnection, which sends the phone's own
            // "Dalvik/..." agent and unpacks gzip by itself.
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 400 || code == 401) throw CloudAuthException(errorMessage(code, text))
            if (code !in 200..299) throw IOException(errorMessage(code, text))
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(code: Int, text: String): String {
        val described = runCatching {
            val json = JSONObject(text)
            json.optString("error_description").ifEmpty { json.optString("Message") }
        }.getOrNull().orEmpty()
        return when {
            described.isNotEmpty() -> described
            code == 400 || code == 401 -> "Wiser did not accept the email or password"
            else -> "Wiser's server returned HTTP $code"
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://api-nl.wiserair.com"

        /** The official app's own client id and secret; the same for every user. */
        private const val CLIENT_CREDENTIALS =
            "SGs1dXRvMjBhMzVjeFdQOHB6aTRNNnZRVzN6NmlVdkI6aWRvRWhMVk4wbk55VldmZ29oekJSWW90RXR4UWptdkVqbGgwemNkSA=="

        /** The official app version the server expects to see. */
        private const val APP_VERSION = "7.8.0"
    }
}
