package io.github.maxdac.marknotes

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.maxdac.marknotes.ui.MarkNotesTheme
import io.github.maxdac.marknotes.ui.browser.BrowserScreen
import io.github.maxdac.marknotes.ui.conflicts.ConflictsScreen
import io.github.maxdac.marknotes.ui.editor.EditorScreen
import io.github.maxdac.marknotes.ui.login.LoginScreen
import io.github.maxdac.marknotes.ui.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            // Sync once per launch; no periodic polling.
            appContainer.syncManager.syncIfLoggedIn()
        }
        setContent {
            MarkNotesTheme {
                MarkNotesNavHost()
            }
        }
    }
}

object Routes {
    const val BROWSER = "browser?path={path}"
    const val EDITOR = "editor?path={path}"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val CONFLICTS = "conflicts"

    fun browser(path: String) = "browser?path=${Uri.encode(path)}"
    fun editor(path: String) = "editor?path=${Uri.encode(path)}"
}

@androidx.compose.runtime.Composable
private fun MarkNotesNavHost() {
    val navController = rememberNavController()
    val pathArg = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })
    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER, arguments = pathArg) { entry ->
            BrowserScreen(
                folder = entry.arguments?.getString("path").orEmpty(),
                onOpenFolder = { navController.navigate(Routes.browser(it)) },
                onOpenNote = { navController.navigate(Routes.editor(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenConflicts = { navController.navigate(Routes.CONFLICTS) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.EDITOR, arguments = pathArg) { entry ->
            EditorScreen(
                path = entry.arguments?.getString("path").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onLogin = { navController.navigate(Routes.LOGIN) },
                onOpenConflicts = { navController.navigate(Routes.CONFLICTS) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CONFLICTS) {
            ConflictsScreen(onBack = { navController.popBackStack() })
        }
    }
}
