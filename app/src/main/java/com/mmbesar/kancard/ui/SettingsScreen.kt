@file:OptIn(ExperimentalMaterial3Api::class)

package com.mmbesar.kancard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmbesar.kancard.R
import com.mmbesar.kancard.security.Mode
import com.mmbesar.kancard.widget.BoardWidget

/** Settings: account (login, log out), appearance (applies instantly) and about. */
@Composable
fun SettingsScreen(
    themePrefs: ThemePrefs,
    viewPrefs: ViewPrefs,
    viewModel: BoardViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val settings by themePrefs.settings.collectAsStateWithLifecycle()
    val rememberLast by viewPrefs.rememberLast.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val summary = remember { viewModel.connectionSummary() }
    var showToken by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    var showCustom by remember { mutableStateOf(false) }

    // Home-screen widgets are redrawn in the new colours.
    LaunchedEffect(settings) {
        try {
            BoardWidget().updateAll(context)
        } catch (e: Exception) {
            // A widget that cannot redraw must never break the settings.
        }
    }

    // Titles and descriptions of the theme choices.
    val themes = buildList {
        add(Triple(ThemeChoice.AUTO, R.string.theme_auto, R.string.theme_auto_desc))
        add(Triple(ThemeChoice.LATTE, R.string.theme_latte, R.string.theme_latte_desc))
        add(Triple(ThemeChoice.FRAPPE, R.string.theme_frappe, R.string.theme_frappe_desc))
        add(Triple(ThemeChoice.MACCHIATO, R.string.theme_macchiato, R.string.theme_macchiato_desc))
        add(Triple(ThemeChoice.MOCHA, R.string.theme_mocha, R.string.theme_mocha_desc))
        if (systemColorsAvailable()) {
            add(Triple(ThemeChoice.SYSTEM, R.string.theme_system, R.string.theme_system_desc))
        }
    }

    // The accent circles show the colours of the flavour that is (or would be) on screen.
    val shownFlavor = flavorFor(settings.theme, systemDark)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- Account ----
            SectionTitle(R.string.settings_account)
            if (mode == Mode.LOCAL || summary == null) {
                Text(stringResource(R.string.about_local))
                OutlinedButton(onClick = { showLogout = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_use_server))
                }
            } else {
                Text(stringResource(R.string.about_server, summary.address))
                Text(stringResource(R.string.about_user, summary.username))
                when (summary.security) {
                    Security.HTTPS -> Text(stringResource(R.string.about_security_https), style = MaterialTheme.typography.bodySmall)
                    Security.HTTPS_PINNED -> Text(stringResource(R.string.about_security_pinned) + "\n" + summary.pinnedSha256.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Security.HTTP -> Text(
                        stringResource(R.string.about_security_http),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (pendingCount > 0) {
                    Text(stringResource(R.string.banner_pending, pendingCount), color = MaterialTheme.colorScheme.primary)
                }
                OutlinedButton(onClick = { viewModel.syncNow() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_sync_now))
                }
                OutlinedButton(onClick = { showToken = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_change_secret))
                }
                OutlinedButton(onClick = { viewModel.startEditConnection() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_edit_connection))
                }
                OutlinedButton(onClick = { showLogout = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_log_out))
                }
            }

            // ---- Appearance ----
            SectionTitle(R.string.settings_appearance)
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium)
            themes.forEach { (choice, title, description) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { themePrefs.update { it.copy(theme = choice) } }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.theme == choice, onClick = null)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // A small preview: the theme's background and its accent.
                    if (choice != ThemeChoice.SYSTEM) {
                        val flavor = flavorFor(choice, systemDark)
                        Swatch(flavor.base, 22, outlined = true)
                        Box(Modifier.size(6.dp))
                        Swatch(flavor.accents[CatAccent.MAUVE.ordinal], 22, outlined = false)
                    }
                }
            }

            // ---- Accent ----
            Text(
                stringResource(R.string.settings_accent),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                stringResource(R.string.settings_accent_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = settings.accent.isEmpty(),
                    onClick = { themePrefs.update { it.copy(accent = "") } },
                    label = { Text(stringResource(R.string.accent_default)) },
                )
                CatAccent.values().forEach { accent ->
                    val color = shownFlavor.accents[accent.ordinal]
                    val label = accent.name.lowercase().replaceFirstChar { it.uppercase() }
                    AccentCircle(
                        color = color,
                        selected = settings.accent == accent.name,
                        label = label,
                        onClick = { themePrefs.update { it.copy(accent = accent.name) } },
                    )
                }
                AccentCircle(
                    color = Color(settings.customAccent),
                    selected = settings.accent == "CUSTOM",
                    label = stringResource(R.string.accent_custom),
                    onClick = { showCustom = true },
                    mark = "+",
                )
            }

            // ---- AMOLED ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_amoled), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_amoled_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.amoled,
                    onCheckedChange = { checked -> themePrefs.update { it.copy(amoled = checked) } },
                )
            }

            // ---- Card details ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_show_descriptions), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_show_descriptions_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.showDescriptions,
                    onCheckedChange = { checked -> themePrefs.update { it.copy(showDescriptions = checked) } },
                )
            }

            // ---- Projects ----
            SectionTitle(R.string.settings_projects)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_remember_view), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_remember_view_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = rememberLast, onCheckedChange = { viewPrefs.setRememberLast(it) })
            }

            // ---- About ----
            SectionTitle(R.string.settings_about)
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                    .getOrNull().orEmpty()
            }
            Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.about_independent), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showToken) {
        TokenDialog(
            onDismiss = { showToken = false },
            onConfirm = { token ->
                viewModel.updateToken(token)
                showToken = false
            },
        )
    }

    if (showLogout) {
        val base = stringResource(
            if (mode == Mode.LOCAL) R.string.switch_message_local else R.string.switch_message_server,
        )
        // Logging out erases the copy on the phone, so unsent changes would be lost: say so.
        val message = if (pendingCount > 0) {
            base + "\n\n" + stringResource(R.string.switch_pending_warning, pendingCount)
        } else {
            base
        }
        ConfirmDialog(
            title = stringResource(R.string.logout_title),
            message = message,
            confirmLabel = stringResource(R.string.logout_confirm),
            onDismiss = { showLogout = false },
            onConfirm = {
                showLogout = false
                viewModel.resetMode()
            },
        )
    }

    if (showCustom) {
        CustomAccentDialog(
            initial = settings.customAccent,
            onDismiss = { showCustom = false },
            onConfirm = { argb ->
                themePrefs.update { it.copy(accent = "CUSTOM", customAccent = argb) }
                showCustom = false
            },
        )
    }
}

