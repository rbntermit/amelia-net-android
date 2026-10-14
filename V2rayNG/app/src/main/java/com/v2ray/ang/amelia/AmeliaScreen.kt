package com.v2ray.ang.amelia

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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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

private object Brand {
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

@Composable
fun AmeliaApp(
    state: AmeliaUiState,
    onSubmitKey: (String) -> Unit,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onRecheck: () -> Unit,
    onLogout: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onMessageShown: () -> Unit,
    updates: UpdateCallbacks,
) {
    MaterialTheme(colorScheme = BrandColors) {
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

        BrandBackground {
            when {
                !state.loaded -> Unit
                !state.hasKey -> KeyScreen(state.busy, onSubmitKey, onOpenUrl, onAbout = { showAbout = true })
                else -> HomeScreen(
                    state, onToggle, onSelect, onRefresh, onRecheck, onLogout, onOpenUrl, updates,
                    onAbout = { showAbout = true },
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
        if (update != null && (state.updateRequired || postponed != update.versionCode)) {
            UpdateDialog(
                state, updates,
                onLater = if (state.updateRequired) null else ({ postponed = update.versionCode }),
            )
        }
        if (showAbout) {
            AboutDialog(
                onOpenUrl,
                onCheckUpdate = { showAbout = false; updates.onCheck() },
                onDismiss = { showAbout = false },
            )
        }
    }
}

/** Gradient from the logo with the logo itself, faded, as a watermark. */
@Composable
private fun BrandBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.Night, Brand.Navy, Brand.Indigo))),
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(1.3f)
                .aspectRatio(1f)
                .alpha(0.07f),
        )
        content()
    }
}

@Composable
private fun AppMark(size: Int) {
    Image(
        painter = painterResource(R.drawable.ic_amelia_logo),
        contentDescription = null,
        modifier = Modifier.size(size.dp).clip(CircleShape),
    )
}

