package io.github.maxdac.marknotes.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.maxdac.marknotes.sync.ProtonConnector

const val THIRD_PARTY_DISCLAIMER = "This is a third-party application not officially supported by Proton."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(proton: ProtonConnector, onLoggedIn: () -> Unit, onBack: () -> Unit) {
    val vm: LoginViewModel = viewModel { LoginViewModel(proton) }
    val state by vm.state.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.done) {
        if (state.done) onLoggedIn()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Proton Drive login") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    THIRD_PARTY_DISCLAIMER,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (!state.needsTwoFactor) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username or email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { vm.login(username, password) },
                    enabled = !state.busy && username.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Log in") }
            } else {
                Text("Enter the 6-digit code from your authenticator app.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Two-factor code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { vm.submitCode(code) },
                    enabled = !state.busy && code.length == 6,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Verify") }
            }

            if (state.busy) CircularProgressIndicator()
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Your password is only used to sign in and is never stored. " +
                    "CAPTCHA / human verification is not supported in this version.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
