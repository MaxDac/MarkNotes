package io.github.maxdac.marknotes.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.ui.login.THIRD_PARTY_DISCLAIMER

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onLogin: () -> Unit, onOpenConflicts: () -> Unit, onBack: () -> Unit) {
    val container = LocalContext.current.appContainer
    val syncManager = container.syncManager
    val state by syncManager.state.collectAsStateWithLifecycle()
    var folder by rememberSaveable { mutableStateOf(container.settings.remoteFolder) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Proton Drive sync", style = MaterialTheme.typography.titleMedium)
            val session = state.session
            if (session == null) {
                Text("Not logged in. Notes are only stored on this device.")
                Button(onClick = onLogin) { Text("Log in") }
            } else {
                Text("Logged in as ${session.username}")
                OutlinedButton(onClick = syncManager::logout, enabled = !state.running) { Text("Log out") }
            }

            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it },
                label = { Text("Drive folder (in My files)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { syncManager.changeRemoteFolder(folder) },
                    enabled = folder.isNotBlank() && folder.trim() != container.settings.remoteFolder && !state.running,
                ) { Text("Change folder") }
                Button(onClick = syncManager::syncNow, enabled = state.loggedIn && !state.running) { Text("Sync now") }
            }
            Text(
                "Changing the folder resets sync history; the next sync merges both sides without deleting anything.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.lastSyncAt > 0) {
                Text(
                    "Last sync: " + DateUtils.getRelativeTimeSpanString(state.lastSyncAt),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (state.conflicts.isNotEmpty()) {
                Button(onClick = onOpenConflicts) { Text("Resolve ${state.conflicts.size} conflict(s)") }
            }
            if (!syncManager.sdkAvailable) {
                Text(
                    "This build does not include the Proton Drive SDK, so sync is unavailable. " +
                        "Notes still work fully offline.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(THIRD_PARTY_DISCLAIMER, style = MaterialTheme.typography.bodyMedium)
            Text(
                "MarkNotes is a personal, non-commercial project. Sync happens on app launch and when you tap " +
                    "\"Sync now\"; there is no background polling.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