/** Translucent card used everywhere on the brand background. */
@Composable
private fun Glass(
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
private fun GradientButton(text: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
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
    onLogout: () -> Unit,
    onOpenUrl: (String) -> Unit,
    updates: UpdateCallbacks,
    onAbout: () -> Unit,
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
        )
        if (state.update != null) {
            UpdateCard(state, updates)
            Spacer(Modifier.height(10.dp))
        }
        state.info?.announce?.let {
            AnnounceCard(it)
            Spacer(Modifier.height(10.dp))
        }
        state.info?.let {
            AccountCard(it, onRenew = { onOpenUrl(AmeliaConfig.botUrl(BotScreen.PAY)) })
        }

        Spacer(Modifier.height(28.dp))
        PowerButton(state.phase, enabled = state.locations.isNotEmpty(), onClick = onToggle)
        Spacer(Modifier.height(18.dp))
        PhaseText(state)
        Spacer(Modifier.height(24.dp))

        LocationSelector(state, onClick = { showLocations = true })
        Spacer(Modifier.height(16.dp))
        ActionGrid(
            onAction = { screen -> onOpenUrl(AmeliaConfig.botUrl(screen)) },
            onNotWorking = {
                // With the VPN on we can fix it ourselves: re-check and switch locations
                if (state.isRunning) onRecheck() else onOpenUrl(AmeliaConfig.botUrl(BotScreen.STATUS))
            },
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
private fun AnnounceCard(text: String) {
    Glass(Modifier.fillMaxWidth(), tint = Color(0x33FFB547)) {
        Text(text, color = Brand.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
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
private fun AccountCard(info: AmeliaSubInfo, onRenew: () -> Unit) {
    val now = System.currentTimeMillis()
    val expired = AmeliaFormat.isExpired(info, now)
    val daysLeft = AmeliaFormat.daysLeft(info, now)
    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val remaining = info.remaining
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        remaining != null -> stringResource(
                            R.string.amelia_traffic_left, formatBytes(remaining), formatBytes(info.total ?: 0L),
                        )
                        else -> stringResource(R.string.amelia_traffic_unlimited)
                    },
                    color = Brand.Text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (expired || (daysLeft != null && daysLeft <= 3)) {
                    TextButton(onClick = onRenew) {
                        Text(stringResource(R.string.amelia_renew), color = Brand.Sky, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            val total = info.total ?: 0L
            if (remaining != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    // amber when less than 10% is left
                    color = if (remaining * 10 < total) Brand.Amber else Brand.Sky,
                    trackColor = Brand.Glass,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
            Text(
                when {
                    !info.hasExpire -> stringResource(R.string.amelia_expire_never)
                    expired -> stringResource(R.string.amelia_expired, AmeliaFormat.date(info.expire ?: 0L))
                    else -> stringResource(R.string.amelia_expire_until, AmeliaFormat.date(info.expire ?: 0L)) +
                        (daysLeft?.let { " · " + stringResource(R.string.amelia_days_left, it) } ?: "")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (expired) Brand.Red else Brand.Muted,
            )
        }
    }
}

// ---- Power button ----------------------------------------------------------------------------

private fun phaseColor(phase: ConnPhase): Color = when (phase) {
    ConnPhase.OFF -> Brand.Muted
    ConnPhase.STARTING, ConnPhase.CHECKING, ConnPhase.SWITCHING -> Brand.Sky
    ConnPhase.CONNECTED -> Brand.Green
    ConnPhase.NO_INTERNET, ConnPhase.ALL_FAILED, ConnPhase.LOCATION_FAILED -> Brand.Amber
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
        ConnPhase.CONNECTED -> state.pingMs?.let { stringResource(R.string.amelia_phase_connected_ping, it.toInt()) }
            ?: stringResource(R.string.amelia_phase_connected)
        ConnPhase.NO_INTERNET -> stringResource(R.string.amelia_phase_no_internet)
        ConnPhase.ALL_FAILED -> stringResource(R.string.amelia_phase_all_failed)
        ConnPhase.LOCATION_FAILED -> stringResource(R.string.amelia_phase_location_failed)
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
            }
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
            TextButton(onClick = onRefresh, enabled = !state.busy) {
                Text(stringResource(R.string.amelia_menu_refresh), color = Brand.Sky)
            }
        }
        if (state.locations.isEmpty()) {
            Text(
                stringResource(R.string.amelia_no_locations),
                color = Brand.Muted,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.locations, key = { it.guid }) { location ->
                val selected = location.guid == state.selectedGuid
                val shape = RoundedCornerShape(16.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (selected) Color(0x3359ABFF) else Brand.Glass, shape)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(location.guid) })
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = Brand.Sky, unselectedColor = Brand.Muted),
                        modifier = Modifier.padding(8.dp),
                    )
                    Text(location.name, color = Brand.Text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

// ---- Bot actions -----------------------------------------------------------------------------

private class ActionItem(val emoji: String, val label: Int, val onClick: () -> Unit)

@Composable
private fun ActionGrid(
    onAction: (BotScreen) -> Unit,
    onNotWorking: () -> Unit,
    onGroup: () -> Unit,
) {
    val items = listOf(
        ActionItem("💳", R.string.amelia_act_pay) { onAction(BotScreen.PAY) },
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
        apk != null -> Button(onClick = { updates.onInstall(apk) }, colors = colors) {
            Text(stringResource(R.string.amelia_update_install))
        }
        else -> Button(onClick = updates.onDownload, colors = colors) {
            Text(stringResource(R.string.amelia_update_download))
        }
    }
}

@Composable
private fun UpdateCard(state: AmeliaUiState, updates: UpdateCallbacks) {
    val v = state.update ?: return
    Glass(Modifier.fillMaxWidth(), tint = Color(0x3359ABFF)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.amelia_update_available, v.versionName ?: v.versionCode.toString()),
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
                    if (onLater == null) R.string.amelia_update_required_title else R.string.amelia_update_available,
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

@Composable
private fun AboutDialog(onOpenUrl: (String) -> Unit, onCheckUpdate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppMark(56) },
        title = { Text(stringResource(R.string.amelia_about_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.amelia_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(
                    onClick = onCheckUpdate,
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Sky, contentColor = Brand.Night),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.amelia_menu_check_update)) }
                Text(
                    stringResource(
                        R.string.amelia_about_text,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.UPSTREAM_VERSION,
                    ) + "\n\n" + AmeliaConfig.SOURCE_CODE_URL,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    TextButton(onClick = { onOpenUrl(AmeliaConfig.SOURCE_CODE_URL) }) {
                        Text(stringResource(R.string.amelia_about_source))
                    }
                    TextButton(onClick = { onOpenUrl(AmeliaConfig.UPSTREAM_URL) }) {
                        Text(stringResource(R.string.amelia_about_upstream))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.amelia_close)) }
        },
    )
}