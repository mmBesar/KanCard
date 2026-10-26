@file:OptIn(ExperimentalMaterial3Api::class)

package com.mmbesar.kancard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.mmbesar.kancard.R
import com.mmbesar.kancard.data.DueDates
import com.mmbesar.kancard.data.TaskEntity
import com.mmbesar.kancard.security.Mode
import java.text.DateFormat

// Small reusable pop-up windows used by the screens.

/** "Do you trust this certificate?" - shown for servers with a self-signed certificate. */
@Composable
fun CertificateDialog(
    state: ConnectState.ConfirmCertificate,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
    changed: Boolean = false,
) {
    val info = state.info
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cert_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (changed) {
                    Text(
                        stringResource(R.string.cert_changed_warning),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(stringResource(R.string.cert_intro))
                if (info.selfSigned) {
                    Text(
                        stringResource(R.string.cert_self_signed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(stringResource(R.string.cert_subject, info.subject))
                Text(stringResource(R.string.cert_issuer, info.issuer))
                Text(
                    stringResource(
                        R.string.cert_valid,
                        dates.format(info.notBefore),
                        dates.format(info.notAfter),
                    ),
                )
                Text(stringResource(R.string.cert_fingerprint), style = MaterialTheme.typography.labelLarge)
                Text(info.sha256, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.cert_advice))
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text(stringResource(R.string.cert_trust)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Warning before connecting over plain http://. */
@Composable
fun HttpWarningDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.http_title)) },
        text = { Text(stringResource(R.string.http_message)) },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.http_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Create or edit a task: title, description, due date, colour and priority. */
@Composable
fun TaskDialog(
    initial: TaskEntity?,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, dueDate: String?, colorId: String, priority: Int) -> Unit,
    onClose: (() -> Unit)?,
) {
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(initial?.description.orEmpty()) }
    var dueDate by rememberSaveable { mutableStateOf(initial?.dueDate) }
    var colorId by rememberSaveable { mutableStateOf(initial?.colorId.orEmpty()) }
    var priority by rememberSaveable { mutableStateOf(initial?.priority ?: 0) }
    var showPicker by remember { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    // Each field follows the direction of what is typed in it (Arabic right to left, English left to right).
    val fieldStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.task_new else R.string.task_edit))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.field_title)) },
                    textStyle = fieldStyle,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (preview) {
                    // The description as it will look (Markdown rendered).
                    Text(stringResource(R.string.field_description), style = MaterialTheme.typography.labelLarge)
                    if (description.isBlank()) {
                        Text(stringResource(R.string.preview_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        MarkdownText(description, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(R.string.field_description)) },
                        textStyle = fieldStyle,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = { preview = !preview }) {
                    Text(stringResource(if (preview) R.string.action_edit_text else R.string.action_preview))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dueDate?.let { stringResource(R.string.due_on, it) }
                            ?: stringResource(R.string.no_due_date),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showPicker = true }) { Text(stringResource(R.string.action_pick_date)) }
                    if (dueDate != null) {
                        TextButton(onClick = { dueDate = null }) { Text(stringResource(R.string.action_clear)) }
                    }
                }

                // Colour: a row of circles; the first one means "default".
                Text(stringResource(R.string.field_color), style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColorDot(color = null, selected = colorId.isEmpty(), label = stringResource(R.string.color_default)) {
                        colorId = ""
                    }
                    kanboardColors.forEach { (id, color) ->
                        ColorDot(color = color, selected = colorId == id, label = id.replace('_', ' ')) {
                            colorId = id
                        }
                    }
                }

                // Priority: none / low / medium / high.
                Text(stringResource(R.string.field_priority), style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val labels = listOf(
                        R.string.priority_none, R.string.priority_low,
                        R.string.priority_medium, R.string.priority_high,
                    )
                    labels.forEachIndexed { level, label ->
                        FilterChip(
                            selected = priority == level,
                            onClick = { priority = level },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }

                if (onClose != null) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.task_close)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), description, dueDate, colorId, priority) },
                enabled = title.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = DueDates.toPickerMillis(dueDate))
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { dueDate = DueDates.fromPickerMillis(it) }
                    showPicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** One colour circle. [color] = null draws the "default" circle (empty with a dash). */
@Composable
private fun ColorDot(color: androidx.compose.ui.graphics.Color?, selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color ?: androidx.compose.ui.graphics.Color.Transparent)
            .border(
                BorderStroke(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                ),
                CircleShape,
            )
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One text field plus OK/Cancel. Used for "new project". */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "Are you sure?" with a custom message. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Shows how this phone is connected (never the token). */
@Composable
fun ConnectionDialog(summary: ConnectionSummary?, mode: Mode, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (mode == Mode.LOCAL || summary == null) {
                    Text(stringResource(R.string.about_local))
                } else {
                    Text(stringResource(R.string.about_server, summary.address))
                    Text(stringResource(R.string.about_user, summary.username))
                    when (summary.security) {
                        Security.HTTPS -> Text(stringResource(R.string.about_security_https))
                        Security.HTTP -> Text(
                            stringResource(R.string.about_security_http),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Security.HTTPS_PINNED -> {
                            Text(stringResource(R.string.about_security_pinned))
                            Text(
                                summary.pinnedSha256.orEmpty(),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.about_independent),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

/** Asks for a new personal access token (when the server stopped accepting the saved one). */
@Composable
fun TokenDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    // Not saved across rotation on purpose: a token should not sit in saved UI state.
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.token_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.token_message))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.field_token)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(token.trim()) }, enabled = token.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
