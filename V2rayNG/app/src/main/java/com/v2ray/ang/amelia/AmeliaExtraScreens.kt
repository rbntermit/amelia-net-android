package com.v2ray.ang.amelia

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R

/** 1.1.0 actions, wired by AmeliaActivity to AmeliaViewModel. */
class ExtraCallbacks(
    val onOpenAbout: () -> Unit,
    /** Copies the support text; the activity owns the clipboard. */
    val onCopySupport: () -> Unit,
    val onCopyText: (String) -> Unit,
    val onStartReport: () -> Unit,
    val onSendReport: (String) -> Unit,
    val onCloseReport: () -> Unit,
    /** 1.5.0: "Банки", "Госуслуги", "Маркетплейсы" on the "Приложения мимо VPN" screen. */
    val onAppsPreset: (AmeliaApps.Preset) -> Unit,
    /** 1.5.0: "📨 Прокси для Telegram" (subscription valid). */
    val onTelegramProxy: () -> Unit,
    val onOpenApps: () -> Unit,
    val onAppBypass: (String, Boolean) -> Unit,
    val onAppsClosed: () -> Unit,
    val onProbe: (Boolean) -> Unit,
    val onDismissAnnounce: () -> Unit,
    // 1.4.0
    val onAutoMode: (AmeliaPolicy.Mode) -> Unit,
    val onOpenJournal: () -> Unit,
    val onCopyJournal: () -> Unit,
    val onInstallPermissionOpen: () -> Unit,
    val onInstallPermissionDismiss: () -> Unit,
    val onInstallRetry: () -> Unit,
    val onInstallHelpDone: () -> Unit,
    val onOpenSecurity: () -> Unit,
    val onDownloadInBrowser: () -> Unit,
    val onGetInTelegram: () -> Unit,
    /** Opens the system dialog that lets the app run in the background. */
    val onAllowBackground: () -> Unit,
    val onBatteryCardDone: () -> Unit,
    val onCrashReports: (Boolean) -> Unit,
    /** Long press on a location → speed test through it. */
    val onMeasureSpeed: (String) -> Unit,
    /** "⚡ Подключить лучший". */
    val onConnectBest: () -> Unit,
)

// ---- shared pieces ---------------------------------------------------------------------------

@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                painterResource(R.drawable.ic_arrow_back_24dp),
                contentDescription = stringResource(R.string.amelia_back),
                tint = Brand.Text,
            )
        }
        Text(title, color = Brand.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoLine(text: String, color: Color = Brand.Text) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}

/** A setting with an on/off switch; the whole row toggles (also with a TV remote). */
@Composable
internal fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Brand.Text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedThumbColor = Brand.Night, checkedTrackColor = Brand.Sky),
        )
    }
}

// ---- main screen banners ---------------------------------------------------------------------

/** Our announcement with clickable https:// and t.me/ links; the cross hides this text. */
@Composable
internal fun AnnounceBanner(text: String, onOpenUrl: (String) -> Unit, onClose: () -> Unit) {
    val linkStyle = TextLinkStyles(SpanStyle(color = Brand.Sky, textDecoration = TextDecoration.Underline))
    val annotated = buildAnnotatedString {
        var pos = 0
        for (link in AmeliaAnnounce.links(text)) {
            append(text.substring(pos, link.start))
            withLink(LinkAnnotation.Url(link.url, linkStyle) { onOpenUrl(link.url) }) {
                append(text.substring(link.start, link.end))
            }
            pos = link.end
        }
        append(text.substring(pos))
    }
    Glass(Modifier.fillMaxWidth(), tint = Color(0x3359ABFF)) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)) {
            Text(
                annotated,
                color = Brand.Text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(top = 8.dp, bottom = 8.dp),
            )
            IconButton(onClick = onClose) {
                val closeLabel = stringResource(R.string.amelia_announce_close)
                Text(
                    "✕",
                    color = Brand.Muted,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(2.dp).semantics { contentDescription = closeLabel },
                )
            }
        }
    }
}

/**
 * 1.3.0: a quiet link on the main screen when the connection failed or no data comes through.
 * It opens the report only when tapped; nothing offers a report on its own.
 */
@Composable
internal fun ReportButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            stringResource(R.string.amelia_report_link),
            color = Brand.Muted,
            style = MaterialTheme.typography.bodySmall,
            textDecoration = TextDecoration.Underline,
        )
    }
}

// ---- problem report --------------------------------------------------------------------------

