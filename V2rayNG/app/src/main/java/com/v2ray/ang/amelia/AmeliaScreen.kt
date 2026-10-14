package com.v2ray.ang.amelia

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import kotlinx.coroutines.launch

@Composable
fun AmeliaApp(
    state: AmeliaUiState,
    onSubmitKey: (String) -> Unit,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onMessageShown: () -> Unit,
    updates: UpdateCallbacks,
) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.message, state.messageText) {
        val text = state.message?.let { context.getString(it) } ?: state.messageText
        if (text != null) {
            // Shown in a separate scope: clearing the message restarts this effect.
            scope.launch { snackbar.showSnackbar(text) }
            onMessageShown()
        }
    }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    when {
        !state.loaded -> Box(Modifier.fillMaxSize())
        !state.hasKey -> KeyScreen(state.busy, snackbar, onSubmitKey, onOpenUrl, onAbout = { showAbout = true })
        else -> HomeScreen(state, snackbar, onToggle, onSelect, onRefresh, onLogout, updates, onAbout = { showAbout = true })
    }

    if (showAbout) AboutDialog(onOpenUrl, onDismiss = { showAbout = false })
}

class UpdateCallbacks(
    val onCheck: () -> Unit,
    val onDownload: () -> Unit,
    val onInstall: (String) -> Unit,
)

@Composable
private fun UpdateCard(state: AmeliaUiState, updates: UpdateCallbacks) {
    val v = state.update ?: return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.amelia_update_available, v.versionName ?: v.versionCode.toString()),
                style = MaterialTheme.typography.titleSmall,
            )
            v.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val progress = state.updateProgress
            val apk = state.updateApkPath
            when {
                progress != null -> {
                    if (progress >= 0) {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.amelia_update_downloading, progress))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.amelia_update_downloading_unknown))
                    }
                }
                apk != null -> Button(onClick = { updates.onInstall(apk) }) {
                    Text(stringResource(R.string.amelia_update_install))
                }
                else -> Button(onClick = updates.onDownload) {
                    Text(stringResource(R.string.amelia_update_download))
                }
            }
        }
    }
}

@Composable
private fun AppMark(size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_amelia_foreground),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
            modifier = Modifier.size((size * 1.4).dp),
        )
    }
}

@Composable
private fun KeyScreen(
    busy: Boolean,
    snackbar: SnackbarHostState,
    onSubmit: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onAbout: () -> Unit,
) {
    var key by rememberSaveable { mutableStateOf("") }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppMark(88)
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.amelia_key_prompt),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text(stringResource(R.string.amelia_key_hint)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (key.isNotEmpty()) onSubmit(key) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSubmit(key) },
                enabled = !busy && key.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.amelia_key_submit))
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onOpenUrl(AmeliaConfig.BOT_URL) }) {
                Text(stringResource(R.string.amelia_open_bot))
            }
            Spacer(Modifier.height(32.dp))
            TextButton(onClick = onAbout) {
                Text(
                    stringResource(R.string.amelia_based_on),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class) // TopAppBar; stable replacement not available in the pinned BOM
@Composable
private fun HomeScreen(
    state: AmeliaUiState,
    snackbar: SnackbarHostState,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    updates: UpdateCallbacks,
    onAbout: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.info?.title ?: stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            painterResource(R.drawable.ic_more_vert_24dp),
                            contentDescription = stringResource(R.string.amelia_menu),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.amelia_menu_refresh)) },
                            enabled = !state.busy,
                            onClick = { menuOpen = false; onRefresh() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.amelia_menu_change_key)) },
                            onClick = { menuOpen = false; confirmLogout = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.amelia_menu_check_update)) },
                            onClick = { menuOpen = false; updates.onCheck() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.amelia_menu_about)) },
                            onClick = { menuOpen = false; onAbout() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(4.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.update != null) {
                    item(key = "update") { UpdateCard(state, updates) }
                }
                state.info?.announce?.let { announce ->
                    item(key = "announce") { AnnounceCard(announce) }
                }
                state.info?.let { info ->
                    item(key = "account") { AccountCard(info) }
                }
                item(key = "connect") { ConnectBlock(state, onToggle) }
                item(key = "header") {
                    Text(
                        stringResource(R.string.amelia_locations),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (state.locations.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.amelia_no_locations),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.locations, key = { it.guid }) { location ->
                    LocationRow(location, location.guid == state.selectedGuid) { onSelect(location.guid) }
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.amelia_logout_title)) },
            text = { Text(stringResource(R.string.amelia_logout_text)) },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) {
                    Text(stringResource(R.string.amelia_logout_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) {
                    Text(stringResource(R.string.amelia_cancel))
                }
            },
        )
    }
}

@Composable
private fun AnnounceCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun formatBytes(value: Long): String {
    val (num, unit) = AmeliaFormat.bytes(value)
    val unitRes = when (unit) {
        0 -> R.string.amelia_unit_b
        1 -> R.string.amelia_unit_kb
        2 -> R.string.amelia_unit_mb
        3 -> R.string.amelia_unit_gb
        else -> R.string.amelia_unit_tb
    }
    return "$num ${stringResource(unitRes)}"
}

@Composable
private fun AccountCard(info: AmeliaSubInfo) {
    val expired = AmeliaFormat.isExpired(info, System.currentTimeMillis())
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val remaining = info.remaining
            Text(
                when {
                    remaining != null -> stringResource(
                        R.string.amelia_traffic_left, formatBytes(remaining), formatBytes(info.total ?: 0L),
                    )
                    info.upload != null || info.download != null ->
                        stringResource(R.string.amelia_traffic_unlimited) + " · " +
                            stringResource(R.string.amelia_traffic_used, formatBytes(info.used))
                    else -> stringResource(R.string.amelia_traffic_unlimited)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                when {
                    !info.hasExpire -> stringResource(R.string.amelia_expire_never)
                    expired -> stringResource(R.string.amelia_expired, AmeliaFormat.date(info.expire ?: 0L))
                    else -> stringResource(R.string.amelia_expire_until, AmeliaFormat.date(info.expire ?: 0L))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectBlock(state: AmeliaUiState, onToggle: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = onToggle,
            shape = CircleShape,
            enabled = state.locations.isNotEmpty(),
            colors = if (state.isRunning) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier.size(156.dp),
        ) {
            Text(
                stringResource(if (state.isRunning) R.string.amelia_disconnect else R.string.amelia_connect),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (state.isRunning) {
                stringResource(R.string.amelia_status_connected, state.selectedName.orEmpty())
            } else {
                stringResource(R.string.amelia_status_disconnected)
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LocationRow(location: AmeliaManager.Location, selected: Boolean, onClick: () -> Unit) {
    Card(
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
            Text(location.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AboutDialog(onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppMark(48) },
        title = { Text(stringResource(R.string.amelia_about_title)) },
        text = {
            Text(
                stringResource(
                    R.string.amelia_about_text,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.UPSTREAM_VERSION,
                ) + "\n\n" + AmeliaConfig.SOURCE_CODE_URL
            )
        },
        confirmButton = {
            TextButton(onClick = { onOpenUrl(AmeliaConfig.SOURCE_CODE_URL) }) {
                Text(stringResource(R.string.amelia_about_source))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onOpenUrl(AmeliaConfig.UPSTREAM_URL) }) {
                    Text(stringResource(R.string.amelia_about_upstream))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.amelia_close)) }
            }
        },
    )
}
