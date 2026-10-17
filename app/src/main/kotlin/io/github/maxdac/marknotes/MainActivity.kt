package io.github.maxdac.marknotes

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.maxdac.marknotes.data.ThemeMode
import io.github.maxdac.marknotes.ui.MarkNotesTheme
import io.github.maxdac.marknotes.ui.browser.BrowserScreen
import io.github.maxdac.marknotes.ui.conflicts.ConflictsScreen
import io.github.maxdac.marknotes.ui.drives.AddDriveScreen
import io.github.maxdac.marknotes.ui.editor.EditorScreen
import io.github.maxdac.marknotes.ui.login.LoginScreen
import io.github.maxdac.marknotes.ui.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            // Sync once per launch; no periodic polling.
            appContainer.syncManager.syncIfConnected()
        }
        val settings = appContainer.settings
        setContent {
            val mode by settings.themeMode.collectAsStateWithLifecycle()
            val color by settings.themeColor.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MarkNotesTheme(mode = mode, color = color) {
                MarkNotesNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Completes a pending Dropbox sign-in when returning from the browser.
        appContainer.dropbox.onActivityResumed()
    }
}

object Routes {
    const val BROWSER = "browser?path={path}"
    const val EDITOR = "editor?path={path}"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val CONFLICTS = "conflicts"
    const val ADD_DRIVE = "add_drive"

    fun browser(path: String) = "browser?path=${Uri.encode(path)}"
    fun editor(path: String) = "editor?path=${Uri.encode(path)}"
}

@Composable
private fun MarkNotesNavHost() {
    val navController = rememberNavController()
    val pathArg = listOf(
        navArgument("path") {
            type = NavType.StringType
            defaultValue = ""
        }
    )
    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER, arguments = pathArg) { entry ->
            BrowserScreen(
                folder = entry.arguments?.getString("path").orEmpty(),
                onOpenFolder = { navController.navigate(Routes.browser(it)) },
                onJumpToFolder = { path ->
                    val root = navController.graph.startDestinationId
                    if (path.isEmpty()) {
                        navController.popBackStack(root, inclusive = false)
                    } else {
                        navController.navigate(Routes.browser(path)) {
                            popUpTo(root)
                            launchSingleTop = true
                        }
                    }
                },
                onOpenNote = { navController.navigate(Routes.editor(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenConflicts = { navController.navigate(Routes.CONFLICTS) },
                onBack = { navController.navigateUp() }
            )
        }
        composable(Routes.EDITOR, arguments = pathArg) { entry ->
            EditorScreen(
                path = entry.arguments?.getString("path").orEmpty(),
                onBack = { navController.navigateUp() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onAddDrive = { navController.navigate(Routes.ADD_DRIVE) },
                onOpenConflicts = { navController.navigate(Routes.CONFLICTS) },
                onBack = { navController.navigateUp() }
            )
        }
        composable(Routes.ADD_DRIVE) {
            AddDriveScreen(
                onProtonLogin = { navController.navigate(Routes.LOGIN) },
                onDone = { navController.popBackStack(Routes.ADD_DRIVE, inclusive = true) },
                onBack = { navController.navigateUp() }
            )
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = { navController.navigateUp() },
                onBack = { navController.navigateUp() }
            )
        }
        composable(Routes.CONFLICTS) {
            ConflictsScreen(onBack = { navController.navigateUp() })
        }
    }
}
