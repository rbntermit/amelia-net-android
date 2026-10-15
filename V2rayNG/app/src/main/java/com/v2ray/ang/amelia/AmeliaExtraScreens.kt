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
    val onBypassRu: (Boolean) -> Unit,
    val onOpenApps: () -> Unit,
    val onAppBypass: (String, Boolean) -> Unit,
    val onAppsClosed: () -> Unit,
    val onProbe: (Boolean) -> Unit,
    val onDismissAnnounce: () -> Unit,
    /** "⚡ Подключить лучший". */
    val onConnectBest: () -> Unit,
    /** Answer to "Сообщить, что … не работает?": true = send. */
    val onAnswerSuggestion: (Boolean) -> Unit,
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

/**
 * "Подписка до 12.11 · осталось 12 дней": amber under 3 days; after the end a red card with
 * a big "Продлить". Nothing without an end date (expire = 0).
 */
@Composable
internal fun ExpiryBanner(info: AmeliaSubInfo?, onRenew: () -> Unit) {
    val expire = info?.expire?.takeIf { it > 0 } ?: return
    val now = System.currentTimeMillis()
    if (AmeliaFormat.isExpired(info, now)) {
        Glass(Modifier.fillMaxWidth(), tint = Color(0x40FF6B6B)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.amelia_sub_ended),
                    color = Brand.Text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                GradientButton(text = stringResource(R.string.amelia_renew), enabled = true, onClick = onRenew)
            }
        }
        return
    }
    val days = AmeliaFormat.daysLeft(info, now) ?: return
    val lastDay = AmeliaExpiry.stage(expire, now) == AmeliaExpiry.Stage.LAST_DAY
    val soon = days < 3
    val text = if (lastDay) {
        stringResource(R.string.amelia_sub_last_day, AmeliaFormat.dayMonth(expire))
    } else {
        stringResource(
            R.string.amelia_sub_until, AmeliaFormat.dayMonth(expire),
            pluralStringResource(R.plurals.amelia_days_left_plural, days, days),
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            color = if (soon) Brand.Amber else Brand.Muted,
            fontWeight = if (soon) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (soon) {
            TextButton(onClick = onRenew) {
                Text(stringResource(R.string.amelia_renew), color = Brand.Sky, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

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

/** Shown on the main screen when the connection failed or no data comes through it. */
@Composable
internal fun ReportButton(onClick: () -> Unit) {
    Glass(Modifier.fillMaxWidth(), tint = Color(0x33FFB547), onClick = onClick) {
        Text(
            "🩺  " + stringResource(R.string.amelia_report_button),
            color = Brand.Text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
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
internal fun SettingsAppsSection(state: AmeliaUiState, extra: ExtraCallbacks, onOpenApps: () -> Unit) {
    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(stringResource(R.string.amelia_apps_title), state.bypassRu, extra.onBypassRu)
            Text(stringResource(R.string.amelia_apps_text), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            if (state.bypassRu) {
                TextButton(onClick = onOpenApps) {
                    Text(
                        stringResource(R.string.amelia_apps_choose, state.bypassCount),
                        color = Brand.Sky,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsProbeSection(state: AmeliaUiState, extra: ExtraCallbacks) {
    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(stringResource(R.string.amelia_probe_title), state.probeEnabled, extra.onProbe)
            Text(stringResource(R.string.amelia_probe_text), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---- app picker ------------------------------------------------------------------------------

@Composable
internal fun AppsScreen(apps: List<AppRow>?, onToggle: (String, Boolean) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader(stringResource(R.string.amelia_apps_screen_title), onBack)
        Text(
            stringResource(R.string.amelia_apps_hint),
            color = Brand.Muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.amelia_apps_search)) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Brand.Sky,
                unfocusedBorderColor = Brand.GlassBorder,
                focusedLabelColor = Brand.Sky,
                cursorColor = Brand.Sky,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (apps == null) {
            CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally), color = Brand.Sky)
            return@Column
        }
        val shown = apps.filter {
            query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) || it.pkg.contains(query.trim(), ignoreCase = true)
        }
        LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(shown, key = { it.pkg }) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Checkbox) { onToggle(app.pkg, !app.bypass) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = app.bypass,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = Brand.Sky, uncheckedColor = Brand.Muted),
                        modifier = Modifier.padding(8.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(app.label, color = Brand.Text, style = MaterialTheme.typography.bodyLarge)
                        if (app.builtIn) {
                            Text(stringResource(R.string.amelia_apps_builtin), color = Brand.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
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

/** Offered by the app when one or two locations pass no data while the others work. */
@Composable
internal fun SuggestReportDialog(name: String, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(stringResource(R.string.amelia_suggest_title, name)) },
        text = { Text(stringResource(R.string.amelia_suggest_text)) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.amelia_suggest_send)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.amelia_suggest_no)) } },
    )
}
