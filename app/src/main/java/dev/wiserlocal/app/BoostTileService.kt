package dev.wiserlocal.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateFormat
import java.io.IOException
import java.util.Date
import java.util.concurrent.Executors

/** Quick settings tile that starts or cancels a hot water boost. */
class BoostTileService : TileService() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() = sync(toggle = false)

    override fun onClick() {
        if (!Prefs(this).configured) {
            openApp()
            return
        }
        sync(toggle = true)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun sync(toggle: Boolean) {
        val prefs = Prefs(this)
        if (!prefs.configured) {
            show(Tile.STATE_INACTIVE, getString(R.string.tile_setup))
            return
        }
        if (toggle) show(qsTile?.state ?: Tile.STATE_INACTIVE, getString(R.string.loading))

        io.execute {
            val (state, subtitle) = try {
                val hub = prefs.hub()
                val hw = hub.fetch().hotWater ?: throw IOException("no hot water")
                var boosted = hw.boosted
                var until = hw.overrideUntilMs
                if (toggle) {
                    if (boosted) {
                        hub.cancelHotWaterBoost(hw.id)
                        boosted = false
                    } else {
                        val minutes = prefs.boostMinutes
                        hub.boostHotWater(hw.id, minutes)
                        boosted = true
                        until = System.currentTimeMillis() + minutes * 60_000L
                    }
                }
                when {
                    boosted && until > 0 -> Tile.STATE_ACTIVE to getString(
                        R.string.tile_until,
                        DateFormat.getTimeFormat(this).format(Date(until)),
                    )
                    boosted -> Tile.STATE_ACTIVE to getString(R.string.on)
                    else -> Tile.STATE_INACTIVE to getString(R.string.off)
                }
            } catch (e: Exception) {
                Tile.STATE_INACTIVE to getString(R.string.tile_unreachable)
            }
            main.post { show(state, subtitle) }
        }
    }

    private fun show(state: Int, subtitle: String) {
        // qsTile is null once the shade has closed; nothing to update then.
        val tile = qsTile ?: return
        tile.state = state
        tile.label = getString(R.string.tile_label)
        tile.subtitle = subtitle
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
