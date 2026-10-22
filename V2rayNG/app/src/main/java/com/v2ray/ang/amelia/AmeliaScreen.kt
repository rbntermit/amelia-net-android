package com.v2ray.ang.amelia

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.amelia.AmeliaConfig.BotScreen
import kotlinx.coroutines.launch

// ---- Brand palette, taken from the logo ------------------------------------------------------

internal object Brand {
    val Night = Color(0xFF0B1336)
    val Navy = Color(0xFF1B2266)
    val Indigo = Color(0xFF483ED5)
    val Sky = Color(0xFF59ABFF)
    val Text = Color(0xFFF2F4FF)
    val Muted = Color(0xFFA9B1D6)
    val Glass = Color(0x1AFFFFFF)
    val GlassBorder = Color(0x26FFFFFF)
    val Green = Color(0xFF3DDC97)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFFF6B6B)
}

private val BrandColors = darkColorScheme(
    primary = Brand.Sky,
    onPrimary = Brand.Night,
    secondary = Brand.Indigo,
    background = Brand.Night,
    surface = Color(0xFF151C4D),
    onSurface = Brand.Text,
    surfaceVariant = Color(0xFF222A62),
    onSurfaceVariant = Brand.Muted,
    surfaceContainerLow = Color(0xFF151C4D),
    surfaceContainer = Color(0xFF1A2256),
    surfaceContainerHigh = Color(0xFF20285E),
    error = Brand.Red,
)

class UpdateCallbacks(
    val onCheck: () -> Unit,
    val onDownload: () -> Unit,
    val onInstall: (String) -> Unit,
)

class SettingsCallbacks(
    /** Settings opened: load geo base info and the last update check time. */
    val onOpen: () -> Unit,
    val onCheckRoute: (String) -> Unit,
    val onRefreshSubscription: () -> Unit,
)

@Composable
fun AmeliaApp(
    state: AmeliaUiState,
    onSubmitKey: (String) -> Unit,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onRecheck: () -> Unit,
    onPingAll: () -> Unit,
    onLogout: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onMessageShown: () -> Unit,
    updates: UpdateCallbacks,
    settings: SettingsCallbacks,
    extra: ExtraCallbacks,
) {
    MaterialTheme(colorScheme = BrandColors) {
        val snackbar = remember { SnackbarHostState() }
        val resources = LocalResources.current
        val scope = rememberCoroutineScope()
        LaunchedEffect(state.message, state.messageText) {
            val text = state.message?.let { resources.getString(it) } ?: state.messageText
            if (text != null) {
                // Shown in a separate scope: clearing the message restarts this effect.
                scope.launch { snackbar.showSnackbar(text) }
                onMessageShown()
            }
        }
        var showAbout by rememberSaveable { mutableStateOf(false) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var showApps by rememberSaveable { mutableStateOf(false) }
        var showJournal by rememberSaveable { mutableStateOf(false) }
        val openAbout = { extra.onOpenAbout(); showAbout = true }
        val openJournal = { extra.onOpenJournal(); showJournal = true }

        BrandBackground {
            when {
                !state.loaded -> Unit
                showAbout -> {
                    BackHandler { showAbout = false }
                    AboutScreen(
                        state.about, onBack = { showAbout = false }, onOpenUrl = onOpenUrl,
                        onCopySupport = extra.onCopySupport, onCheckUpdate = updates.onCheck,
                    )
                }
                !state.hasKey -> KeyScreen(state.busy, onSubmitKey, onOpenUrl, onAbout = openAbout)
                showJournal -> {
                    BackHandler { showJournal = false }
                    JournalScreen(state.journal, onBack = { showJournal = false }, onCopy = extra.onCopyJournal)
                }
                showApps -> {
                    val close = { showApps = false; extra.onAppsClosed() }
                    BackHandler(onBack = close)
                    AppsScreen(state.apps, extra.onAppBypass, extra.onAppsPreset, onBack = close)
                }
                showSettings -> {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        state, updates, settings, extra,
                        onAbout = openAbout,
                        onOpenApps = { extra.onOpenApps(); showApps = true },
                        onOpenJournal = openJournal,
                        onBack = { showSettings = false },
                    )
                }
                else -> HomeScreen(
                    state, onToggle, onSelect, onRefresh, onRecheck, onPingAll, onLogout, onOpenUrl, updates, extra,
                    onAbout = openAbout,
                    onJournal = openJournal,
                    onSettings = { settings.onOpen(); showSettings = true },
                )
            }
            SnackbarHost(
                snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        }
        // Offered once per version per app start; a required update cannot be put off.
        var postponed by rememberSaveable { mutableStateOf(0) }
        val update = state.update
        // 1.5.5: after "Отмена" in the system window the window does not pop up by itself for a day
        if (update != null && (state.updateRequired || postponed != update.versionCode) &&
            AmeliaInstaller.offerUpdateDialog(state.installCancelledAt, System.currentTimeMillis(), state.updateRequired)
        ) {
            UpdateDialog(
                state, updates,
                onLater = if (state.updateRequired) null else ({ postponed = update.versionCode }),
            )
        }
        state.installPermissionFor?.let {
            InstallPermissionDialog(onOpen = extra.onInstallPermissionOpen, onDismiss = extra.onInstallPermissionDismiss)
        }
        if (state.installBlocked && state.update != null) {
            InstallBlockedDialog(extra, canDownload = state.update.url != null)
        }
        state.report?.let { r ->
            ReportDialog(
                r, onSend = extra.onSendReport, onClose = extra.onCloseReport, onCopy = extra.onCopyText,
                onOpenBot = { onOpenUrl(AmeliaConfig.botUrl(BotScreen.SUPPORT)) },
                onOpenBotHome = { onOpenUrl(AmeliaConfig.BOT_URL) },
            )
        }
    }
}

/**
 * The avatar as the screen background (1.0.2): its exact top-to-bottom gradient, and the
 * artwork itself (ring, "A", ".Net") large and faded in the middle.
 */
@Composable
private fun BrandBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Avatar.Top, Avatar.Middle, Avatar.Bottom))),
    ) {
        Image(
            painter = painterResource(R.drawable.amelia_watermark),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.92f)
                .aspectRatio(1f)
                .alpha(0.11f),
        )
        content()
    }
}

