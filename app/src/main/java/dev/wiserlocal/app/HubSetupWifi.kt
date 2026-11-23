package dev.wiserlocal.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** The hub's setup Wi-Fi, from the QR code on its label. */
data class HubWifi(val ssid: String, val password: String?)

/**
 * Gets the hub secret the way the official app does: join the hub's own setup Wi-Fi
 * (from the label's QR code) and read http://192.168.8.1/secret. Android asks the user
 * to approve joining; the phone goes back to its normal Wi-Fi afterwards.
 */
object HubSetupWifi {

    private const val HUB_SETUP_ADDRESS = "192.168.8.1"
    private const val TIMEOUT_MS = 60_000L

    /** Reads a standard Wi-Fi QR code: WIFI:S:<ssid>;T:WPA;P:<password>;; (backslash escapes allowed). */
    fun parse(text: String): HubWifi? {
        if (!text.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = mutableMapOf<String, String>()
        val body = text.substring(5)
        var i = 0
        while (i < body.length) {
            val colon = body.indexOf(':', i)
            if (colon < 0) break
            val key = body.substring(i, colon).uppercase()
            val value = StringBuilder()
            var j = colon + 1
            while (j < body.length && body[j] != ';') {
                if (body[j] == '\\' && j + 1 < body.length) j++
                value.append(body[j])
                j++
            }
            if (key.isNotEmpty()) fields[key] = value.toString()
            i = j + 1
        }
        val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
        return HubWifi(ssid, fields["P"]?.takeIf { it.isNotEmpty() })
    }

    /**
     * Joins [wifi], fetches the secret and lets the network go. [onResult] runs on the main
     * thread with the secret or the error.
     */
    fun fetchSecret(context: Context, wifi: HubWifi, onResult: (Result<String>) -> Unit) {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val specifier = WifiNetworkSpecifier.Builder().setSsid(wifi.ssid).apply {
            wifi.password?.let { setWpa2Passphrase(it) }
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        var done = false
        lateinit var callback: ConnectivityManager.NetworkCallback
        fun finish(result: Result<String>) {
            main.post {
                if (done) return@post
                done = true
                runCatching { cm.unregisterNetworkCallback(callback) } // leaves the hub's network
                onResult(result)
            }
        }
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                thread(name = "hub-secret") { finish(runCatching { readSecret(network) }) }
            }

            override fun onUnavailable() {
                finish(Result.failure(IOException("Couldn't join ${wifi.ssid}. Press Setup on the hub so its light flashes, then try again.")))
            }
        }
        try {
            cm.requestNetwork(request, callback, TIMEOUT_MS.toInt())
        } catch (e: Exception) {
            finish(Result.failure(e))
        }
    }

    private fun readSecret(network: Network): String {
        val conn = network.openConnection(URL("http://$HUB_SETUP_ADDRESS/secret")) as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("The hub answered HTTP $code instead of its secret")
            // The answer is the secret, sometimes as a JSON string in quotes.
            val text = conn.inputStream.bufferedReader().use { it.readText() }.trim().trim('"')
            if (text.isEmpty() || text.length > 512 || text.any { it.isWhitespace() }) {
                throw IOException("The hub didn't send a secret")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
