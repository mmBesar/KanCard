package com.mmbesar.kancard.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmbesar.kancard.R
import com.mmbesar.kancard.network.ServerAddress

/**
 * First start: connect to Kanboard (http:// and self-signed https:// are fine)
 * or skip the server and use KanCard on this phone only.
 */
@Composable
fun SetupScreen(viewModel: BoardViewModel) {
    val state by viewModel.connectState.collectAsStateWithLifecycle()
    val editing by viewModel.editingConnection.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()

    // When editing, the saved address and user are filled in (the password is never shown).
    val saved = remember(editing) { if (editing) viewModel.connectionSummary() else null }
    var address by rememberSaveable { mutableStateOf(saved?.address.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(saved?.username.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var showHttpWarning by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    val working = state is ConnectState.Working
    val isHttp = ServerAddress.parse(address)?.isCleartext == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The mark: the app icon on its own colour.
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(26.dp)),
        ) {
            Image(painterResource(R.drawable.ic_launcher_bg), contentDescription = null, modifier = Modifier.fillMaxSize())
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        if (editing) {
            Text(stringResource(R.string.setup_edit_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.setup_edit_warning), color = MaterialTheme.colorScheme.error)
            if (pendingCount > 0) {
                Text(stringResource(R.string.switch_pending_warning, pendingCount), color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineLarge)
            Text(
                stringResource(R.string.setup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        SetupCard(title = stringResource(R.string.setup_section_server)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.field_address)) },
                placeholder = { Text(stringResource(R.string.field_address_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            if (isHttp) {
                Text(
                    stringResource(R.string.setup_http_notice),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SetupCard(title = stringResource(R.string.setup_section_account)) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.field_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.field_token)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(stringResource(if (showPassword) R.string.setup_hide else R.string.setup_show))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.setup_login_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = {
                if (isHttp) showHttpWarning = true else viewModel.connect(address, username, token)
            },
            enabled = !working && address.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.action_connect)) }

        if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        (state as? ConnectState.Error)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error)
        }

        if (editing) {
            OutlinedButton(
                onClick = { viewModel.cancelEditConnection() },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_cancel)) }
        } else {
            SetupCard(title = stringResource(R.string.setup_local_title)) {
                Text(stringResource(R.string.setup_local_intro), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { viewModel.startLocal() },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_use_locally)) }
            }
        }
    }

    if (showHttpWarning) {
        HttpWarningDialog(
            onContinue = {
                showHttpWarning = false
                viewModel.connect(address, username, token)
            },
            onDismiss = { showHttpWarning = false },
        )
    }

    (state as? ConnectState.ConfirmCertificate)?.let { confirm ->
        CertificateDialog(
            state = confirm,
            onTrust = { viewModel.confirmCertificate(confirm) },
            onDismiss = { viewModel.dismissConnectMessage() },
        )
    }
}

/** A rounded panel with a small title, used to group the login fields. */
@Composable
private fun SetupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
