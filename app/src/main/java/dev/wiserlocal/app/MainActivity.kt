package dev.wiserlocal.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()

    /** Setpoints tapped but not yet confirmed by the hub, so quick taps add up. */
    private val pendingSetPoints = mutableMapOf<Int, Double>()
    private var inFlight = 0

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        if (!prefs.configured && savedInstanceState == null) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        withHub()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_refresh -> { withHub(); true }
        R.id.menu_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Runs [action] against the hub (if given), then reloads and redraws everything. */
    private fun withHub(action: ((WiserHub) -> Unit)? = null) {
        if (!prefs.configured) {
            list.removeAllViews()
            status.setText(R.string.not_configured)
            return
        }
        val hub = prefs.hub()
        status.setText(R.string.loading)
        inFlight++
        io.execute {
            val result = try {
                if (action != null) {
                    action(hub)
                    Thread.sleep(400) // give the hub a moment to apply the change
                }
                Result.success(hub.fetch())
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                inFlight--
                if (isDestroyed || inFlight > 0) return@runOnUiThread
                pendingSetPoints.clear()
                result.fold(
                    onSuccess = { show(it) },
                    onFailure = { status.text = getString(R.string.error, it.message ?: it.javaClass.simpleName) },
                )
            }
        }
    }

    private fun show(state: HubState) {
        list.removeAllViews()
        state.rooms.forEach { addRoom(it) }
        state.hotWater?.let { addHotWater(it) }
        status.text = getString(R.string.updated, DateFormat.getTimeFormat(this).format(Date()))
        TempWidgetProvider.render(this, state)
    }

    private fun addRoom(room: Room) {
        val row = layoutInflater.inflate(R.layout.row_room, list, false)
        row.findViewById<TextView>(R.id.room_name).text = room.name
        row.findViewById<TextView>(R.id.room_temp).text = WiserHub.formatTemp(room.tempC)
        row.findViewById<TextView>(R.id.room_detail).setText(
            when {
                room.heating -> R.string.heating
                room.overridden -> R.string.manual
                else -> R.string.following_schedule
            }
        )
        val setPoint = row.findViewById<TextView>(R.id.room_setpoint)
        setPoint.text = WiserHub.formatSetPoint(room.setPointC)

        fun nudge(delta: Double) {
            val current = pendingSetPoints[room.id] ?: room.setPointC ?: 20.0
            val target = (current + delta).coerceIn(WiserHub.MIN_TEMP_C, WiserHub.MAX_TEMP_C)
            pendingSetPoints[room.id] = target
            setPoint.text = WiserHub.formatSetPoint(target)
            withHub { it.setRoomTemp(room.id, target) }
        }
        row.findViewById<Button>(R.id.room_down).setOnClickListener { nudge(-0.5) }
        row.findViewById<Button>(R.id.room_up).setOnClickListener { nudge(0.5) }

        row.findViewById<Button>(R.id.room_schedule).apply {
            visibility = if (room.overridden) View.VISIBLE else View.GONE
            setOnClickListener { withHub { it.resumeRoomSchedule(room.id) } }
        }
        row.findViewById<Button>(R.id.room_edit_schedule).apply {
            visibility = if (room.scheduleId > 0) View.VISIBLE else View.GONE
            setOnClickListener { openSchedule(room.scheduleId, heating = true, name = room.name) }
        }
        list.addView(row)
    }

    private fun addHotWater(hw: HotWater) {
        val row = layoutInflater.inflate(R.layout.row_hot_water, list, false)
        row.findViewById<TextView>(R.id.hw_state).setText(if (hw.on) R.string.on else R.string.off)
        row.findViewById<TextView>(R.id.hw_detail).text = when {
            hw.boosted && hw.overrideUntilMs > 0 -> getString(
                R.string.boosted_until,
                DateFormat.getTimeFormat(this).format(Date(hw.overrideUntilMs)),
            )
            hw.boosted -> getString(R.string.boosted)
            else -> getString(R.string.following_schedule)
        }
        row.findViewById<Button>(R.id.hw_boost).apply {
            if (hw.boosted) {
                setText(R.string.cancel_boost)
                setOnClickListener { withHub { it.cancelHotWaterBoost(hw.id) } }
            } else {
                val minutes = prefs.boostMinutes
                text = getString(R.string.boost_for, minutes)
                setOnClickListener { withHub { it.boostHotWater(hw.id, minutes) } }
            }
        }
        row.findViewById<Button>(R.id.hw_edit_schedule).apply {
            visibility = if (hw.scheduleId > 0) View.VISIBLE else View.GONE
            setOnClickListener {
                openSchedule(hw.scheduleId, heating = false, name = getString(R.string.hot_water))
            }
        }
        list.addView(row)
    }

    private fun openSchedule(scheduleId: Int, heating: Boolean, name: String) {
        startActivity(
            Intent(this, ScheduleActivity::class.java)
                .putExtra(ScheduleActivity.EXTRA_ID, scheduleId)
                .putExtra(ScheduleActivity.EXTRA_HEATING, heating)
                .putExtra(ScheduleActivity.EXTRA_TITLE, getString(R.string.schedule_title, name))
        )
    }
}
