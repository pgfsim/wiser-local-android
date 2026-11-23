package dev.wiserlocal.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.wiserlocal.app.ui.momentIcon

/**
 * The quick actions shown when the launcher icon is long-pressed: away mode, hot water
 * boost, boost all rooms and the hub's Moments. They are rebuilt from the hub's state
 * after each load, so only what the hub supports is offered.
 */
object AppShortcuts {

    const val ACTION = "dev.wiserlocal.app.SHORTCUT"
    const val EXTRA_KIND = "kind"
    const val EXTRA_AWAY_ON = "awayOn"
    const val EXTRA_MOMENT_ID = "momentId"
    const val EXTRA_MOMENT_NAME = "momentName"

    const val KIND_AWAY = "away"
    const val KIND_HOT_WATER = "hotWater"
    const val KIND_BOOST_ALL = "boostAll"
    const val KIND_MOMENT = "moment"

    @Volatile private var lastPublished: String? = null

    /** Replaces the shortcuts with ones for [state]; does nothing if they haven't changed. */
    fun publish(context: Context, state: HubState) {
        val app = context.applicationContext
        val shortcuts = build(app, state)
        val signature = shortcuts.joinToString("|") { "${it.id}:${it.shortLabel}" }
        if (signature == lastPublished) return
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(app).coerceAtLeast(1)
            val shown = shortcuts.take(max)
            ShortcutManagerCompat.setDynamicShortcuts(app, shown)
            // A shortcut pinned to the home screen outlives the list; switch off any whose
            // Moment has gone (or that the hub no longer supports) instead of leaving it broken.
            val ids = shown.map { it.id }.toSet()
            val stale = ShortcutManagerCompat.getShortcuts(app, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .map { it.id }.filter { it !in ids }
            if (stale.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(app, stale, "No longer on the hub")
            ShortcutManagerCompat.enableShortcuts(app, shown)
            lastPublished = signature
        }
    }

    private fun build(context: Context, state: HubState): List<ShortcutInfoCompat> {
        val out = mutableListOf<ShortcutInfoCompat>()
        if (state.features.awayMode) {
            val turnOn = !state.system.away
            out += shortcut(
                context, "away",
                if (turnOn) "Away on" else "Away off",
                if (turnOn) "Turn away mode on" else "Turn away mode off",
                R.drawable.ic_away,
            ) { putExtra(EXTRA_KIND, KIND_AWAY).putExtra(EXTRA_AWAY_ON, turnOn) }
        }
        if (state.hotWater != null) {
            out += shortcut(context, "hotWater", "Boost hot water", "Boost the hot water", R.drawable.ic_water) {
                putExtra(EXTRA_KIND, KIND_HOT_WATER)
            }
        }
        if (state.features.boostAll && state.rooms.isNotEmpty()) {
            out += shortcut(context, "boostAll", "Boost all rooms", "Boost all rooms for an hour", R.drawable.ic_flame) {
                putExtra(EXTRA_KIND, KIND_BOOST_ALL)
            }
        }
        if (state.features.moments) {
            state.moments.forEach { m ->
                out += shortcut(context, "moment:${m.id}", m.name, "Run ${m.name}", momentIcon(m.iconId, R.drawable.ic_moment)) {
                    putExtra(EXTRA_KIND, KIND_MOMENT).putExtra(EXTRA_MOMENT_ID, m.id).putExtra(EXTRA_MOMENT_NAME, m.name)
                }
            }
        }
        return out.mapIndexed { rank, s -> ShortcutInfoCompat.Builder(s).setRank(rank).build() }
    }

    private fun shortcut(
        context: Context,
        id: String,
        shortLabel: String,
        longLabel: String,
        @DrawableRes icon: Int,
        extras: Intent.() -> Intent,
    ): ShortcutInfoCompat {
        val intent = Intent(context, ShortcutActivity::class.java).setAction(ACTION).extras()
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(shortLabel.take(25))
            .setLongLabel(longLabel)
            .setIcon(adaptiveIcon(context, icon))
            .setIntent(intent)
            .build()
    }

    /** The app's icon colours: the glyph in white on the launcher icon's background. */
    private fun adaptiveIcon(context: Context, @DrawableRes glyph: Int): IconCompat {
        val density = context.resources.displayMetrics.density
        val size = (108 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(context, R.color.icon_bg))
        ContextCompat.getDrawable(context, glyph)?.mutate()?.let { d ->
            d.setTint(0xFFFFFFFF.toInt())
            val inset = (size - 44 * density).toInt() / 2 // 44dp glyph, well inside the 66dp safe zone
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(canvas)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }
}
