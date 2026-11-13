package dev.wiserlocal.app

import android.content.Context

/** Hub connection details and app options, kept in app-private storage. */
class Prefs(context: Context) {
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

    val configured: Boolean get() = host.isNotBlank() && secret.isNotBlank()

    fun hub() = WiserHub(host, secret)
}