/** Colors sampled from the avatar (docs/brand/icon-source.jpg). */
private object Avatar {
    val Top = Color(0xFF111C49)
    val Middle = Color(0xFF2D2D91)
    val Bottom = Color(0xFF483ED5)
}

@Composable
internal fun AppMark(size: Int) {
    Image(
        painter = painterResource(R.drawable.ic_amelia_logo),
        contentDescription = null,
        modifier = Modifier.size(size.dp).clip(CircleShape),
    )
}

/** Translucent card used everywhere on the brand background. */
@Composable
internal fun Glass(
    modifier: Modifier = Modifier,
    tint: Color = Brand.Glass,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .clip(shape)
            .background(tint, shape)
            .border(BorderStroke(1.dp, Brand.GlassBorder), shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) { content() }
}

// ---- Key entry -------------------------------------------------------------------------------

@Composable
private fun KeyScreen(
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onAbout: () -> Unit,
) {
    var key by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppMark(132)
        Spacer(Modifier.height(20.dp))
        Text("Amelia.Net", color = Brand.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.amelia_key_prompt),
            color = Brand.Muted,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Glass(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text(stringResource(R.string.amelia_key_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (key.isNotEmpty()) onSubmit(key) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Brand.Sky,
                        unfocusedBorderColor = Brand.GlassBorder,
                        focusedLabelColor = Brand.Sky,
                        cursorColor = Brand.Sky,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                GradientButton(
                    text = stringResource(R.string.amelia_key_submit),
                    enabled = !busy && key.isNotEmpty(),
                    busy = busy,
                    onClick = { onSubmit(key) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { onOpenUrl(AmeliaConfig.BOT_URL) }) {
            Text(stringResource(R.string.amelia_open_bot), color = Brand.Sky)
        }
        TextButton(onClick = { onOpenUrl(AmeliaConfig.botUrl(BotScreen.PAY)) }) {
            Text(stringResource(R.string.amelia_act_pay), color = Brand.Muted)
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onAbout) {
            Text(stringResource(R.string.amelia_based_on), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun GradientButton(text: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .background(
                Brush.horizontalGradient(listOf(Brand.Sky, Brand.Indigo)),
                shape,
            )
            .alpha(if (enabled || busy) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}

// ---- Home ------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    state: AmeliaUiState,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onRecheck: () -> Unit,
    onPingAll: () -> Unit,
    onLogout: () -> Unit,
    onOpenUrl: (String) -> Unit,
    updates: UpdateCallbacks,
    extra: ExtraCallbacks,
    onAbout: () -> Unit,
    onJournal: () -> Unit,
    onSettings: () -> Unit,
) {
    var showLocations by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopBar(
            title = state.info?.title ?: "Amelia.Net",
            busy = state.busy,
            canRecheck = state.isRunning,
            onRefresh = onRefresh,
            onRecheck = onRecheck,
            onCheckUpdate = updates.onCheck,
            onChangeKey = { confirmLogout = true },
            onAbout = onAbout,
            onSettings = onSettings,
            onReport = extra.onStartReport,
            onJournal = onJournal,
            onCabinet = extra.onCabinet,
        )
        if (state.update != null) {
            UpdateCard(state, updates)
            Spacer(Modifier.height(10.dp))
        }
        if (state.onReserveFallback) {
            ReserveFallbackBanner(onRenew = extra.onRenew)
        } else {
            SubscriptionLine(state.info, onRenew = extra.onRenew, onPayOnSite = extra.onPayOnSite)
        }
        BootPermissionLine(state)
        if (state.showBatteryCard) {
            Spacer(Modifier.height(10.dp))
            BatteryCard(
                onAllow = { extra.onBatteryCardDone(); extra.onAllowBackground() },
                onClose = extra.onBatteryCardDone,
            )
        }

        Spacer(Modifier.height(16.dp))
        PowerButton(state.phase, enabled = state.locations.isNotEmpty(), onClick = onToggle)
        Spacer(Modifier.height(16.dp))
        PhaseText(state)
        state.traffic?.takeIf { state.isRunning }?.let {
            Spacer(Modifier.height(10.dp))
            TrafficPanel(it)
        }
        if (state.showReportButton) {
            Spacer(Modifier.height(12.dp))
            ReportButton(extra.onStartReport)
        }
        Spacer(Modifier.height(20.dp))

        state.announce?.let {
            AnnounceBanner(it, onOpenUrl, onClose = extra.onDismissAnnounce)
            Spacer(Modifier.height(12.dp))
        }
        LocationSelector(state, onClick = { showLocations = true })
        if (state.locations.size > 1) {
            Spacer(Modifier.height(10.dp))
            ConnectBestButton(extra.onConnectBest)
        }
        Spacer(Modifier.height(10.dp))
        TelegramProxyItem(
            state.info,
            onOpen = extra.onTelegramProxy,
            onRenew = extra.onRenew,
        )
        Spacer(Modifier.height(16.dp))
        ActionGrid(
            onAction = { screen -> onOpenUrl(AmeliaConfig.botUrl(screen)) },
            onPay = extra.onRenew,
            // Checks every location, shows which work, offers to send the result to support
            onNotWorking = extra.onStartReport,
            onGroup = { onOpenUrl(AmeliaConfig.GROUP_URL) },
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onAbout) {
            Text(stringResource(R.string.amelia_based_on), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(72.dp)) // room for the snackbar
    }

    if (showLocations) {
        LocationsSheet(
            state,
            onSelect = { onSelect(it); showLocations = false },
            onRefresh = onRefresh,
            onPingAll = onPingAll,
            onMeasureSpeed = extra.onMeasureSpeed,
            onDismiss = { showLocations = false },
        )
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
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.amelia_cancel)) }
            },
        )
    }
}

@Composable
private fun TopBar(
    title: String,
    busy: Boolean,
    canRecheck: Boolean,
    onRefresh: () -> Unit,
    onRecheck: () -> Unit,
    onCheckUpdate: () -> Unit,
    onChangeKey: () -> Unit,
    onAbout: () -> Unit,
    onSettings: () -> Unit,
    onReport: () -> Unit,
    onJournal: () -> Unit,
    onCabinet: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMark(36)
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            color = Brand.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Brand.Sky, strokeWidth = 2.dp)
        IconButton(onClick = onSettings) {
            Icon(
                painterResource(R.drawable.ic_settings_24dp),
                contentDescription = stringResource(R.string.amelia_settings),
                tint = Brand.Text,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    painterResource(R.drawable.ic_more_vert_24dp),
                    contentDescription = stringResource(R.string.amelia_menu),
                    tint = Brand.Text,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.amelia_site_cabinet)) },
                    onClick = { menuOpen = false; onCabinet() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.amelia_menu_refresh)) },
                    enabled = !busy,
                    onClick = { menuOpen = false; onRefresh() },
                )
                if (canRecheck) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.amelia_menu_recheck)) },
                        onClick = { menuOpen = false; onRecheck() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.amelia_menu_report)) },
                    onClick = { menuOpen = false; onReport() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.amelia_journal)) },
                    onClick = { menuOpen = false; onJournal() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.amelia_menu_check_update)) },
                    onClick = { menuOpen = false; onCheckUpdate() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.amelia_menu_change_key)) },
                    onClick = { menuOpen = false; onChangeKey() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.amelia_menu_about)) },
                    onClick = { menuOpen = false; onAbout() },
                )
            }
        }
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

