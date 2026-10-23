package io.github.maxdac.marknotes

import android.content.Intent
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
import io.github.maxdac.marknotes.ui.NoteScreen
import io.github.maxdac.marknotes.ui.browser.BrowserScreen
import io.github.maxdac.marknotes.ui.conflicts.ConflictsScreen
import io.github.maxdac.marknotes.ui.drives.AddDriveScreen
import io.github.maxdac.marknotes.ui.importing.ImportScreen
import io.github.maxdac.marknotes.ui.importing.SharedContent
import io.github.maxdac.marknotes.ui.login.LoginScreen
import io.github.maxdac.marknotes.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    /** The latest content shared with the app; [shareRequests] counts new shares to open the import screen for. */
    private val sharedContent = MutableStateFlow<SharedContent?>(null)
    private val shareRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedContent.value = SharedContent.from(intent)
        if (savedInstanceState == null) {
            // Sync once per launch; no periodic polling.
            appContainer.syncManager.syncIfConnected()
            if (sharedContent.value != null) shareRequests.value++
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
                MarkNotesNavHost(sharedContent, shareRequests, onFinish = ::finish)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        SharedContent.from(intent)?.let {
            setIntent(intent)
            sharedContent.value = it
            shareRequests.value++
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
    const val IMPORT = "import"

    fun browser(path: String) = "browser?path=${Uri.encode(path)}"
    fun editor(path: String) = "editor?path=${Uri.encode(path)}"
}

@Composable
private fun MarkNotesNavHost(
    sharedContent: StateFlow<SharedContent?>,
    shareRequests: StateFlow<Int>,
    onFinish: () -> Unit
) {
    val navController = rememberNavController()
    val pathArg = listOf(
        navArgument("path") {
            type = NavType.StringType
            defaultValue = ""
        }
    )
    val shareRequest by shareRequests.collectAsStateWithLifecycle()
    LaunchedEffect(shareRequest) {
        if (shareRequest > 0) {
            // A new share replaces an import screen that is still open.
            navController.popBackStack(Routes.IMPORT, inclusive = true)
            navController.navigate(Routes.IMPORT)
        }
    }
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
            NoteScreen(
                path = entry.arguments?.getString("path").orEmpty(),
                onBack = { navController.navigateUp() },
                onOpenNote = { navController.navigate(Routes.editor(it)) }
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
        composable(Routes.IMPORT) {
            val content by sharedContent.collectAsStateWithLifecycle()
            val current = content
            if (current == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                ImportScreen(
                    content = current,
                    onSaved = { path ->
                        navController.navigate(Routes.editor(path)) {
                            popUpTo(Routes.IMPORT) { inclusive = true }
                        }
                    },
                    // Return to the app the content was shared from.
                    onCancel = onFinish
                )
            }
        }
    }
}