@Composable
internal fun ReportDialog(
    report: ReportUi,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenBot: () -> Unit,
) {
    var comment by rememberSaveable { mutableStateOf("") }
    // Server unreachable: the report goes to the clipboard right away, for the bot chat
    LaunchedEffect(report.phase, report.fallbackText) {
        if (report.phase == ReportPhase.FAILED) report.fallbackText?.let(onCopy)
    }
    AlertDialog(
        onDismissRequest = { if (report.phase != ReportPhase.SENDING) onClose() },
        title = { Text(stringResource(R.string.amelia_report_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (report.phase) {
                    ReportPhase.TESTING -> {
                        Text(stringResource(R.string.amelia_report_testing, report.checks.size, report.total))
                        LinearProgressIndicator(
                            progress = { if (report.total == 0) 0f else report.checks.size.toFloat() / report.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    ReportPhase.SENT -> Text(stringResource(R.string.amelia_report_sent, report.reportId.orEmpty()))

                    ReportPhase.SENDING -> {
                        ReportSummary(report)
                        Text(stringResource(R.string.amelia_report_sending), color = Brand.Muted)
                    }

                    else -> {
                        ReportSummary(report)
                        if (report.phase == ReportPhase.FAILED) {
                            Text(stringResource(R.string.amelia_report_failed), color = Brand.Amber)
                        }
                        OutlinedTextField(
                            value = comment,
                            onValueChange = { comment = it.take(AmeliaReport.MAX_COMMENT) },
                            label = { Text(stringResource(R.string.amelia_report_comment)) },
                            enabled = report.phase != ReportPhase.SENDING,
                            minLines = 2,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Brand.Sky,
                                focusedLabelColor = Brand.Sky,
                                cursorColor = Brand.Sky,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (report.phase) {
                ReportPhase.TESTING -> Unit
                ReportPhase.SENT -> TextButton(onClick = onClose) { Text(stringResource(R.string.amelia_close)) }
                ReportPhase.SENDING -> CircularProgressIndicator(Modifier.padding(8.dp))
                ReportPhase.FAILED -> TextButton(onClick = { report.fallbackText?.let(onCopy); onOpenBot() }) {
                    Text(stringResource(R.string.amelia_report_open_bot))
                }
                ReportPhase.RESULT -> TextButton(onClick = { onSend(comment) }) {
                    Text(stringResource(R.string.amelia_report_send))
                }
            }
        },
        dismissButton = {
            Row {
                if (report.phase == ReportPhase.FAILED) {
                    TextButton(onClick = { onSend(comment) }) { Text(stringResource(R.string.amelia_report_retry)) }
                }
                if (report.phase != ReportPhase.SENT && report.phase != ReportPhase.SENDING) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.amelia_cancel)) }
                }
            }
        },
    )
}

/** "Работают: Финляндия 1, Швеция 2. Не работают: Нидерланды 1" and why each one fails. */
@Composable
private fun ReportSummary(report: ReportUi) {
    val working = report.working
    val failing = report.failing
    when {
        working.isEmpty() -> Text(stringResource(R.string.amelia_report_none_working), color = Brand.Amber)
        failing.isEmpty() -> Text(stringResource(R.string.amelia_report_all_working), color = Brand.Green)
        else -> Text(stringResource(R.string.amelia_report_working, working.joinToString(", ")), color = Brand.Green)
    }
    if (failing.isNotEmpty() && working.isNotEmpty()) {
        Text(stringResource(R.string.amelia_report_failing, failing.joinToString(", ")), color = Brand.Amber)
    }
    report.servers.filter { !it.ok && !AmeliaReserve.isReserve(it.name) }.forEach { s ->
        Text(
            "• ${s.name}: " + stringResource(
                if (s.stage == AmeliaPingStage.CONNECT) R.string.amelia_report_no_connect else R.string.amelia_report_no_data,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ---- about -----------------------------------------------------------------------------------

@Composable
internal fun AboutScreen(
    info: AmeliaAbout.Info?,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopySupport: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(stringResource(R.string.amelia_about_title), onBack)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppMark(56)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Amelia.Net", color = Brand.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.amelia_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    color = Brand.Muted,
                )
            }
        }
        if (info == null) {
            Text(stringResource(R.string.amelia_about_loading), color = Brand.Muted)
        } else {
            Glass(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle(stringResource(R.string.amelia_about_versions))
                    InfoLine(stringResource(R.string.amelia_version_full, info.versionName, info.versionCode))
                    InfoLine(
                        stringResource(R.string.amelia_about_xray, info.xray) +
                            if (info.xrayFromRuntime) "" else " (" + stringResource(R.string.amelia_about_xray_build) + ")",
                    )
                    InfoLine(stringResource(R.string.amelia_about_hy2, info.xray))
                    InfoLine(stringResource(R.string.amelia_about_hev, info.hev))
                }
            }
            Glass(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle(stringResource(R.string.amelia_about_data))
                    InfoLine(stringResource(R.string.amelia_about_geoip, AmeliaAbout.dateOrDash(info.geoipAt)))
                    InfoLine(stringResource(R.string.amelia_about_geosite, AmeliaAbout.dateOrDash(info.geositeAt)))
                    InfoLine(AmeliaAbout.subText(context, info))
                    InfoLine(AmeliaAbout.updateAnswerText(context, info.updateAnswer))
                }
            }
            Glass(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle(stringResource(R.string.amelia_probe_title))
                    InfoLine(
                        stringResource(if (info.probeEnabled) R.string.amelia_about_probe_on else R.string.amelia_about_probe_off),
                    )
                    Text(stringResource(R.string.amelia_probe_text), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            GradientButton(text = stringResource(R.string.amelia_about_copy), enabled = true, onClick = onCopySupport)
        }
        TextButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.amelia_menu_check_update), color = Brand.Sky)
        }
        Glass(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.amelia_about_license, BuildConfig.UPSTREAM_VERSION),
                    color = Brand.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(AmeliaConfig.SOURCE_CODE_URL, color = Brand.Text, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { onOpenUrl(AmeliaConfig.SOURCE_CODE_URL) }) {
                        Text(stringResource(R.string.amelia_about_source), color = Brand.Sky)
                    }
                    TextButton(onClick = { onOpenUrl(AmeliaConfig.UPSTREAM_URL) }) {
                        Text(stringResource(R.string.amelia_about_upstream), color = Brand.Sky)
                    }
                }
            }
        }
        Spacer(Modifier.height(72.dp))
    }
}

