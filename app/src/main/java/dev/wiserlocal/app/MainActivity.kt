package dev.wiserlocal.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import dev.wiserlocal.app.ui.HubViewModel
import dev.wiserlocal.app.ui.WiserApp
import dev.wiserlocal.app.ui.WiserTheme
import dev.wiserlocal.app.ui.isDarkTheme

/** The whole app is one Compose screen; see [WiserApp]. */
class MainActivity : ComponentActivity() {

    private val vm: HubViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // The app's own theme setting decides the system bar icon colours, not the phone's.
            val dark = isDarkTheme(vm.theme)
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            WiserTheme(vm.theme) {
                WiserApp(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}