/** Heading that starts a settings section. */
@Composable
private fun SectionTitle(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp),
    )
}

/** A plain coloured circle. */
@Composable
private fun Swatch(color: Color, sizeDp: Int, outlined: Boolean) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (outlined) 1f else 0f)),
                CircleShape,
            ),
    )
}

/** A tappable accent colour. The chosen one shows a tick and a thick ring. */
@Composable
private fun AccentCircle(
    color: Color,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    mark: String = "✓",
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
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
        if (selected || mark != "✓") Text(mark, color = onColorFor(color))
    }
}

private fun hexOf(color: Color): String = String.format("%06X", color.toArgb() and 0xFFFFFF)

/** Pick any colour with three sliders or by typing its hex code. */
@Composable
private fun CustomAccentDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableStateOf(start[0]) }
    var saturation by remember { mutableStateOf(start[1]) }
    var value by remember { mutableStateOf(start[2]) }
    val color = Color.hsv(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    var hex by remember { mutableStateOf(hexOf(color)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(color),
                )
                Text(stringResource(R.string.custom_hue), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = hue,
                    onValueChange = {
                        hue = it
                        hex = hexOf(Color.hsv(hue, saturation, value))
                    },
                    valueRange = 0f..360f,
                )
                Text(stringResource(R.string.custom_saturation), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = saturation,
                    onValueChange = {
                        saturation = it
                        hex = hexOf(Color.hsv(hue, saturation, value))
                    },
                    valueRange = 0f..1f,
                )
                Text(stringResource(R.string.custom_brightness), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = value,
                    onValueChange = {
                        value = it
                        hex = hexOf(Color.hsv(hue, saturation, value))
                    },
                    valueRange = 0f..1f,
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { typed ->
                        hex = typed.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                        val rgb = if (hex.length == 6) hex.toIntOrNull(16) else null
                        if (rgb != null) {
                            val parsed = FloatArray(3)
                            android.graphics.Color.colorToHSV(0xFF000000.toInt() or rgb, parsed)
                            hue = parsed[0]
                            saturation = parsed[1]
                            value = parsed[2]
                        }
                    },
                    label = { Text(stringResource(R.string.custom_hex)) },
                    prefix = { Text("#") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(color.toArgb()) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