// ---- settings: 1.1.0 sections ----------------------------------------------------------------

@Composable
internal fun SettingsProbeSection(state: AmeliaUiState, extra: ExtraCallbacks) {
    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(stringResource(R.string.amelia_probe_title), state.probeEnabled, extra.onProbe)
            Text(stringResource(R.string.amelia_probe_text), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Connected through a reserve location: only Telegram works, so the bot can take the payment. */
@Composable
internal fun ReserveFallbackBanner(onRenew: () -> Unit) {
    Glass(Modifier.fillMaxWidth(), tint = Color(0x40FF6B6B)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.amelia_reserve_fallback),
                color = Brand.Text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            GradientButton(text = stringResource(R.string.amelia_renew), enabled = true, onClick = onRenew)
        }
    }
}

/** "проверено 3 минуты назад · за 7,4 с" under the location list. */
@Composable
internal fun CheckedAgo(checkedAt: Long, lastCheckMs: Long, modifier: Modifier = Modifier) {
    if (checkedAt <= 0) return
    val minutes = ((System.currentTimeMillis() - checkedAt) / 60_000).toInt()
    val ago = when {
        minutes < 1 -> stringResource(R.string.amelia_checked_now)
        minutes < 24 * 60 -> pluralStringResource(R.plurals.amelia_checked_min_ago, minutes, minutes)
        else -> stringResource(R.string.amelia_checked_long_ago)
    }
    val took = if (lastCheckMs > 0) {
        " · " + stringResource(R.string.amelia_checked_took, String.format(java.util.Locale.US, "%.1f", lastCheckMs / 1000.0).replace('.', ','))
    } else ""
    Text(ago + took, color = Brand.Muted, style = MaterialTheme.typography.labelSmall, modifier = modifier)
}

