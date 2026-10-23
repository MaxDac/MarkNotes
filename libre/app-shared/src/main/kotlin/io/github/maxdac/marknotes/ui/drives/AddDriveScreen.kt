package io.github.maxdac.marknotes.ui.drives

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.core.notes.NotePaths
import io.github.maxdac.marknotes.core.sync.DriveProvider

const val ADD_DRIVE_TITLE = "Add drive"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDriveScreen(onProviderSignIn: (DriveProvider) -> Unit, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val container = context.appContainer
    val vm: AddDriveViewModel = viewModel { AddDriveViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.onShown(activity) }
    }
    LaunchedEffect(state.signInScreenFor) {
        state.signInScreenFor?.let { provider ->
            vm.onSignInScreenShown()
            onProviderSignIn(provider)
        }
    }
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val goBack = { if (!vm.back()) onBack() }
    BackHandler(onBack = goBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ADD_DRIVE_TITLE) },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            when (val step = state.step) {
                AddDriveStep.ChooseProvider -> ProviderList(vm.providers()) { vm.choose(it, activity) }

                is AddDriveStep.SigningIn -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("Signing in to ${step.provider.displayName}…", Modifier.padding(16.dp))
                    }
                }

                is AddDriveStep.ChooseFolder -> FolderStep(step, state, vm)
            }
        }
    }
}

@Composable
private fun ProviderList(options: List<ProviderOption>, onChoose: (DriveProvider) -> Unit) {
    Column {
        Text(
            "Choose where to sync your notes. You can connect several drives: each one keeps a full copy.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp)
        )
        options.forEach { option ->
            val enabled = option.unavailableReason == null
            ListItem(
                modifier = Modifier.clickable(enabled = enabled) { onChoose(option.provider) },
                leadingContent = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                headlineContent = {
                    Text(
                        option.provider.displayName,
                        color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.outline
                    )
                },
                supportingContent = option.unavailableReason?.let { reason -> { Text(reason) } }
            )
        }
    }
}

@Composable
private fun FolderStep(step: AddDriveStep.ChooseFolder, state: AddDriveState, vm: AddDriveViewModel) {
    val name = state.rootFolderName
    val nameValid = AddDriveViewModel.isValidFolderName(name)
    Column(Modifier.fillMaxSize()) {
        Text(
            "Signed in to ${step.account.provider.displayName} as ${step.account.label}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (step.browsable) {
            Text(
                "Folder: /" + step.path,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val folders = step.folders
                when {
                    folders == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    folders.isEmpty() -> Text("No sub-folders", Modifier.align(Alignment.Center))

                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(folders, key = { it.path }) { folder ->
                            ListItem(
                                modifier = Modifier.clickable { vm.open(folder.path) },
                                leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                headlineContent = { Text(folder.name) }
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step.browsable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Create a folder for the notes here", Modifier.weight(1f))
                    Switch(checked = state.createRootFolder, onCheckedChange = vm::setCreateRootFolder)
                }
            }
            if (!step.browsable || state.createRootFolder) {
                OutlinedTextField(
                    value = name,
                    onValueChange = vm::setRootFolderName,
                    label = { Text(if (step.browsable) "Folder name" else "Folder in My files") },
                    isError = !nameValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            val target = when {
                !step.browsable -> name.trim()
                state.createRootFolder -> NotePaths.join(step.path, name.trim())
                else -> step.path
            }
            Text("Notes will be synced to /$target", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = vm::connect,
                enabled = (step.browsable && !state.createRootFolder) || nameValid,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Connect") }
        }
    }
}
