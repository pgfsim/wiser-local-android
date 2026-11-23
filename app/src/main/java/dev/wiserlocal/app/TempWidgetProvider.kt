package dev.wiserlocal.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
import java.util.Date
import kotlin.concurrent.thread

/**
 * Home screen widget showing one room's temperature.
 *
 * Android refreshes it roughly every 30 minutes (the platform minimum); it also
 * refreshes when the refresh icon is tapped and whenever the app loads data.
 */
class TempWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAsync(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshAsync(context)
    }

    private fun refreshAsync(context: Context) {
        val app = context.applicationContext
        val pending = goAsync()
        thread {
            try {
                val prefs = Prefs(app)
                if (!prefs.configured) {
                    push(app, app.getString(R.string.widget_setup), viaCloud = false)
                } else {
                    try {
                        val hub = prefs.hub()
                        val state = hub.fetch()
                        render(app, state, viaCloud = hub.usedCloud)
                    } catch (e: Exception) {
                        // Keep the last known reading on screen and flag it as stale.
                        push(app, app.getString(R.string.widget_offline), viaCloud = false)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_REFRESH = "dev.wiserlocal.app.WIDGET_REFRESH"

        fun requestRefresh(context: Context) {
            context.sendBroadcast(
                Intent(context, TempWidgetProvider::class.java).setAction(ACTION_REFRESH)
            )
        }

        /**
         * Draws fresh hub data on every placed widget and remembers it for later. [viaCloud]
         * adds a small cloud mark when the data came through Wiser's cloud (away from home).
         */
        fun render(context: Context, state: HubState, viaCloud: Boolean = false) {
            val wanted = Prefs(context).widgetRoomId
            val room = state.rooms.firstOrNull { it.id == wanted }
                ?: state.rooms.firstOrNull { it.tempC != null }
                ?: state.rooms.firstOrNull()

            cache(context).edit()
                .putString("name", room?.name ?: context.getString(R.string.widget_no_rooms))
                .putString("temp", WiserHub.formatTemp(room?.tempC))
                .putString(
                    "detail",
                    when {
                        room == null -> ""
                        room.heating -> context.getString(
                            R.string.widget_target_heating,
                            WiserHub.formatSetPoint(room.setPointC),
                        )
                        else -> context.getString(
                            R.string.widget_target,
                            WiserHub.formatSetPoint(room.setPointC),
                        )
                    },
                )
                .apply()

            push(context, DateFormat.getTimeFormat(context).format(Date()), viaCloud)
        }

        private fun push(context: Context, status: String, viaCloud: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TempWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val cache = cache(context)
            val views = RemoteViews(context.packageName, R.layout.widget_temp)
            views.setTextViewText(R.id.widget_temp, cache.getString("temp", "--"))
            views.setTextViewText(R.id.widget_room, cache.getString("name", context.getString(R.string.app_name)))
            views.setTextViewText(R.id.widget_detail, cache.getString("detail", ""))
            views.setTextViewText(R.id.widget_status, status)
            views.setViewVisibility(R.id.widget_cloud, if (viaCloud) android.view.View.VISIBLE else android.view.View.GONE)

            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, TempWidgetProvider::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            manager.updateAppWidget(ids, views)
        }

        private fun cache(context: Context) =
            context.getSharedPreferences("widget", Context.MODE_PRIVATE)
    }
}
