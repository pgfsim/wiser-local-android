package dev.wiserlocal.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
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
        R.id.menu_system -> { startActivity(Intent(this, SystemActivity::class.java)); true }
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
        addSummary(state.system)
        state.rooms.forEach { addRoom(it) }
        state.hotWater?.let { addHotWater(it) }
        status.text = getString(R.string.updated, time(System.currentTimeMillis()))
        TempWidgetProvider.render(this, state)
    }

    private fun addSummary(system: SystemInfo) {
        val row = layoutInflater.inflate(R.layout.row_summary, list, false)
        row.findViewById<TextView>(R.id.summary_title).setText(
            if (system.boilerFiring) R.string.boiler_on else R.string.boiler_off
        )
        row.findViewById<TextView>(R.id.summary_detail).setText(
            if (system.away) R.string.away_on else R.string.away_off
        )
        row.findViewById<Button>(R.id.summary_away).apply {
            setText(if (system.away) R.string.away_cancel else R.string.away_set)
            setOnClickListener { withHub { it.setAway(!system.away) } }
        }
        list.addView(row)
    }

    private fun addRoom(room: Room) {
        val row = layoutInflater.inflate(R.layout.row_room, list, false)
        row.findViewById<TextView>(R.id.room_name).text = room.name
        row.findViewById<TextView>(R.id.room_temp).text = WiserHub.formatTemp(room.tempC)

        val isOff = room.setPointC != null && room.setPointC < WiserHub.MIN_TEMP_C
        row.findViewById<TextView>(R.id.room_detail).text = when {
            room.boosted && room.overrideUntilMs > 0 ->
                getString(R.string.boosted_until, time(room.overrideUntilMs))
            room.boosted -> getString(R.string.boosted)
            room.heating && room.demandPercent > 0 -> getString(R.string.heating_demand, room.demandPercent)
            room.heating -> getString(R.string.heating)
            room.mode == "Manual" && isOff -> getString(R.string.off)
            room.mode == "Manual" -> getString(R.string.mode_manual)
            room.overridden -> getString(R.string.manual)
            else -> getString(R.string.following_schedule)
        }

        val extras = listOfNotNull(
            room.humidity?.let { getString(R.string.humidity, it) },
            if (room.windowOpen) getString(R.string.window_open) else null,
        )
        row.findViewById<TextView>(R.id.room_extra).apply {
            text = extras.joinToString(" · ")
            visibility = if (extras.isEmpty()) View.GONE else View.VISIBLE
        }

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

        row.findViewById<Button>(R.id.room_boost).apply {
            if (room.boosted) {
                setText(R.string.cancel_boost)
                setOnClickListener { withHub { it.resumeRoomSchedule(room.id) } }
            } else {
                setText(R.string.boost)
                setOnClickListener { pickRoomBoost(room) }
            }
        }

        row.findViewById<Button>(R.id.room_more).setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, MENU_MODE, 0, R.string.menu_mode)
            if (room.scheduleId > 0) {
                if (room.mode == "Auto") popup.menu.add(0, MENU_ADVANCE, 1, R.string.advance_schedule)
                popup.menu.add(0, MENU_EDIT, 2, R.string.edit_schedule)
            }
            if (room.overridden && !room.boosted) popup.menu.add(0, MENU_RESUME, 3, R.string.resume_schedule)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_MODE -> pickRoomMode(room, isOff)
                    MENU_ADVANCE -> withHub { it.advanceRoom(room) }
                    MENU_EDIT -> openSchedule(room.scheduleId, heating = true, name = room.name)
                    MENU_RESUME -> withHub { it.resumeRoomSchedule(room.id) }
                }
                true
            }
            popup.show()
        }
        list.addView(row)
    }

    private fun pickRoomBoost(room: Room) {
        val labels = BOOST_MINUTES.map { getString(R.string.boost_option, BOOST_DELTA_C.toInt(), durationLabel(it)) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.boost_title, room.name))
            .setItems(labels.toTypedArray()) { _, which ->
                withHub { it.boostRoom(room.id, BOOST_DELTA_C, BOOST_MINUTES[which]) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickRoomMode(room: Room, isOff: Boolean) {
        val modes = RoomMode.values()
        val labels = arrayOf(
            getString(R.string.following_schedule),
            getString(R.string.mode_manual_hold),
            getString(R.string.off),
        )
        val current = when {
            room.mode == "Manual" && isOff -> RoomMode.OFF
            room.mode == "Manual" -> RoomMode.MANUAL
            else -> RoomMode.AUTO
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mode_title, room.name))
            .setSingleChoiceItems(labels, modes.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                withHub { it.setRoomMode(room.id, modes[which]) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addHotWater(hw: HotWater) {
        val row = layoutInflater.inflate(R.layout.row_hot_water, list, false)
        row.findViewById<TextView>(R.id.hw_state).setText(if (hw.on) R.string.on else R.string.off)
        row.findViewById<TextView>(R.id.hw_detail).text = when {
            hw.boosted && hw.overrideUntilMs > 0 -> getString(R.string.boosted_until, time(hw.overrideUntilMs))
            hw.boosted -> getString(R.string.boosted)
            hw.mode == "Manual" -> getString(R.string.mode_manual)
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
        row.findViewById<Button>(R.id.hw_more).setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, MENU_MODE, 0, R.string.menu_mode)
            if (hw.scheduleId > 0) {
                if (hw.mode == "Auto") popup.menu.add(0, MENU_ADVANCE, 1, R.string.advance_schedule)
                popup.menu.add(0, MENU_EDIT, 2, R.string.edit_schedule)
            }
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_MODE -> pickHotWaterMode(hw)
                    MENU_ADVANCE -> withHub { it.advanceHotWater(hw) }
                    MENU_EDIT -> openSchedule(hw.scheduleId, heating = false, name = getString(R.string.hot_water))
                }
                true
            }
            popup.show()
        }
        list.addView(row)
    }

    private fun pickHotWaterMode(hw: HotWater) {
        val modes = HotWaterMode.values()
        val labels = arrayOf(
            getString(R.string.following_schedule),
            getString(R.string.mode_always_on),
            getString(R.string.mode_always_off),
        )
        val current = when {
            hw.mode != "Manual" -> HotWaterMode.AUTO
            hw.on -> HotWaterMode.ON
            else -> HotWaterMode.OFF
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mode_title, getString(R.string.hot_water)))
            .setSingleChoiceItems(labels, modes.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                withHub { it.setHotWaterMode(hw.id, modes[which]) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openSchedule(scheduleId: Int, heating: Boolean, name: String) {
        startActivity(
            Intent(this, ScheduleActivity::class.java)
                .putExtra(ScheduleActivity.EXTRA_ID, scheduleId)
                .putExtra(ScheduleActivity.EXTRA_HEATING, heating)
                .putExtra(ScheduleActivity.EXTRA_TITLE, getString(R.string.schedule_title, name))
        )
    }

    private fun time(millis: Long): String = DateFormat.getTimeFormat(this).format(Date(millis))

    private fun durationLabel(minutes: Int): String =
        if (minutes < 60) getString(R.string.minutes, minutes)
        else resources.getQuantityString(R.plurals.hours, minutes / 60, minutes / 60)

    companion object {
        private const val MENU_MODE = 1
        private const val MENU_ADVANCE = 2
        private const val MENU_EDIT = 3
        private const val MENU_RESUME = 4

        /** Same step the official app uses for a room boost. */
        private const val BOOST_DELTA_C = 2.0
        private val BOOST_MINUTES = listOf(30, 60, 120, 180)
    }
}