// ---- Power button ----------------------------------------------------------------------------

private fun phaseColor(phase: ConnPhase): Color = when (phase) {
    ConnPhase.OFF -> Brand.Muted
    ConnPhase.STARTING, ConnPhase.CHECKING, ConnPhase.SWITCHING -> Brand.Sky
    ConnPhase.CONNECTED -> Brand.Green
    ConnPhase.NO_INTERNET, ConnPhase.ALL_FAILED, ConnPhase.MANUAL_FAILED -> Brand.Amber
}

private fun ConnPhase.inProgress() =
    this == ConnPhase.STARTING || this == ConnPhase.CHECKING || this == ConnPhase.SWITCHING

@Composable
private fun PowerButton(phase: ConnPhase, enabled: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(phaseColor(phase), label = "power-color")
    val transition = rememberInfiniteTransition(label = "power")
    val sweepStart by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.85f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "pulse",
    )
    val on = phase != ConnPhase.OFF
    val label = stringResource(if (on) R.string.amelia_disconnect else R.string.amelia_connect)

    Box(
        Modifier
            .size(210.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // outer glow / progress ring
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val glowAlpha = if (phase == ConnPhase.CONNECTED) 0.35f * pulse else 0.18f
            drawCircle(color.copy(alpha = glowAlpha), radius = size.minDimension / 2)
            if (phase.inProgress()) {
                drawArc(
                    color = color,
                    startAngle = sweepStart,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                    topLeft = Offset(stroke, stroke),
                    size = androidx.compose.ui.geometry.Size(size.width - 2 * stroke, size.height - 2 * stroke),
                )
            } else {
                drawCircle(
                    color.copy(alpha = 0.7f),
                    radius = size.minDimension / 2 - stroke,
                    style = Stroke(stroke / 2),
                )
            }
        }
        // the button itself
        Box(
            Modifier
                .size(158.dp)
                .clip(CircleShape)
                .background(
                    if (on) Brush.verticalGradient(listOf(color.copy(alpha = 0.95f), Brand.Indigo))
                    else Brush.verticalGradient(listOf(Color(0xFF2A3275), Color(0xFF1A2058))),
                )
                .border(2.dp, color.copy(alpha = 0.8f), CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PowerGlyph(if (on) Color.White else Brand.Sky, Modifier.size(54.dp))
                Spacer(Modifier.height(6.dp))
                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

/** Standard power symbol, drawn so no icon library is needed. */
@Composable
private fun PowerGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.minDimension
        val stroke = w * 0.11f
        val pad = stroke
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(pad, pad),
            size = androidx.compose.ui.geometry.Size(w - 2 * pad, w - 2 * pad),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(w / 2, pad * 0.4f), Offset(w / 2, w * 0.48f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun PhaseText(state: AmeliaUiState) {
    val text = when (state.phase) {
        ConnPhase.OFF -> stringResource(R.string.amelia_phase_off)
        ConnPhase.STARTING -> stringResource(R.string.amelia_phase_starting)
        ConnPhase.CHECKING -> stringResource(R.string.amelia_phase_checking)
        ConnPhase.SWITCHING -> stringResource(R.string.amelia_phase_switching)
        // 1.4.0: which location, and its ping, right under the button
        ConnPhase.CONNECTED -> {
            val name = state.selectedName
            val ping = state.pingMs
            when {
                name != null && ping != null -> stringResource(R.string.amelia_phase_connected_on_ping, name, ping.toInt())
                name != null -> stringResource(R.string.amelia_phase_connected_on, name)
                ping != null -> stringResource(R.string.amelia_phase_connected_ping, ping.toInt())
                else -> stringResource(R.string.amelia_phase_connected)
            }
        }
        ConnPhase.MANUAL_FAILED -> stringResource(R.string.amelia_phase_manual_failed)
        ConnPhase.NO_INTERNET -> stringResource(R.string.amelia_phase_no_internet)
        ConnPhase.ALL_FAILED -> stringResource(R.string.amelia_phase_all_failed)
    }
    Text(
        text,
        color = if (state.phase == ConnPhase.OFF) Brand.Muted else phaseColor(state.phase),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

// ---- Locations -------------------------------------------------------------------------------

/** Ping value with Happ-like colours; "…" while a round is running and this one has no result yet. */
@Composable
private fun PingBadge(ms: Long?, pinging: Boolean, reserve: Boolean = false) {
    if (ms == null) {
        if (pinging) Text("…", color = Brand.Muted, style = MaterialTheme.typography.labelLarge)
        return
    }
    // Reserve locations are checked through Telegram, the only thing they pass
    if (reserve) {
        Text(
            stringResource(R.string.amelia_reserve_label) + " · ",
            color = Brand.Muted,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    val (text, color) = when (AmeliaFormat.pingLevel(ms)) {
        AmeliaFormat.PingLevel.GOOD -> stringResource(R.string.amelia_ping_ms, ms.toInt()) to Brand.Green
        AmeliaFormat.PingLevel.MEDIUM -> stringResource(R.string.amelia_ping_ms, ms.toInt()) to Brand.Amber
        AmeliaFormat.PingLevel.BAD -> stringResource(R.string.amelia_ping_ms, ms.toInt()) to Brand.Red
        AmeliaFormat.PingLevel.FAILED -> stringResource(R.string.amelia_ping_fail) to Brand.Red
    }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun LocationSelector(state: AmeliaUiState, onClick: () -> Unit) {
    Glass(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.amelia_location), color = Brand.Muted, style = MaterialTheme.typography.labelMedium)
                Text(
                    state.selectedName ?: stringResource(R.string.amelia_choose_location),
                    color = Brand.Text,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CheckedAgo(state.checkedAt, state.lastCheckMs)
            }
            PingBadge(
                state.selectedGuid?.let { state.pings[it] }, pinging = false,
                reserve = AmeliaReserve.isReserve(state.selectedName),
            )
            Spacer(Modifier.width(8.dp))
            Text("›", color = Brand.Sky, fontSize = 28.sp, fontWeight = FontWeight.Light)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class) // ModalBottomSheet; no stable alternative in the pinned BOM
@Composable
private fun LocationsSheet(
    state: AmeliaUiState,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onPingAll: () -> Unit,
    onMeasureSpeed: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF141B4A),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.amelia_locations),
                color = Brand.Text,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !state.busy && !state.pinging) {
                Text(stringResource(R.string.amelia_menu_refresh), color = Brand.Muted)
            }
        }
        CheckedAgo(state.checkedAt, state.lastCheckMs, Modifier.padding(horizontal = 20.dp))
        // Happ-style "ping all": measures every location through its own core instance
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            GradientButton(
                text = "⚡ " + stringResource(if (state.pinging) R.string.amelia_ping_stop else R.string.amelia_ping_all),
                enabled = state.locations.isNotEmpty(),
                onClick = onPingAll,
            )
        }
        if (state.pinging) {
            LinearProgressIndicator(
                progress = {
                    if (state.locations.isEmpty()) 0f
                    else state.pings.size.toFloat() / state.locations.size
                },
                color = Brand.Sky,
                trackColor = Brand.Glass,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (state.locations.isEmpty()) {
            Text(
                stringResource(R.string.amelia_no_locations),
                color = Brand.Muted,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.locations, key = { it.guid }) { location ->
                val selected = location.guid == state.selectedGuid
                val shape = RoundedCornerShape(16.dp)
                var menu by remember { mutableStateOf(false) }
                Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (selected) Color(0x3359ABFF) else Brand.Glass, shape)
                        // long press: "Замерить скорость" (1.3.0), never automatic
                        .combinedClickable(
                            role = Role.RadioButton,
                            onClick = { onSelect(location.guid) },
                            onLongClick = { menu = true },
                        )
                        .semantics { this.selected = selected }
                        .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = Brand.Sky, unselectedColor = Brand.Muted),
                        modifier = Modifier.padding(8.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(location.name, color = Brand.Text, style = MaterialTheme.typography.bodyLarge)
                        if (location.guid in state.providerBad) {
                            Text(
                                stringResource(R.string.amelia_provider_bad),
                                color = Brand.Amber,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        PingBadge(state.pings[location.guid], state.pinging, AmeliaReserve.isReserve(location.name))
                        SpeedBadge(state.speeds[location.guid], state.speedTesting == location.guid)
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.amelia_speed_measure)) },
                        enabled = state.speedTesting == null,
                        onClick = { menu = false; onMeasureSpeed(location.guid) },
                    )
                }
                }
            }
        }
    }
}