/** "⚡ Подключить лучший": the first location of the failover queue. */
@Composable
internal fun ConnectBestButton(onClick: () -> Unit) {
    Glass(Modifier.fillMaxWidth(), tint = Color(0x2659ABFF), onClick = onClick) {
        Text(
            stringResource(R.string.amelia_connect_best),
            color = Brand.Text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

/** Once after the first connection while the phone may put the app to sleep. */
@Composable
internal fun BatteryCard(onAllow: () -> Unit, onClose: () -> Unit) {
    Glass(Modifier.fillMaxWidth(), tint = Color(0x33FFB547)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    stringResource(R.string.amelia_battery_card_title),
                    color = Brand.Text,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                val closeLabel = stringResource(R.string.amelia_close)
                IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                    Text("✕", color = Brand.Muted, fontSize = 16.sp)
                }
            }
            Text(stringResource(R.string.amelia_battery_card_text), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            if (AmeliaBattery.isSamsung()) {
                Text(stringResource(R.string.amelia_battery_samsung), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            }
            GradientButton(text = stringResource(R.string.amelia_battery_allow), enabled = true, onClick = onAllow)
        }
    }
}

/** Settings: background work allowed or not, and how to allow it. */
@Composable
internal fun SettingsBatterySection(state: AmeliaUiState, extra: ExtraCallbacks) {
    Glass(Modifier.fillMaxWidth(), onClick = if (state.batteryOk) null else extra.onAllowBackground) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.amelia_battery_setting), color = Brand.Text, style = MaterialTheme.typography.titleSmall)
            if (state.batteryOk) {
                Text(stringResource(R.string.amelia_battery_ok), color = Brand.Green, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.amelia_battery_not_ok), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
                if (AmeliaBattery.isSamsung()) {
                    Text(stringResource(R.string.amelia_battery_samsung), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = extra.onAllowBackground) {
                    Text(stringResource(R.string.amelia_battery_allow), color = Brand.Sky)
                }
            }
        }
    }
}

@Composable
internal fun SettingsCrashSection(state: AmeliaUiState, extra: ExtraCallbacks) {
    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(stringResource(R.string.amelia_crash_title), state.crashEnabled, extra.onCrashReports)
            Text(stringResource(R.string.amelia_crash_text), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---- 1.4.0 -----------------------------------------------------------------------------------

@Composable
internal fun SettingsAutoSwitchSection(state: AmeliaUiState, extra: ExtraCallbacks, onOpenJournal: () -> Unit) {
    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle(stringResource(R.string.amelia_autoswitch_title))
            listOf(
                AmeliaPolicy.Mode.ALWAYS to R.string.amelia_autoswitch_always,
                AmeliaPolicy.Mode.UNLESS_MANUAL to R.string.amelia_autoswitch_unless_manual,
                AmeliaPolicy.Mode.OFF to R.string.amelia_autoswitch_off,
            ).forEach { (mode, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.RadioButton) { extra.onAutoMode(mode) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = state.autoMode == mode,
                        onClick = null,
                        colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = Brand.Sky, unselectedColor = Brand.Muted),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(label), color = Brand.Text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(R.string.amelia_autoswitch_text), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpenJournal) { Text(stringResource(R.string.amelia_journal), color = Brand.Sky) }
        }
    }
}

/** "Журнал": the last switches and checks, newest first, with a copy button for support. */
@Composable
internal fun JournalScreen(entries: List<AmeliaJournal.Entry>?, onBack: () -> Unit, onCopy: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader(stringResource(R.string.amelia_journal), onBack)
        if (entries == null) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = Brand.Sky)
            return@Column
        }
        if (entries.isEmpty()) {
            Text(stringResource(R.string.amelia_journal_empty), color = Brand.Muted)
        } else {
            GradientButton(text = stringResource(R.string.amelia_about_copy), enabled = true, onClick = onCopy)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(entries) { e ->
                Row {
                    Text(
                        java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(e.ts)),
                        color = Brand.Muted,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(84.dp),
                    )
                    Text(e.text, color = Brand.Text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Before installing: why Android asks, and the button to the switch (1.4.0). */
@Composable
internal fun InstallPermissionDialog(onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.amelia_install_permission_title)) },
        text = { Text(stringResource(R.string.amelia_install_permission_text)) },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.amelia_install_permission_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.amelia_cancel)) } },
    )
}

/** The phone refused the update: how to let it through, or other ways to get it (1.4.0). */
@Composable
internal fun InstallBlockedDialog(extra: ExtraCallbacks, canDownload: Boolean) {
    AlertDialog(
        onDismissRequest = extra.onInstallHelpDone,
        title = { Text(stringResource(R.string.amelia_install_blocked_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.amelia_install_blocked_text))
                Text(
                    stringResource(if (AmeliaBattery.isSamsung()) R.string.amelia_install_blocked_samsung else R.string.amelia_install_blocked_play),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = extra.onOpenSecurity) { Text(stringResource(R.string.amelia_install_open_security)) }
                TextButton(onClick = extra.onInstallRetry) { Text(stringResource(R.string.amelia_install_retry)) }
                if (canDownload) {
                    TextButton(onClick = extra.onDownloadInBrowser) { Text(stringResource(R.string.amelia_install_download_browser)) }
                }
                TextButton(onClick = extra.onGetInTelegram) { Text(stringResource(R.string.amelia_install_telegram)) }
            }
        },
        confirmButton = { TextButton(onClick = extra.onInstallHelpDone) { Text(stringResource(R.string.amelia_close)) } },
    )
}
