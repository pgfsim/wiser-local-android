package dev.wiserlocal.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.pm.ShortcutManagerCompat
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Runs a launcher shortcut without opening the app: sends the request to the hub in the
 * background, says what happened in a toast and closes at once.
 */
class ShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent?.takeIf { it.action == AppShortcuts.ACTION }?.extras
        val prefs = Prefs(this)
        if (extras == null || !prefs.configured) {
            // Not set up yet: open the app instead.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val app = applicationContext
        val kind = extras.getString(AppShortcuts.EXTRA_KIND)
        val awayOn = extras.getBoolean(AppShortcuts.EXTRA_AWAY_ON)
        val momentId = extras.getInt(AppShortcuts.EXTRA_MOMENT_ID)
        val momentName = extras.getString(AppShortcuts.EXTRA_MOMENT_NAME).orEmpty()
        val shortcutId = when (kind) {
            AppShortcuts.KIND_MOMENT -> "moment:$momentId"
            else -> kind
        }
        shortcutId?.let { runCatching { ShortcutManagerCompat.reportShortcutUsed(app, it) } }
        finish()

        thread(name = "shortcut") {
            val message = try {
                val hub = prefs.hub()
                val done = when (kind) {
                    AppShortcuts.KIND_AWAY -> {
                        hub.setAway(awayOn)
                        if (awayOn) "Away mode on" else "Away mode off"
                    }
                    AppShortcuts.KIND_HOT_WATER -> {
                        val hw = hub.fetch().hotWater ?: throw IOException("The hub has no hot water")
                        val minutes = prefs.boostMinutes
                        hub.boostHotWater(hw.id, minutes)
                        "Hot water boosted for $minutes minutes"
                    }
                    AppShortcuts.KIND_BOOST_ALL -> {
                        hub.boostAllRooms()
                        "All rooms boosted for an hour"
                    }
                    AppShortcuts.KIND_MOMENT -> {
                        hub.triggerMoment(momentId)
                        "Ran $momentName"
                    }
                    else -> throw IOException("Unknown shortcut")
                }
                // Refresh the shortcuts, so "Away on" becomes "Away off" and so on.
                runCatching { AppShortcuts.publish(app, hub.fetch()) }
                done + if (hub.usedCloud) " (through Wiser's cloud)" else ""
            } catch (e: Exception) {
                "Couldn't reach the hub: ${e.message ?: e.javaClass.simpleName}"
            }
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
        }
    }
}