/** "≈ 48 Мбит/с" from a speed test in the last 24 h, or "замеряю…" while it runs. */
@Composable
private fun SpeedBadge(mbps: Double?, testing: Boolean) {
    when {
        testing -> Text(stringResource(R.string.amelia_speed_testing), color = Brand.Muted, style = MaterialTheme.typography.labelSmall)
        mbps != null -> Text(
            stringResource(R.string.amelia_speed_value, AmeliaSpeed.format(mbps)),
            color = Brand.Muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
// ---- Bot actions -----------------------------------------------------------------------------

private class ActionItem(val emoji: String, val label: Int, val onClick: () -> Unit)

@Composable
private fun ActionGrid(
    onAction: (BotScreen) -> Unit,
    onPay: () -> Unit,
    onNotWorking: () -> Unit,
    onGroup: () -> Unit,
) {
    val items = listOf(
        ActionItem("💳", R.string.amelia_act_pay, onPay),
        ActionItem("🎁", R.string.amelia_act_referral) { onAction(BotScreen.REFERRAL) },
        ActionItem("🩺", R.string.amelia_act_not_working, onNotWorking),
        ActionItem("💬", R.string.amelia_act_support) { onAction(BotScreen.SUPPORT) },
        ActionItem("❓", R.string.amelia_act_help) { onAction(BotScreen.HELP) },
        ActionItem("📢", R.string.amelia_act_group, onGroup),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { item ->
                    Glass(Modifier.weight(1f), onClick = item.onClick) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(item.emoji, fontSize = 24.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(item.label),
                                color = Brand.Text,
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- Update and about ------------------------------------------------------------------------

/** 1.3.1: "Обновление X готово — установить" once the APK is downloaded (e.g. on Wi-Fi in the background). */
private fun updateTitle(state: AmeliaUiState, required: Boolean): Int = when {
    required -> R.string.amelia_update_required_title
    state.updateApkPath != null -> R.string.amelia_update_ready
    else -> R.string.amelia_update_available
}

/** Download progress, then "Install"; or "Download" before that. */
@Composable
private fun UpdateActions(state: AmeliaUiState, updates: UpdateCallbacks) {
    val progress = state.updateProgress
    val apk = state.updateApkPath
    val colors = ButtonDefaults.buttonColors(containerColor = Brand.Sky, contentColor = Brand.Night)
    when {
        progress != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (progress >= 0) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.amelia_update_downloading, progress), color = Brand.Muted)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.amelia_update_downloading_unknown), color = Brand.Muted)
            }
        }
        // 1.5.1: one install at a time; the button waits for the running one
        state.installing -> Button(onClick = {}, enabled = false, colors = colors) {
            Text(stringResource(R.string.amelia_update_installing))
        }
        // 1.5.4: what Android will ask, so nobody taps "Отмена" out of fear (also after a cancel)
        apk != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.amelia_update_install_hint), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { updates.onInstall(apk) }, colors = colors) {
                Text(stringResource(R.string.amelia_update_install))
            }
        }
        else -> Button(onClick = updates.onDownload, colors = colors) {
            // on mobile data people see how much it costs before downloading
            Text(
                state.updateSize?.let { stringResource(R.string.amelia_update_download_size, AmeliaFormat.megabytes(it)) }
                    ?: stringResource(R.string.amelia_update_download),
            )
        }
    }
}

