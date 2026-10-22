package io.github.maxdac.marknotes.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.maxdac.marknotes.AppContainer
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.core.notes.Attachments
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.data.ThemeColor
import io.github.maxdac.marknotes.data.ThemeMode
import io.github.maxdac.marknotes.sync.DriveStatus
import io.github.maxdac.marknotes.sync.SyncManager
import io.github.maxdac.marknotes.ui.MarkNotesLogo
import io.github.maxdac.marknotes.ui.login.THIRD_PARTY_DISCLAIMER
import io.github.maxdac.marknotes.ui.swatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val NO_DRIVES_TEXT = "No drives connected. Notes are only stored on this device."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onAddDrive: () -> Unit, onOpenConflicts: () -> Unit, onBack: () -> Unit) {
    val container = LocalContext.current.appContainer
    val syncManager = container.syncManager
    val state by syncManager.state.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<DriveStatus?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppearanceSection(container.settings)

            HorizontalDivider()
            Text("Drives", style = MaterialTheme.typography.titleMedium)
            if (state.drives.isEmpty()) Text(NO_DRIVES_TEXT)
            state.drives.forEach { drive ->
                DriveCard(drive, enabled = !state.running, onRemove = { removing = drive })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAddDrive, enabled = !state.running) { Text("Add drive") }
                Button(onClick = syncManager::syncNow, enabled = state.hasDrives && !state.running) {
                    Text("Sync now")
                }
            }
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (state.conflicts.isNotEmpty()) {
                Button(onClick = onOpenConflicts) { Text("Resolve ${state.conflicts.size} conflict(s)") }
            }

            HorizontalDivider()
            ImagesSection(container, enabled = !state.running)

            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MarkNotesLogo(size = 48.dp)
                Text("MarkNotes", style = MaterialTheme.typography.titleLarge)
            }
            Text(THIRD_PARTY_DISCLAIMER, style = MaterialTheme.typography.bodyMedium)
            Text(
                "MarkNotes is a personal, non-commercial project. Sync happens on app launch and when you tap " +
                    "\"Sync now\"; there is no background polling.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    removing?.let { drive -> RemoveDriveDialog(drive, syncManager, onDismiss = { removing = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(settings: AppSettings) {
    val mode by settings.themeMode.collectAsStateWithLifecycle()
    val color by settings.themeColor.collectAsStateWithLifecycle()
    Text("Appearance", style = MaterialTheme.typography.titleMedium)
    Text("Theme", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { settings.setThemeMode(option) },
                label = { Text(option.label) }
            )
        }
    }
    Text("Colour: ${color.label}", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        settings.availableColors.forEach { option ->
            ColorSwatch(option, selected = option == color) { settings.setThemeColor(option) }
        }
    }
}

@Composable
private fun ColorSwatch(color: ThemeColor, selected: Boolean, onClick: () -> Unit) {
    val fill = if (color == ThemeColor.DYNAMIC) {
        Modifier.background(
            Brush.sweepGradient(ThemeColor.entries.drop(1).map { it.swatch() } + ThemeColor.BLUE.swatch()),
            CircleShape
        )
    } else {
        Modifier.background(color.swatch(), CircleShape)
    }
    Box(
        Modifier
            .size(44.dp)
            .then(fill)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = color.label
                this.selected = selected
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            selected -> Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
            color == ThemeColor.DYNAMIC -> Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
private fun DriveCard(drive: DriveStatus, enabled: Boolean, onRemove: () -> Unit) {
    val account = drive.account
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(account.provider.displayName, style = MaterialTheme.typography.titleSmall)
            Text(account.label, style = MaterialTheme.typography.bodyMedium)
            Text("Folder: /${account.syncFolder}", style = MaterialTheme.typography.bodySmall)
            if (drive.lastSyncAt > 0) {
                Text(
                    "Last sync: " + DateUtils.getRelativeTimeSpanString(drive.lastSyncAt),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            drive.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            drive.unavailableReason?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun ImagesSection(container: AppContainer, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Text("Images", style = MaterialTheme.typography.titleMedium)
    Text(
        "Images added to notes are kept in the attachments folder and synced to every drive.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedButton(onClick = { confirming = true }, enabled = enabled && !cleaning) { Text("Clean unused images") }
    if (cleaning) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Clean unused images?") },
            text = {
                Text(
                    "Images not referenced by any note will be deleted from this device, and from your drives " +
                        "on the next sync."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    cleaning = true
                    scope.launch {
                        message = try {
                            val deleted = withContext(Dispatchers.IO) { Attachments.deleteUnused(container.notes) }
                            if (deleted.isNotEmpty()) container.notifyLocalChanged()
                            when (deleted.size) {
                                0 -> "No unused images found."
                                1 -> "Deleted 1 unused image."
                                else -> "Deleted ${deleted.size} unused images."
                            }
                        } catch (e: Exception) {
                            "Could not clean images: ${e.message}"
                        }
                        cleaning = false
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RemoveDriveDialog(drive: DriveStatus, syncManager: SyncManager, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disconnect ${drive.account.provider.displayName}?") },
        text = {
            Text(
                "${drive.account.label} will no longer be synced and you will be signed out. " +
                    "Notes stay on this device and on the drive."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                syncManager.removeDrive(drive.account)
            }) { Text("Disconnect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
