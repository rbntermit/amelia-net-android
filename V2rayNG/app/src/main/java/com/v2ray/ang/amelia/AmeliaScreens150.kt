package com.v2ray.ang.amelia

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- subscription line (main screen) ---------------------------------------------------------

/**
 * "Подписка до 05.11 · осталось 15 дн." with "12,4 из 100 ГБ" under it; accent colour and
 * "Продлить" from 3 days left; "Подписка бессрочная"; after the end the red card. Nothing
 * before the subscription is loaded.
 */
@Composable
internal fun SubscriptionLine(info: AmeliaSubInfo?, onRenew: () -> Unit, onPayOnSite: () -> Unit) {
    val line = AmeliaFormat.subLine(info, System.currentTimeMillis()) ?: return
    if (line == AmeliaFormat.SubLine.Ended) {
        Glass(Modifier.fillMaxWidth(), tint = Color(0x40FF6B6B)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.amelia_sub_ended),
                    color = Brand.Text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                GradientButton(text = stringResource(R.string.amelia_renew), enabled = true, onClick = onRenew)
                // 1.5.7: card or СБП on the site, for everyone (also with Telegram)
                TextButton(onClick = onPayOnSite) { Text(stringResource(R.string.amelia_site_pay), color = Brand.Muted) }
            }
        }
        return
    }
    val soon = line is AmeliaFormat.SubLine.Until && line.soon
    val text = when (line) {
        is AmeliaFormat.SubLine.Until ->
            if (line.lastDay) stringResource(R.string.amelia_sub_last_day, AmeliaFormat.dayMonth(line.expireSec))
            else stringResource(R.string.amelia_sub_until_days, AmeliaFormat.dayMonth(line.expireSec), line.days)
        else -> stringResource(R.string.amelia_sub_unlimited)
    }
    val usage = AmeliaFormat.usageGb(info)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text,
                color = if (soon) Brand.Amber else Brand.Muted,
                fontWeight = if (soon) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
            )
            usage?.let { (used, total) ->
                Text(
                    stringResource(R.string.amelia_sub_usage, used, total),
                    color = Brand.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (soon) {
            TextButton(onClick = onRenew) {
                Text(stringResource(R.string.amelia_renew), color = Brand.Sky, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---- "📨 Прокси для Telegram" ------------------------------------------------------------------

/**
 * Opens the bot, which sends the person's own proxy link. The app never builds or keeps the
 * link. With an ended subscription a dialog offers "Продлить" instead.
 */
@Composable
internal fun TelegramProxyItem(info: AmeliaSubInfo?, onOpen: () -> Unit, onRenew: () -> Unit) {
    var expiredDialog by remember { mutableStateOf(false) }
    Glass(
        Modifier.fillMaxWidth(),
        onClick = {
            if (info != null && AmeliaFormat.isExpired(info, System.currentTimeMillis())) expiredDialog = true else onOpen()
        },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.amelia_tgproxy_title),
                color = Brand.Text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.amelia_tgproxy_hint),
                color = Brand.Muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (expiredDialog) {
        AlertDialog(
            onDismissRequest = { expiredDialog = false },
            text = { Text(stringResource(R.string.amelia_tgproxy_expired)) },
            confirmButton = {
                TextButton(onClick = { expiredDialog = false; onRenew() }) { Text(stringResource(R.string.amelia_renew)) }
            },
            dismissButton = {
                TextButton(onClick = { expiredDialog = false }) { Text(stringResource(R.string.amelia_close)) }
            },
        )
    }
}

// ---- settings → "Приложения мимо VPN" ----------------------------------------------------------

@Composable
internal fun SettingsAppsSection(state: AmeliaUiState, onOpenApps: () -> Unit) {
    Glass(Modifier.fillMaxWidth(), onClick = onOpenApps) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.amelia_apps_title),
                    color = Brand.Text,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (state.bypassCount > 0) stringResource(R.string.amelia_apps_count, state.bypassCount)
                    else stringResource(R.string.amelia_apps_none),
                    color = Brand.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("›", color = Brand.Muted, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private val presetLabels = mapOf(
    AmeliaApps.Preset.BANKS to R.string.amelia_apps_preset_banks,
    AmeliaApps.Preset.GOV to R.string.amelia_apps_preset_gov,
    AmeliaApps.Preset.SHOPS to R.string.amelia_apps_preset_shops,
)

@Composable
internal fun AppsScreen(
    apps: List<AppRow>?,
    onToggle: (String, Boolean) -> Unit,
    onPreset: (AmeliaApps.Preset) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader(stringResource(R.string.amelia_apps_title), onBack)
        Text(
            stringResource(R.string.amelia_apps_explain),
            color = Brand.Muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        if (apps == null) {
            CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally), color = Brand.Sky)
            return@Column
        }
        val installed = remember(apps) { apps.mapTo(HashSet()) { it.pkg } }
        val selected = apps.filter { it.bypass }.mapTo(HashSet()) { it.pkg }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AmeliaApps.Preset.entries.forEach { preset ->
                val inSet = AmeliaApps.presetInstalled(preset, installed)
                FilterChip(
                    selected = inSet.isNotEmpty() && selected.containsAll(inSet),
                    enabled = inSet.isNotEmpty(),
                    onClick = { onPreset(preset) },
                    label = { Text(stringResource(presetLabels.getValue(preset))) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = Brand.Text,
                        selectedContainerColor = Brand.Sky,
                        selectedLabelColor = Brand.Night,
                        disabledLabelColor = Brand.Muted,
                    ),
                )
            }
        }
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
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        val q = query.trim()
        val shown = apps.filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) }
        LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            items(shown, key = { it.pkg }) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Switch) { onToggle(app.pkg, !app.bypass) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app.pkg)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        app.label,
                        color = Brand.Text,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = app.bypass,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = Brand.Night, checkedTrackColor = Brand.Sky),
                    )
                }
            }
        }
    }
}

/** Launcher icons, loaded off the main thread and kept while the app runs. */
private object AppIcons {
    private const val SIZE_PX = 96
    private val cache = LruCache<String, ImageBitmap>(300)

    fun cached(pkg: String): ImageBitmap? = cache.get(pkg)

    fun load(context: android.content.Context, pkg: String): ImageBitmap? = cache.get(pkg) ?: try {
        context.packageManager.getApplicationIcon(pkg).toBitmap(SIZE_PX, SIZE_PX).asImageBitmap().also { cache.put(pkg, it) }
    } catch (e: Exception) {
        LogUtil.w(AppConfig.TAG, "Amelia: no icon for $pkg", e)
        null
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val context = LocalContext.current.applicationContext
    val icon by produceState(AppIcons.cached(pkg), pkg) {
        if (value == null) value = withContext(Dispatchers.IO) { AppIcons.load(context, pkg) }
    }
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}