@Composable
private fun UpdateCard(state: AmeliaUiState, updates: UpdateCallbacks) {
    val v = state.update ?: return
    Glass(Modifier.fillMaxWidth(), tint = Color(0x3359ABFF)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(updateTitle(state, required = false), v.versionName ?: v.versionCode.toString()),
                color = Brand.Text,
                style = MaterialTheme.typography.titleSmall,
            )
            v.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = Brand.Muted, style = MaterialTheme.typography.bodySmall) }
            UpdateActions(state, updates)
        }
    }
}

/**
 * New version window. With [onLater] == null (required update) it has no "Later" button and
 * cannot be closed by back or a tap outside.
 */
@Composable
private fun UpdateDialog(state: AmeliaUiState, updates: UpdateCallbacks, onLater: (() -> Unit)?) {
    val v = state.update ?: return
    val name = v.versionName ?: v.versionCode.toString()
    AlertDialog(
        onDismissRequest = { onLater?.invoke() },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = onLater != null,
            dismissOnClickOutside = onLater != null,
        ),
        icon = { AppMark(56) },
        title = {
            Text(
                stringResource(
                    updateTitle(state, required = onLater == null),
                    name,
                ),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onLater == null) Text(stringResource(R.string.amelia_update_required_text))
                v.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = Brand.Muted) }
                UpdateActions(state, updates)
            }
        },
        confirmButton = {},
        dismissButton = {
            if (onLater != null) {
                TextButton(onClick = onLater) { Text(stringResource(R.string.amelia_update_later)) }
            }
        },
    )
}

