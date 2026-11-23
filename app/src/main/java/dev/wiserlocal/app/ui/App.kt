package dev.wiserlocal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import dev.wiserlocal.app.R

enum class Tab(val label: String) { HOME("Home"), AUTOMATIONS("Automations"), CONTROL("Control"), INSIGHTS("Insights") }

/** Screens opened on top of the tabs. */
sealed interface Screen {
    data object Settings : Screen
    data object HubConnection : Screen
    data object Rooms : Screen
    data object Devices : Screen
    data class RoomDetails(val roomId: Int) : Screen
    data class DeviceDetails(val deviceId: Int) : Screen
    data object Away : Screen
    data object SmartModes : Screen
    data object SystemSettings : Screen
    data object HomeLayout : Screen
    data object Theme : Screen
    data class MomentEditor(val momentId: Int?) : Screen
    data class AutomationEditor(val automationId: Int?) : Screen
    data class ScheduleEditor(val id: Int, val heating: Boolean, val title: String, val level: Boolean = false) : Screen
    data object Equipment : Screen
    data class EquipmentItem(val domain: String, val id: Int) : Screen
}

class Navigator {
    var tab by mutableStateOf(Tab.HOME)

    /** Selected tab inside Automations: 0 Moments, 1 Automations, 2 Schedules. */
    var automationsTab by mutableIntStateOf(0)
    val stack = mutableStateListOf<Screen>()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("No Navigator") }

@Composable
fun WiserApp(vm: HubViewModel) {
    val nav = remember { Navigator() }
    val dialogs = remember { DialogHost() }
    val snackbar = remember { SnackbarHostState() }
    val updates: UpdateViewModel = viewModel()

    // Clear each message only after its snackbar has gone: clearing it first changes the
    // effect's key, which cancels the effect and the snackbar with it.
    LaunchedEffect(vm.message) {
        val text = vm.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.message = null
    }
    LaunchedEffect(updates.message) {
        val text = updates.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        updates.message = null
    }
    LaunchedEffect(Unit) {
        if (!vm.configured) nav.push(Screen.HubConnection)
        updates.checkDaily()
    }

    CompositionLocalProvider(LocalDialogs provides dialogs, LocalNav provides nav) {
        BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }
        BackHandler(enabled = nav.stack.isEmpty() && nav.tab != Tab.HOME) { nav.tab = Tab.HOME }

        when (val screen = nav.stack.lastOrNull()) {
            null -> MainTabs(vm, nav, snackbar)
            Screen.Settings -> SettingsScreen(vm, snackbar)
            Screen.HubConnection -> HubConnectionScreen(vm, snackbar)
            Screen.Rooms -> RoomsScreen(vm, snackbar)
            Screen.Devices -> DevicesScreen(vm, snackbar)
            is Screen.RoomDetails -> RoomDetailsScreen(vm, snackbar, screen.roomId)
            is Screen.DeviceDetails -> DeviceDetailsScreen(vm, snackbar, screen.deviceId)
            Screen.Away -> AwayScreen(vm, snackbar)
            Screen.SmartModes -> SmartModesScreen(vm, snackbar)
            Screen.SystemSettings -> SystemSettingsScreen(vm, snackbar)
            Screen.HomeLayout -> HomeLayoutScreen(vm, snackbar)
            Screen.Theme -> ThemeScreen(vm, snackbar)
            is Screen.MomentEditor -> MomentEditorScreen(vm, snackbar, screen.momentId)
            is Screen.AutomationEditor -> AutomationEditorScreen(vm, snackbar, screen.automationId)
            is Screen.ScheduleEditor -> ScheduleEditorScreen(vm, snackbar, screen)
            Screen.Equipment -> EquipmentScreen(vm, snackbar)
            is Screen.EquipmentItem -> EquipmentItemScreen(vm, snackbar, screen.domain, screen.id)
        }

        dialogs.content?.invoke()
        UpdateDialog(updates)

        vm.identifying?.let { device ->
            AlertDialog(
                onDismissRequest = { vm.stopIdentify() },
                title = { Text(deviceName(device)) },
                text = { Text("The device is flashing so you can find it. Tap Done to stop.") },
                confirmButton = { TextButton(onClick = { vm.stopIdentify() }) { Text("Done") } },
            )
        }
    }
}

@Composable
private fun MainTabs(vm: HubViewModel, nav: Navigator, snackbar: SnackbarHostState) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val icon = when (tab) {
                        Tab.HOME -> rememberVectorPainter(Icons.Default.Home)
                        Tab.AUTOMATIONS -> painterResource(R.drawable.ic_automation)
                        Tab.CONTROL -> painterResource(R.drawable.ic_tune)
                        Tab.INSIGHTS -> painterResource(R.drawable.ic_insights)
                    }
                    NavigationBarItem(
                        selected = nav.tab == tab,
                        onClick = { nav.tab = tab },
                        icon = { Icon(icon, contentDescription = null) },
                        // One line even with large text; "Automations" otherwise breaks in two.
                        label = { Text(tab.label, maxLines = 1, softWrap = false) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
        when (nav.tab) {
            Tab.HOME -> HomeTab(vm, modifier)
            Tab.AUTOMATIONS -> AutomationsTab(vm, modifier)
            Tab.CONTROL -> ControlTab(vm, modifier)
            Tab.INSIGHTS -> InsightsTab(vm, modifier)
        }
    }
}
