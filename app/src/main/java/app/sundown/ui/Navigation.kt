package app.sundown.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.sundown.ui.components.NBottomBar
import app.sundown.ui.components.NavDestination
import app.sundown.ui.screens.AppPickerScreen
import app.sundown.ui.screens.EditorScreen
import app.sundown.ui.screens.LogScreen
import app.sundown.ui.screens.ScreenPickerScreen
import app.sundown.ui.screens.SetupScreen
import app.sundown.ui.screens.TodayScreen
import app.sundown.ui.theme.NIcons

object Routes {
    const val TODAY = "today"
    const val LOG = "log"
    const val SETUP = "setup"
    const val EDIT = "edit"
    const val PICK_APPS = "pick_apps"
    const val PICK_SCREENS = "pick_screens"
}

private val tabs = listOf(
    NavDestination("Today", NIcons.Sundown, Routes.TODAY),
    NavDestination("Log", NIcons.Logs, Routes.LOG),
    NavDestination("Setup", NIcons.Settings, Routes.SETUP),
)

@Composable
fun SundownNav(vm: MainViewModel, requestedTab: String?, onTabConsumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            nav.goToTab(requestedTab)
            onTabConsumed()
        }
    }

    val bottomBar: @Composable () -> Unit = {
        NBottomBar(tabs, route) { nav.goToTab(it.route) }
    }

    NavHost(nav, startDestination = Routes.TODAY) {
        composable(Routes.TODAY) {
            TodayScreen(vm, bottomBar, onEdit = { nav.navigate(Routes.EDIT) }, onSetup = { nav.goToTab(Routes.SETUP) })
        }
        composable(Routes.LOG) { LogScreen(vm, bottomBar) }
        composable(Routes.SETUP) { SetupScreen(bottomBar) }
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

private fun NavHostController.goToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