// ---- Session traffic -------------------------------------------------------------------------

/** Traffic and time since the VPN was turned on; reported by the daemon every few seconds. */
@Composable
private fun TrafficPanel(t: AmeliaDaemon.Traffic) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(t.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    Glass(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(R.string.amelia_traffic_session, formatBytes(t.down), formatBytes(t.up)),
                color = Brand.Text,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.amelia_traffic_speed, formatBytes(t.downSpeed), formatBytes(t.upSpeed)) +
                    "   ·   " + stringResource(R.string.amelia_traffic_duration, AmeliaFormat.duration(now - t.startedAt)),
                color = Brand.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---- Settings --------------------------------------------------------------------------------

@Composable
private fun SettingsScreen(
    state: AmeliaUiState,
    updates: UpdateCallbacks,
    settings: SettingsCallbacks,
    extra: ExtraCallbacks,
    onAbout: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenJournal: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(stringResource(R.string.amelia_settings), onBack)

        SettingsSubscriptionSection(state, settings, extra)
        SettingsAppsSection(state, onOpenApps)
        SettingsBootSection(state, extra)
        SettingsBatterySection(state, extra)
        SettingsAutoSwitchSection(state, extra, onOpenJournal)
        SettingsUpdateSection(state, updates, onAbout)
        SettingsBypassSection(state, settings)
        SettingsProbeSection(state, extra)
        SettingsCrashSection(state, extra)
        TextButton(onClick = extra.onStartReport) {
            Text(stringResource(R.string.amelia_menu_report), color = Brand.Sky)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, color = Brand.Sky, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

/** When the locations were last downloaded, and a button to do it now. */
@Composable
private fun SettingsSubscriptionSection(state: AmeliaUiState, settings: SettingsCallbacks, extra: ExtraCallbacks) {
    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.amelia_settings_account))
            Text(
                if (state.subUpdatedAt > 0) {
                    stringResource(R.string.amelia_settings_sub_updated, AmeliaFormat.dateTime(state.subUpdatedAt))
                } else {
                    stringResource(R.string.amelia_settings_sub_never)
                },
                color = Brand.Text,
                style = MaterialTheme.typography.bodyMedium,
            )
            GradientButton(
                text = stringResource(R.string.amelia_settings_sub_refresh),
                enabled = !state.busy,
                busy = state.busy,
                onClick = settings.onRefreshSubscription,
            )
            // 1.5.7: the site (cabinet, payment without Telegram, gift); "Продлить" as on the main screen
            TextButton(onClick = extra.onCabinet) { Text(stringResource(R.string.amelia_site_cabinet), color = Brand.Sky) }
            TextButton(onClick = extra.onRenew) { Text(stringResource(R.string.amelia_site_renew), color = Brand.Sky) }
            TextButton(onClick = extra.onPayOnSite) { Text(stringResource(R.string.amelia_site_pay), color = Brand.Muted) }
            TextButton(onClick = extra.onGift) { Text(stringResource(R.string.amelia_site_gift), color = Brand.Sky) }
        }
    }
}

