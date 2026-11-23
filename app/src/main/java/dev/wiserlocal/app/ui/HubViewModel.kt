package dev.wiserlocal.app.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.wiserlocal.app.CloudAccount
import dev.wiserlocal.app.CloudAuthException
import dev.wiserlocal.app.CloudSession
import dev.wiserlocal.app.CloudStore
import dev.wiserlocal.app.Device
import dev.wiserlocal.app.HubState
import dev.wiserlocal.app.MomentAction
import dev.wiserlocal.app.AutomationDetail
import dev.wiserlocal.app.Prefs
import dev.wiserlocal.app.Room
import dev.wiserlocal.app.Schedule
import dev.wiserlocal.app.TempWidgetProvider
import dev.wiserlocal.app.HeatReport
import dev.wiserlocal.app.InsightsOverview
import dev.wiserlocal.app.ReportRange
import dev.wiserlocal.app.WiserCloud
import dev.wiserlocal.app.WiserInsights
import java.time.LocalDate
import dev.wiserlocal.app.OpenThermInfo
import dev.wiserlocal.app.WiserHub
import dev.wiserlocal.app.usesOpenTherm
import java.io.IOException
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
    var momentActions by mutableStateOf<Map<Int, List<MomentAction>?>>(emptyMap()); private set

    /** What each automation does, by id; null where the hub's answer could not be read. */
    var automationDetails by mutableStateOf<Map<Int, AutomationDetail?>>(emptyMap()); private set
    var network by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set
    var openTherm by mutableStateOf(OpenThermInfo.NONE); private set

    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var updatedAt by mutableStateOf<Long?>(null); private set
    var configured by mutableStateOf(prefs.configured); private set

    /** One-off message for the snackbar; the UI clears it once shown. */
    var message by mutableStateOf<String?>(null)

    /** Experimental: reach the hub through Wiser's cloud when away from home. */
    var remoteControl by mutableStateOf(prefs.remoteControl); private set

    /** True when the last load went through Wiser's cloud rather than straight to the hub. */
    var viaCloud by mutableStateOf(false); private set

    var theme by mutableStateOf(prefs.theme); private set
    var favourites by mutableStateOf(prefs.favourites); private set

    /** Target temperatures tapped but not yet confirmed by the hub, so quick taps add up. */
    val pendingSetPoints = mutableStateMapOf<Int, Double>()

    /** Device flashing because of Identify, if any. */
    var identifying by mutableStateOf<Device?>(null); private set

    // Wiser cloud account, for Insights. Runs on its own thread so it never holds up the hub.
    private val cloudStore = CloudStore(app)
    private val cloudIo = Executors.newSingleThreadExecutor()
    private val session: CloudSession? get() = dev.wiserlocal.app.CloudAuth.session
    var cloudAccount by mutableStateOf(cloudStore.account); private set
    var cloudSignedIn by mutableStateOf(cloudStore.refreshToken != null); private set
    var cloudBusy by mutableStateOf(false); private set
    var cloudError by mutableStateOf<String?>(null); private set

    // Heat report from Wiser's Insights site.
    // Debug builds keep the last heat report page in app-private storage, so a changed
    // page can be pulled with `adb shell run-as` and checked against the parser.
    private val insights = WiserInsights(
        cloudStore.insightsBase,
        accessToken = { cloudAccessToken() },
        debugDump = if (dev.wiserlocal.app.BuildConfig.DEBUG) {
            { html -> java.io.File(app.filesDir, "last_heat_report.html").writeText(html) }
        } else null,
    )
    var reportRange by mutableStateOf(ReportRange.DAY); private set
    var reportDate by mutableStateOf(LocalDate.now()); private set
    var report by mutableStateOf<HeatReport?>(null); private set
    var reportBusy by mutableStateOf(false); private set
    var reportError by mutableStateOf<String?>(null); private set
    var overview by mutableStateOf<InsightsOverview?>(null); private set
    var overviewError by mutableStateOf<String?>(null); private set

    private var inFlight = 0
    private var wantMoments = false
    private var wantSystem = false

    private class Loaded(
        val state: HubState,
        val schedules: List<Schedule>,
        val momentActions: Map<Int, List<MomentAction>?>?,
        val automationDetails: Map<Int, AutomationDetail?>?,
        val network: List<Pair<String, String>>?,
        val openTherm: OpenThermInfo?,
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
                val actions = if (moments && state.features.moments) {
                    state.moments.associate { m -> m.id to try { hub.fetchMomentActions(m.id) } catch (e: Exception) { null } }
                } else null
                val automations = if (moments && state.features.automations) {
                    state.automations.associate { a -> a.id to try { hub.fetchAutomation(a.id) } catch (e: Exception) { null } }
                } else null
                val net = if (system) try { hub.fetchNetwork() } catch (e: Exception) { emptyList() } else null
                // OpenTherm settings and readings only matter once OpenTherm is the boiler type.
                val boiler = if (system && (state.system.openTherm == "Connected" || state.system.usesOpenTherm)) {
                    try { hub.fetchOpenTherm() } catch (e: Exception) { OpenThermInfo.NONE }
                } else if (system) OpenThermInfo.NONE else null
                Loaded(state, schedules, actions, automations, net, boiler)
            }
            result.getOrNull()?.let { dev.wiserlocal.app.AppShortcuts.publish(getApplication(), it.state) }
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
                        loaded.automationDetails?.let { automationDetails = it }
                        loaded.network?.let { network = it }
                        loaded.openTherm?.let { openTherm = it }
                        error = null
                        updatedAt = System.currentTimeMillis()
                        viaCloud = hub.usedCloud
                        TempWidgetProvider.render(getApplication(), loaded.state)
                    },
                    onFailure = { error = errorText(it) },
                )
            }
        }
    }

    /** Also load what each Moment and automation does while a screen that shows them is open. */
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

    /** Loads the Insights landing page figures (savings, activity, 30 days of heating). */
    fun loadOverview() {
        if (!cloudSignedIn) return
        val eco = state?.system?.ecoMode
        cloudIo.execute {
            val result = runCatching { insights.overview(eco) }
            main.post {
                result.fold(
                    onSuccess = { overview = it; overviewError = null },
                    onFailure = { overviewError = it.message ?: it.javaClass.simpleName },
                )
            }
        }
    }

    /** Loads the heat report for [range] around [date] (defaults: what is showing now). */
    fun showReport(range: ReportRange = reportRange, date: LocalDate = reportDate) {
        reportRange = range
        reportDate = date
        if (!cloudSignedIn) return
        reportBusy = true
        reportError = null
        cloudIo.execute {
            val result = runCatching { insights.heatReport(range, date) }
            main.post {
                if (range != reportRange || date != reportDate) return@post // a newer request is on its way
                reportBusy = false
                result.fold(
                    onSuccess = { report = it },
                    onFailure = {
                        if (it is CloudAuthException) {
                            forgetSession()
                            cloudError = "Your Wiser sign-in has expired. Please sign in again."
                        } else {
                            reportError = it.message ?: it.javaClass.simpleName
                        }
                    },
                )
            }
        }
    }

    /** Signs in to the Wiser account. The password is used for this request only. */
    fun cloudSignIn(email: String, password: String) {
        cloudBusy = true
        cloudError = null
        val cloud = WiserCloud(cloudStore.base)
        cloudIo.execute {
            val result = runCatching { cloud.login(email.trim(), password).also(::keepSession) }
            main.post {
                cloudBusy = false
                result.fold(
                    onSuccess = { cloudSignedIn = true; cloudAccount = it.account; message = "Signed in to Wiser" },
                    onFailure = { cloudError = it.message ?: it.javaClass.simpleName },
                )
            }
        }
    }

    /** Checks the saved sign-in still works (renewing it if needed) when Insights opens. */
    fun cloudCheck() {
        if (!cloudSignedIn || cloudBusy) return
        cloudBusy = true
        cloudIo.execute {
            val failure = runCatching { cloudAccessToken() }.exceptionOrNull()
            main.post {
                cloudBusy = false
                cloudAccount = session?.account ?: cloudAccount
                when (failure) {
                    null -> cloudError = null
                    is CloudAuthException -> {
                        forgetSession()
                        cloudError = "Your Wiser sign-in has expired. Please sign in again."
                    }
                    else -> cloudError = "Couldn't reach Wiser: ${failure.message ?: failure.javaClass.simpleName}"
                }
            }
        }
    }

    /**
     * A current access token for Wiser's cloud, renewing it with the saved
     * refresh token when it has run out. Call off the main thread.
     */
    fun cloudAccessToken(): String = dev.wiserlocal.app.CloudAuth.accessToken(getApplication())

    /**
     * Turns the cloud route on or off. Turning it on needs the hub's cloud id, which comes
     * from its MAC address, so the first time it has to be done at home.
     */
    fun changeRemoteControl(on: Boolean) {
        if (!on) {
            prefs.remoteControl = false
            remoteControl = false
            viaCloud = false
            return
        }
        val hub = WiserHub(prefs.host, prefs.secret) // straight to the hub, for the fallback below
        val base = cloudStore.base
        cloudIo.execute {
            // Ask Wiser's cloud for the hub's id, as the official app does. If that fails, keep
            // an id found before, or work it out from the hub's MAC address (only possible at home).
            val fromCloud = runCatching { dev.wiserlocal.app.CloudRelay.lookUpHubId(base, cloudAccessToken()) }
            val urn = fromCloud.getOrNull()
                ?: prefs.hubCloudId
                ?: runCatching { hub.fetchMacAddress()?.let(dev.wiserlocal.app.CloudRelay::urnForMac) }.getOrNull()
            main.post {
                if (urn != null) {
                    prefs.hubCloudId = urn
                    prefs.remoteControl = true
                    remoteControl = true
                } else {
                    message = if (fromCloud.isSuccess) {
                        "Wiser's cloud doesn't list a hub on this account"
                    } else {
                        "Couldn't find the hub's id. Check the phone is online, or turn this on at home."
                    }
                }
            }
        }
    }

    fun cloudSignOut() {
        cloudBusy = true
        val cloud = WiserCloud(cloudStore.base)
        cloudIo.execute {
            // Tell the server too, so the saved token stops working; sign out locally either way.
            runCatching {
                val current = session?.takeIf { System.currentTimeMillis() < it.expiresAtMs }
                    ?: cloudStore.refreshToken?.let { cloud.refresh(it) }
                current?.let { cloud.logout(it) }
            }
            main.post {
                cloudBusy = false
                forgetSession()
                cloudError = null
                message = "Signed out of Wiser"
            }
        }
    }

    private fun keepSession(s: CloudSession) = dev.wiserlocal.app.CloudAuth.keep(getApplication(), s)

    private fun forgetSession() {
        dev.wiserlocal.app.CloudAuth.forget()
        report = null
        overview = null
        cloudStore.clear()
        cloudSignedIn = false
        cloudAccount = null
    }

    override fun onCleared() {
        stopIdentify()
        io.shutdown()
        cloudIo.shutdown()
        super.onCleared()
    }

    private fun errorText(e: Throwable) = "Error: " + (e.message ?: e.javaClass.simpleName)
}
