package app.sundown.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.sundown.ui.screens.AppPickerScreen
import app.sundown.ui.screens.EditorScreen
import app.sundown.ui.screens.HomeScreen
import app.sundown.ui.screens.ScreenPickerScreen

object Routes {
    const val HOME = "home"
    // Tabs inside HomeScreen (also the values of MainActivity.EXTRA_TAB).
    const val SCHEDULE = "schedule"
    const val LOG = "log"
    const val EDIT = "edit"
    const val PICK_APPS = "pick_apps"
    const val PICK_SCREENS = "pick_screens"
}

@Composable
fun SundownNav(vm: MainViewModel, requestedTab: String?, onTabConsumed: () -> Unit) {
    val nav = rememberNavController()

    // A tab request (e.g. a notification) also brings Home back to the front.
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) nav.popBackStack(Routes.HOME, inclusive = false)
    }

    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(vm, requestedTab, onTabConsumed, onEdit = { nav.navigate(Routes.EDIT) })
        }
        composable(Routes.EDIT) {
            EditorScreen(
                vm,
                onBack = { nav.popBackStack() },
                onPickApps = { nav.navigate(Routes.PICK_APPS) },
                onPickScreens = { nav.navigate(Routes.PICK_SCREENS) },
            )
        }
        composable(Routes.PICK_APPS) { AppPickerScreen(vm, onBack = { nav.popBackStack() }) }
        composable(Routes.PICK_SCREENS) { ScreenPickerScreen(vm, onBack = { nav.popBackStack() }) }
    }
}
