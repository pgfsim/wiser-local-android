package dev.wiserlocal.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

class SettingsActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private var roomIds = listOf<Int>()

    private lateinit var prefs: Prefs
    private lateinit var host: EditText
    private lateinit var secret: EditText
    private lateinit var minutes: EditText
    private lateinit var result: TextView
    private lateinit var widgetRoom: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = Prefs(this)
        host = findViewById(R.id.host)
        secret = findViewById(R.id.secret)
        minutes = findViewById(R.id.boost_minutes)
        result = findViewById(R.id.result)
        widgetRoom = findViewById(R.id.widget_room)

        if (savedInstanceState == null) {
            host.setText(prefs.host)
            secret.setText(prefs.secret)
            minutes.setText(prefs.boostMinutes.toString())
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            save()
            test()
        }

        widgetRoom.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val roomId = roomIds.getOrNull(position) ?: return
                if (roomId != prefs.widgetRoomId) {
                    prefs.widgetRoomId = roomId
                    TempWidgetProvider.requestRefresh(this@SettingsActivity)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        if (prefs.configured) test()
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun save() {
        prefs.host = host.text.toString().trim().removePrefix("http://").trimEnd('/')
        prefs.secret = secret.text.toString().trim()
        prefs.boostMinutes = (minutes.text.toString().toIntOrNull() ?: 60).coerceIn(5, 180)
        minutes.setText(prefs.boostMinutes.toString())
    }

    private fun test() {
        if (!prefs.configured) {
            result.setText(R.string.enter_both)
            return
        }
        result.setText(R.string.loading)
        val hub = prefs.hub()
        io.execute {
            val outcome = try {
                Result.success(hub.fetch())
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                outcome.fold(
                    onSuccess = { onConnected(it) },
                    onFailure = { result.text = getString(R.string.error, it.message ?: it.javaClass.simpleName) },
                )
            }
        }
    }

    private fun onConnected(state: HubState) {
        result.text = resources.getQuantityString(R.plurals.connected, state.rooms.size, state.rooms.size)
        roomIds = state.rooms.map { it.id }
        widgetRoom.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            state.rooms.map { it.name },
        )
        val selected = roomIds.indexOf(prefs.widgetRoomId)
        if (selected >= 0) widgetRoom.setSelection(selected)
        TempWidgetProvider.render(this, state)
    }
}
