package dev.wiserlocal.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import java.util.concurrent.Executors

/** Add, rename and delete rooms and heating schedules, and choose which schedule a room follows. */
class ManageActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var rows: Rows
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_system)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        rows = Rows(this, findViewById(R.id.system_list))
        status = findViewById(R.id.system_status)
    }

    override fun onResume() {
        super.onResume()
        load() // also picks up edits made in the schedule editor
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /** Runs [action] against the hub (if given), then reloads everything. */
    private fun load(action: ((WiserHub) -> Unit)? = null) {
        val prefs = Prefs(this)
        if (!prefs.configured) {
            status.setText(R.string.not_configured)
            return
        }
        val hub = prefs.hub()
        status.setText(R.string.loading)
        io.execute {
            val result = try {
                if (action != null) {
                    action(hub)
                    Thread.sleep(400) // give the hub a moment to apply the change
                }
                Result.success(hub.fetch() to hub.fetchSchedules())
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { (state, schedules) -> show(state, schedules.filter { it.heating }) },
                    onFailure = { status.text = getString(R.string.error, it.message ?: it.javaClass.simpleName) },
                )
            }
        }
    }

    private fun show(state: HubState, schedules: List<Schedule>) {
        rows.clear()
        status.text = ""

        rows.header(R.string.manage_rooms)
        for (room in state.rooms) {
            val schedule = schedules.firstOrNull { it.id == room.scheduleId }
            val detail = schedule?.let { getString(R.string.room_follows, it.name) }
                ?: getString(R.string.room_no_schedule)
            rows.info(room.name, detail) { roomActions(room, state.rooms, schedules) }
        }
        rows.info(getString(R.string.add_room), getString(R.string.add_room_help)) {
            promptText(getString(R.string.add_room), "") { name -> load { it.createRoom(name) } }
        }

        rows.header(R.string.manage_schedules)
        for (schedule in schedules) {
            val users = state.rooms.filter { it.scheduleId == schedule.id }.map { it.name }
            val detail = if (users.isEmpty()) getString(R.string.schedule_unused)
            else getString(R.string.schedule_used_by, users.joinToString(", "))
            rows.info(schedule.name.ifEmpty { getString(R.string.schedule) }, detail) {
                scheduleActions(schedule, schedules, users)
            }
        }
        rows.info(getString(R.string.add_schedule), getString(R.string.add_schedule_help)) {
            promptText(getString(R.string.add_schedule), "") { name -> load { it.createHeatingSchedule(name) } }
        }

        rows.header(R.string.moments)
        for (moment in state.moments) {
            rows.info(moment.name, getString(R.string.moment_help)) { momentActions(moment) }
        }
        rows.info(getString(R.string.add_moment), getString(R.string.add_moment_help)) {
            promptText(getString(R.string.add_moment), "") { name ->
                if (name.isNotBlank()) pickMomentAction(state) { action -> load { it.createMoment(name.trim(), action) } }
            }
        }
    }

    private fun roomActions(room: Room, rooms: List<Room>, schedules: List<Schedule>) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.rename) to {
            promptText(getString(R.string.rename_title, room.name), room.name) { name ->
                load { it.renameRoom(room.id, name) }
            }
        }
        if (schedules.isNotEmpty()) {
            actions += getString(R.string.change_schedule) to { pickSchedule(room, rooms, schedules) }
        }
        actions += getString(R.string.delete) to {
            confirm(getString(R.string.delete_room_confirm, room.name), R.string.delete) {
                load { it.deleteRoom(room.id) }
            }
        }
        menu(room.name, actions)
    }

    private fun momentActions(moment: Moment) {
        menu(moment.name, listOf(
            getString(R.string.rename) to {
                promptText(getString(R.string.rename_title, moment.name), moment.name) { name ->
                    load { it.renameMoment(moment.id, name) }
                }
            },
            getString(R.string.delete) to {
                confirm(getString(R.string.delete_moment_confirm, moment.name), R.string.delete) {
                    load { it.deleteMoment(moment.id) }
                }
            },
        ))
    }

    /** Asks what the new Moment should do: a room temperature or off, or hot water on or off. */
    private fun pickMomentAction(state: HubState, onPicked: (MomentAction) -> Unit) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        for (room in state.rooms) {
            actions += getString(R.string.moment_room_temp, room.name) to {
                pickFromWheel(
                    room.name,
                    PICKER_TEMPS.map { WiserHub.formatSetPoint(it / 10.0) },
                    PICKER_TEMPS.indexOf(200).coerceAtLeast(0),
                ) { index -> onPicked(MomentAction("Room", room.id, PICKER_TEMPS[index])) }
            }
            actions += getString(R.string.moment_room_off, room.name) to {
                onPicked(MomentAction("Room", room.id, WiserHub.SCHEDULE_OFF))
            }
        }
        state.hotWater?.let { water ->
            actions += getString(R.string.moment_water_on) to {
                onPicked(MomentAction("HotWater", water.id, WiserHub.HOT_WATER_ON))
            }
            actions += getString(R.string.moment_water_off) to {
                onPicked(MomentAction("HotWater", water.id, WiserHub.SCHEDULE_OFF))
            }
            actions += getString(R.string.moment_water_boost) to {
                val durations = MainActivity.DURATIONS
                pickFromWheel(getString(R.string.moment_water_boost), durations.map { durationLabel(it) }, 1) { index ->
                    onPicked(MomentAction("HotWater", water.id, WiserHub.HOT_WATER_ON, durations[index]))
                }
            }
        }
        menu(getString(R.string.moment_action_title), actions)
    }

    private fun pickSchedule(room: Room, rooms: List<Room>, schedules: List<Schedule>) {
        val labels = schedules.map { it.name.ifEmpty { getString(R.string.schedule) } }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.change_schedule_title, room.name))
            .setSingleChoiceItems(labels.toTypedArray(), schedules.indexOfFirst { it.id == room.scheduleId }) { dialog, which ->
                dialog.dismiss()
                val chosen = schedules[which]
                if (chosen.id != room.scheduleId) {
                    // The hub wants the full list of rooms for the schedule, not just the new one.
                    val users = rooms.filter { it.scheduleId == chosen.id }.map { it.id } + room.id
                    load { it.assignSchedule(chosen, users) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun scheduleActions(schedule: Schedule, schedules: List<Schedule>, users: List<String>) {
        val name = schedule.name.ifEmpty { getString(R.string.schedule) }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.edit_schedule) to {
            startActivity(
                Intent(this, ScheduleActivity::class.java)
                    .putExtra(ScheduleActivity.EXTRA_ID, schedule.id)
                    .putExtra(ScheduleActivity.EXTRA_HEATING, true)
                    .putExtra(ScheduleActivity.EXTRA_TITLE, name)
            )
        }
        actions += getString(R.string.rename) to {
            promptText(getString(R.string.rename_title, name), schedule.name) { newName ->
                load { it.renameSchedule(schedule, newName) }
            }
        }
        val others = schedules.filter { it.id != schedule.id }
        if (others.isNotEmpty()) {
            actions += getString(R.string.copy_schedule) to {
                val labels = others.map { it.name.ifEmpty { getString(R.string.schedule) } }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.copy_schedule_title, name))
                    .setItems(labels.toTypedArray()) { _, which ->
                        val target = others[which]
                        confirm(getString(R.string.copy_schedule_confirm, labels[which], name), R.string.replace) {
                            load { it.copySchedule(schedule, target.id) }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        actions += getString(R.string.delete) to {
            val message = if (users.isEmpty()) getString(R.string.delete_schedule_confirm, name)
            else getString(R.string.delete_schedule_in_use, name, users.joinToString(", "))
            confirm(message, R.string.delete) { load { it.deleteSchedule(schedule) } }
        }
        menu(name, actions)
    }

    private fun menu(title: String, actions: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