/** Current version, last check, and updating right from here. */
@Composable
private fun SettingsUpdateSection(state: AmeliaUiState, updates: UpdateCallbacks, onAbout: () -> Unit) {
    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.amelia_settings_app))
            Text(
                stringResource(R.string.amelia_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                color = Brand.Text,
                style = MaterialTheme.typography.titleMedium,
            )
            val v = state.update
            if (v != null) {
                Text(
                    stringResource(
                        updateTitle(state, required = state.updateRequired),
                        v.versionName ?: v.versionCode.toString(),
                    ),
                    color = Brand.Text,
                )
                v.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = Brand.Muted, style = MaterialTheme.typography.bodySmall) }
                UpdateActions(state, updates)
            } else {
                Text(
                    if (state.lastUpdateCheck > 0) {
                        stringResource(R.string.amelia_settings_up_to_date) + " · " +
                            stringResource(R.string.amelia_settings_last_check, AmeliaFormat.dateTime(state.lastUpdateCheck))
                    } else {
                        stringResource(R.string.amelia_settings_never_checked)
                    },
                    color = Brand.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                GradientButton(
                    text = stringResource(R.string.amelia_menu_check_update),
                    enabled = !state.updateChecking,
                    busy = state.updateChecking,
                    onClick = updates.onCheck,
                )
            }
            TextButton(onClick = onAbout) { Text(stringResource(R.string.amelia_menu_about), color = Brand.Sky) }
        }
    }
}

