package dev.wiserlocal.app.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import dev.wiserlocal.app.AppUpdate
import dev.wiserlocal.app.AppUpdater
import dev.wiserlocal.app.BuildConfigInfo
import dev.wiserlocal.app.Prefs
import java.util.concurrent.Executors

/** Looks for a newer release on GitHub and, if the user agrees, downloads and installs it. */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val updater = AppUpdater(app)
    private val prefs = Prefs(app)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The update offered in the dialog; null when there is nothing to show. */
    var available by mutableStateOf<AppUpdate?>(null); private set

    /** Download progress from 0 to 1 while downloading (NaN when the size is unknown), else null. */
    var progress by mutableStateOf<Float?>(null); private set
    var checking by mutableStateOf(false); private set

    /** One-off message for the snackbar; the UI clears it once shown. */
    var message by mutableStateOf<String?>(null)

    val currentVersion: String get() = BuildConfigInfo.versionName(getApplication())

    /** Checks at most once a day, quietly: nothing is shown unless there is an update. */
    fun checkDaily() {
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < DAY_MS) return
        prefs.lastUpdateCheck = now
        check(quiet = true)
    }

    /** Checks now, and says so when the app is already up to date or the check fails. */
    fun checkNow() = check(quiet = false)

    private fun check(quiet: Boolean) {
        if (checking) return
        checking = true
        io.execute {
            val result = runCatching { updater.check() }
            main.post {
                checking = false
                result.onSuccess { update ->
                    if (update != null) available = update
                    else if (!quiet) message = "You have the latest version"
                }.onFailure { if (!quiet) message = "Couldn't check for updates. ${reason(it)}".trim() }
            }
        }
    }

    fun later() {
        available = null
    }

    fun update() {
        val update = available ?: return
        progress = Float.NaN
        io.execute {
            val result = runCatching { updater.download(update) { p -> main.post { progress = p ?: Float.NaN } } }
            main.post {
                progress = null
                available = null
                result.onSuccess { file ->
                    runCatching { updater.install(file) }.onFailure { message = "Couldn't open the installer. ${it.message ?: ""}".trim() }
                }.onFailure { message = "The update couldn't be downloaded. ${reason(it)}".trim() }
            }
        }
    }

    private fun reason(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "Check the phone is online."
        else -> e.message ?: ""
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

/** "Update available" with Update and Later, then a progress bar while it downloads. */
@Composable
fun UpdateDialog(updates: UpdateViewModel) {
    val update = updates.available ?: return
    val progress = updates.progress
    AlertDialog(
        onDismissRequest = { if (progress == null) updates.later() },
        title = { Text("Update available") },
        text = {
            Column {
                Text("Wiser Local ${update.version} is available. You have ${updates.currentVersion}.")
                if (progress != null) {
                    Spacer(Modifier.height(16.dp))
                    if (progress.isNaN()) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { updates.update() }, enabled = progress == null) { Text("Update") }
        },
        dismissButton = {
            TextButton(onClick = { updates.later() }, enabled = progress == null) { Text("Later") }
        },
    )
}
