package dev.wiserlocal.app

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.util.concurrent.Executors

/** Quick settings tile that switches away mode on or off. */
class AwayTileService : TileService() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() = sync(toggle = false)

    override fun onClick() = sync(toggle = true)

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun sync(toggle: Boolean) {
        val prefs = Prefs(this)
        if (!prefs.configured) {
            show(Tile.STATE_INACTIVE, getString(R.string.widget_setup))
            return
        }
        if (toggle) show(qsTile?.state ?: Tile.STATE_INACTIVE, getString(R.string.loading))

        io.execute {
            val (state, subtitle) = try {
                val hub = prefs.hub()
                var away = hub.fetch().system.away
                if (toggle) {
                    away = !away
                    hub.setAway(away)
                }
                if (away) Tile.STATE_ACTIVE to getString(R.string.on)
                else Tile.STATE_INACTIVE to getString(R.string.off)
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
        tile.label = getString(R.string.away_mode)
        tile.subtitle = subtitle
        tile.updateTile()
    }
}