/** What goes around the VPN, and a checker for any site or address. */
@Composable
private fun SettingsBypassSection(state: AmeliaUiState, settings: SettingsCallbacks) {
    var input by rememberSaveable { mutableStateOf("") }
    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.amelia_bypass_title))
            Text(stringResource(R.string.amelia_bypass_text), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            val geo = state.geoInfo
            Text(
                if (geo != null && geo.sites > 0) {
                    stringResource(
                        R.string.amelia_bypass_geo, geo.sites, geo.networks,
                        if (geo.updatedAt > 0) AmeliaFormat.dateTime(geo.updatedAt) else "—",
                    )
                } else {
                    stringResource(R.string.amelia_bypass_geo_missing)
                },
                color = Brand.Text,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.amelia_bypass_direct_list, AmeliaRouting.DIRECT_SITES.joinToString(", ")),
                color = Brand.Text,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.amelia_bypass_local), color = Brand.Text, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.amelia_bypass_vpn_list, AmeliaRouting.PROXY_SITES.joinToString(", ")),
                color = Brand.Text,
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(4.dp))
            SectionTitle(stringResource(R.string.amelia_route_title))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.amelia_route_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (input.isNotBlank()) settings.onCheckRoute(input) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Brand.Sky,
                    unfocusedBorderColor = Brand.GlassBorder,
                    focusedLabelColor = Brand.Sky,
                    cursorColor = Brand.Sky,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            GradientButton(
                text = stringResource(R.string.amelia_route_check),
                enabled = input.isNotBlank() && !state.routeChecking,
                busy = state.routeChecking && state.routeCheck == null,
                onClick = { settings.onCheckRoute(input) },
            )
            state.routeCheck?.let { RouteResult(it, state.routeDirectMs, state.routeChecking) }
        }
    }
}

@Composable
private fun RouteResult(r: AmeliaRouteCheck.Result, directMs: Long?, checking: Boolean) {
    if (r.route == null) {
        Text(stringResource(R.string.amelia_reason_bad_input), color = Brand.Amber)
        return
    }
    val direct = r.route == AmeliaRouteCheck.Route.DIRECT
    Glass(Modifier.fillMaxWidth(), tint = if (direct) Color(0x333DDC97) else Color(0x3359ABFF)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                r.host + " — " + stringResource(if (direct) R.string.amelia_route_direct else R.string.amelia_route_vpn),
                color = if (direct) Brand.Green else Brand.Sky,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val rule = r.rule.orEmpty()
            Text(
                when (r.reason) {
                    AmeliaRouteCheck.Reason.PROXY_SITE -> stringResource(R.string.amelia_reason_proxy_site, rule)
                    AmeliaRouteCheck.Reason.DIRECT_SITE -> stringResource(R.string.amelia_reason_direct_site, rule)
                    AmeliaRouteCheck.Reason.RU_SITE -> stringResource(R.string.amelia_reason_ru_site, rule)
                    AmeliaRouteCheck.Reason.LOCAL_NETWORK -> stringResource(R.string.amelia_reason_local, rule)
                    AmeliaRouteCheck.Reason.RU_IP -> stringResource(R.string.amelia_reason_ru_ip, rule)
                    AmeliaRouteCheck.Reason.DEFAULT -> stringResource(R.string.amelia_reason_default)
                    AmeliaRouteCheck.Reason.BAD_INPUT -> stringResource(R.string.amelia_reason_bad_input)
                } + (r.ip?.let { " · " + stringResource(R.string.amelia_route_resolved, it) } ?: ""),
                color = Brand.Text,
                style = MaterialTheme.typography.bodySmall,
            )
            if (direct) {
                when {
                    directMs == null && checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    directMs != null && directMs >= 0 ->
                        Text(stringResource(R.string.amelia_route_direct_ok, directMs.toInt()), color = Brand.Green)
                    directMs != null ->
                        Text(stringResource(R.string.amelia_route_direct_fail), color = Brand.Amber)
                }
            }
        }
    }
}
