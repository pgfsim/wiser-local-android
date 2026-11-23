package dev.wiserlocal.app

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Experimental: sends hub requests through Wiser's cloud, as the official app does away from
 * home. Each request is wrapped as {"method":"WebMethod_GET","urlPath":"/v2/domain/","body":"…"}
 * and posted to web_action; the hub's answer comes back as {"resultCode":200,"body":"…"}.
 */
class CloudRelay(
    private val base: String,
    /** Identifies the hub: urn:dev:opm:SchneiderElectric-WiserHeat-<MAC without colons>. */
    private val deviceUrn: String,
    private val accessToken: () -> String,
    private val expireToken: () -> Unit,
) {
    /** The hub's status code and body for one request; [localPath] is the path the hub serves locally. */
    fun send(method: String, localPath: String, body: String?): Pair<Int, String> {
        val payload = JSONObject()
            .put("method", "WebMethod_$method")
            // Locally everything sits under /data (/data/v2/domain/); the cloud wants the rest.
            .put("urlPath", localPath.removePrefix("/data"))
        if (body != null) payload.put("body", body)
        repeat(2) { attempt ->
            val conn = URL("$base$PATH").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Authorization", "Bearer ${accessToken()}")
                conn.setRequestProperty("Wiser-deviceId", Base64.encodeToString(deviceUrn.toByteArray(), Base64.NO_WRAP))
                conn.setRequestProperty("Wiser-app-version", APP_VERSION)
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                val code = conn.responseCode
                if (code == 401 && attempt == 0) {
                    expireToken() // the access token ran out early; renew it and try once more
                    return@repeat
                }
                if (code == 401 || code == 403) throw CloudAuthException("Wiser's cloud refused the request. Sign in to Wiser again.")
                if (code !in 200..299) throw IOException("Wiser's cloud returned HTTP $code")
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                return json.optInt("resultCode", 200) to json.optString("body")
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Wiser's cloud refused the sign-in")
    }

    companion object {
        private const val PATH = "/api/wcs2/rpc/public_genie/web_action"
        private const val APP_VERSION = "7.8.0"

        /** The id prefixes of hubs the relay can reach, as the official app checks them. */
        private val HUB_PREFIXES = listOf(
            "urn:dev:opm:SchneiderElectric-WiserHeat",
            "urn:dev:opm:SchneiderElectric-AuraConnect",
            "urn:dev:cer:SchneiderElectric-WiserHeat",
        )

        /**
         * Asks Wiser's cloud for the account's hub id, as the official app does:
         * GET /api/account/summary lists the sites, each with its devices' ControllerId.
         * Null if the account has no hub.
         */
        fun lookUpHubId(base: String, accessToken: String): String? {
            val conn = URL("$base/api/account/summary").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Authorization", "Bearer $accessToken")
                conn.setRequestProperty("Wiser-app-version", APP_VERSION)
                val code = conn.responseCode
                if (code == 401 || code == 403) throw CloudAuthException("Wiser's cloud refused the request. Sign in to Wiser again.")
                if (code !in 200..299) throw IOException("Wiser's cloud returned HTTP $code")
                val sites = org.json.JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                for (i in 0 until sites.length()) {
                    val devices = sites.optJSONObject(i)?.optJSONArray("Devices") ?: continue
                    for (j in 0 until devices.length()) {
                        val id = devices.optJSONObject(j)?.optString("ControllerId").orEmpty()
                        if (HUB_PREFIXES.any { id.startsWith(it) }) return id
                    }
                }
                return null
            } finally {
                conn.disconnect()
            }
        }

        /** The hub's cloud id from its MAC address ("FC:FE:C2:07:9A:F4" or "FCFEC2079AF4"). */
        fun urnForMac(mac: String): String? {
            val hex = mac.filter { it.isLetterOrDigit() }.uppercase()
            if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
            return "urn:dev:opm:SchneiderElectric-WiserHeat-$hex"
        }
    }
}
