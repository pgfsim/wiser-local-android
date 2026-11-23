package dev.wiserlocal.app

import android.content.Context

/** Hub connection details and app options, kept in app-private storage. */
class Prefs(context: Context) {
    private val app = context.applicationContext
    private val sp = context.applicationContext.getSharedPreferences("wiser", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()

    var secret: String
        get() = sp.getString("secret", "") ?: ""
        set(v) = sp.edit().putString("secret", v).apply()

    /** Room shown on the home screen widget; -1 means "first room with a reading". */
    var widgetRoomId: Int
        get() = sp.getInt("widgetRoomId", -1)
        set(v) = sp.edit().putInt("widgetRoomId", v).apply()

    var boostMinutes: Int
        get() = sp.getInt("boostMinutes", 60)
        set(v) = sp.edit().putInt("boostMinutes", v).apply()

    /** "system", "light" or "dark". */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    /**
     * Cards on the home screen, in order: "room:<id>" or "hotwater".
     * Null until the user picks, meaning every room then hot water.
     */
    var favourites: List<String>?
        get() = sp.getString("favourites", null)?.split(',')?.filter { it.isNotBlank() }
        set(v) = sp.edit().putString("favourites", v?.joinToString(",")).apply()

    /** When the app last looked for an update by itself, in phone time. */
    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()

    /** Experimental: reach the hub through Wiser's cloud when it isn't on this network. */
    var remoteControl: Boolean
        get() = sp.getBoolean("remoteControl", false)
        set(v) = sp.edit().putBoolean("remoteControl", v).apply()

    /** The hub's id in Wiser's cloud, learned from its MAC address while at home. */
    var hubCloudId: String?
        get() = sp.getString("hubCloudId", null)
        set(v) = sp.edit().putString("hubCloudId", v).apply()

    val configured: Boolean get() = host.isNotBlank() && secret.isNotBlank()

    fun hub() = WiserHub(host, secret, relay())

    private fun relay(): CloudRelay? {
        if (!remoteControl) return null
        val id = hubCloudId ?: return null
        val store = CloudStore(app)
        if (store.refreshToken == null) return null
        return CloudRelay(store.base, id, accessToken = { CloudAuth.accessToken(app) }, expireToken = { CloudAuth.expire() })
    }
}
