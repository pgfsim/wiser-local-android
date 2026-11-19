package dev.wiserlocal.app.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.wiserlocal.app.Device
import dev.wiserlocal.app.HubState
import dev.wiserlocal.app.MomentAction
import dev.wiserlocal.app.Prefs
import dev.wiserlocal.app.Room
import dev.wiserlocal.app.Schedule
import dev.wiserlocal.app.TempWidgetProvider
import dev.wiserlocal.app.WiserHub
import java.util.concurrent.Executors

/**
 * Everything the screens show, loaded from the hub on one background thread.
 * Every change goes through [run], which sends it and then reloads.
 */
class HubViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    var state by mutableStateOf<HubState?>(null); private set
    var schedules by mutableStateOf<List<Schedule>>(emptyList()); private set

    /** What each Moment does, by Moment id; null where this app cannot describe it. */
    var momentActions by mutableStateOf<Map<Int, MomentAction?>>(emptyMap()); private set
    var network by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set
    var openTherm by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set

    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var updatedAt by mutableStateOf<Long?>(null); private set
    var configured by mutableStateOf(prefs.configured); private set

    /** One-off message for the snackbar; the UI clears it once shown. */
    var message by mutableStateOf<String?>(null)

    var theme by mutableStateOf(prefs.theme); private set
    var favourites by mutableStateOf(prefs.favourites); private set

    /** Target temperatures tapped but not yet confirmed by the hub, so quick taps add up. */
    val pendingSetPoints = mutableStateMapOf<Int, Double>()

    /** Device flashing because of Identify, if any. */
    var identifying by mutableStateOf<Device?>(null); private set

    private var inFlight = 0
    private var wantMoments = false
    private var wantSystem = false

    private class Loaded(
        val state: HubState,
        val schedules: List<Schedule>,
        val momentActions: Map<Int, MomentAction?>?,
        val network: List<Pair<String, String>>?,
        val openTherm: List<Pair<String, String>>?,
    )

    fun refresh() = run()

    /**
     * Sends [action] to the hub (if given), then reloads. [done] is shown once it
     * worked; [onSuccess] runs on the main thread after the action succeeded.
     */
    fun run(
        done: String? = null,
        onSuccess: (() -> Unit)? = null,
        action: ((WiserHub) -> Unit)? = null,
    ) {
        configured = prefs.configured
        if (!configured) return
        val hub = prefs.hub()
        val moments = wantMoments
        val system = wantSystem
        inFlight++
        loading = true
        io.execute {
            val actionError = try {
                if (action != null) {
                    action(hub)
                    Thread.sleep(400) // give the hub a moment to apply the change
                }
                null
            } catch (e: Exception) {
                e
            }
            val result = runCatching {
                val state = hub.fetch()
                // Schedules only add "next change" lines, so a failure here is not fatal.
                val schedules = try { hub.fetchSchedules() } catch (e: Exception) { emptyList() }
                val actions = if (moments) {
                    state.moments.associate { m -> m.id to try { hub.fetchMomentAction(m.id) } catch (e: Exception) { null } }
                } else null
                val net = if (system) try { hub.fetchNetwork() } catch (e: Exception) { emptyList() } else null
                // Boiler readings only exist when the hub is wired over OpenTherm.
                val boiler = if (system && state.system.openTherm == "Connected") {
                    try { hub.fetchOpenTherm() } catch (e: Exception) { emptyList() }
                } else if (system) emptyList() else null
                Loaded(state, schedules, actions, net, boiler)
            }
            main.post {
                inFlight--
                if (actionError != null) {
                    message = errorText(actionError)
                } else {
                    onSuccess?.invoke()
                    if (done != null) message = done
                }
                if (inFlight > 0) return@post
                loading = false
                pendingSetPoints.clear()
                result.fold(
                    onSuccess = { loaded ->
                        state = loaded.state
                        schedules = loaded.schedules
                        loaded.momentActions?.let { momentActions = it }
                        loaded.network?.let { network = it }
                        loaded.openTherm?.let { openTherm = it }
                        error = null
                        updatedAt = System.currentTimeMillis()
                        TempWidgetProvider.render(getApplication(), loaded.state)
                    },
                    onFailure = { error = errorText(it) },
                )
            }
        }
    }

    /** Also load each Moment's action while a screen that shows them is open. */
    fun watchMoments(on: Boolean) {
        wantMoments = on
        if (on) refresh()
    }

    /** Also load the hub's network and boiler details while the system screen is open. */
    fun watchSystem(on: Boolean) {
        wantSystem = on
        if (on) refresh()
    }

    fun nudge(room: Room, delta: Double) {
        val current = pendingSetPoints[room.id] ?: room.setPointC ?: 20.0
        val target = (current + delta).coerceIn(WiserHub.MIN_TEMP_C, WiserHub.MAX_TEMP_C)
        pendingSetPoints[room.id] = target
        run { it.setRoomTemp(room.id, target) }
    }

    fun saveConnection(host: String, secret: String, boostMinutes: Int) {
        prefs.host = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        prefs.secret = secret.trim()
        prefs.boostMinutes = boostMinutes.coerceIn(5, 180)
        state = null
        error = null
        refresh()
    }

    fun setWidgetRoom(roomId: Int) {
        prefs.widgetRoomId = roomId
        TempWidgetProvider.requestRefresh(getApplication())
    }

    fun chooseTheme(value: String) {
        prefs.theme = value
        theme = value
    }

    fun saveFavourites(keys: List<String>) {
        prefs.favourites = keys
        favourites = keys
    }

    /** Makes the device flash until [stopIdentify], like the official app. */
    fun identify(device: Device) {
        val hub = prefs.hub()
        io.execute {
            val failure = runCatching { hub.identifyDevice(device.id, true) }.exceptionOrNull()
            main.post {
                if (failure != null) message = errorText(failure) else identifying = device
            }
        }
    }

    fun stopIdentify() {
        val device = identifying ?: return
        identifying = null
        if (io.isShutdown) return
        val hub = prefs.hub()
        io.execute {
            // If this fails the device stops flashing by itself after a while.
            runCatching { hub.identifyDevice(device.id, false) }
        }
    }

    override fun onCleared() {
        stopIdentify()
        io.shutdown()
        super.onCleared()
    }

    private fun errorText(e: Throwable) = "Error: " + (e.message ?: e.javaClass.simpleName)
}
