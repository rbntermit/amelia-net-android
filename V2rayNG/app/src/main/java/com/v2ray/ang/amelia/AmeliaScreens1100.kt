package com.v2ray.ang.amelia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R

/**
 * 1.10.0: the rest of the "🩺 Не работает?" window after "Что видно снаружи": "Проверка сервисов",
 * "Сайт не открывается" and "Как у меня".
 */
@Composable
internal fun HelpSections(state: AmeliaUiState, extra: ExtraCallbacks, onConnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = Brand.GlassBorder)
        ServicesSection(state.services, onCheck = extra.onCheckServices, onConnect = onConnect)
        HorizontalDivider(color = Brand.GlassBorder)
        SiteSection(state.siteInput, state.siteSending, state.siteOutcome, onSend = extra.onSendSite, onEdited = extra.onSiteEdited)
        HorizontalDivider(color = Brand.GlassBorder)
        MineCard(state)
    }
}

// ---- "Проверка сервисов" ----------------------------------------------------------------------

@Composable
private fun ServicesSection(services: ServicesUi?, onCheck: () -> Unit, onConnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onCheck, enabled = services?.running != true) {
            Text(stringResource(R.string.amelia_services_button), color = Brand.Sky)
        }
        when {
            services == null -> Unit
            services.vpnOff -> {
                Text(stringResource(R.string.amelia_services_vpn_off), color = Brand.Amber, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onConnect) { Text(stringResource(R.string.amelia_services_connect), color = Brand.Sky) }
            }
            services.running -> {
                Text(stringResource(R.string.amelia_services_running), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
                CircularProgressIndicator(Modifier.padding(4.dp), color = Brand.Sky)
            }
            else -> services.results.forEach { r ->
                val (text, color) = when (r.status) {
                    AmeliaServices.Status.OK -> stringResource(R.string.amelia_services_ok, r.name) to Brand.Green
                    AmeliaServices.Status.FAIL -> stringResource(R.string.amelia_services_fail, r.name) to Brand.Amber
                    AmeliaServices.Status.NOT_IN_COUNTRY -> stringResource(R.string.amelia_services_gemini_country) to Brand.Amber
                }
                Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---- "Сайт не открывается" --------------------------------------------------------------------

/**
 * 1.10.1: the field lives in the screen's state, so "Отправить в поддержку" takes the site too; the answer is
 * right under the field, and the button says what it does ("Проверить этот сайт").
 */
@Composable
private fun SiteSection(
    site: String,
    sending: Boolean,
    outcome: AmeliaSiteCheck.Outcome?,
    onSend: (String) -> Unit,
    onEdited: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.amelia_site_title), color = Brand.Text, style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = site,
            onValueChange = onEdited,
            label = { Text(stringResource(R.string.amelia_site_hint)) },
            singleLine = true,
            enabled = !sending,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (site.isNotBlank()) onSend(site) }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Sky, focusedLabelColor = Brand.Sky, cursorColor = Brand.Sky),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        outcome?.let {
            val (text, color) = when (it) {
                AmeliaSiteCheck.Outcome.SENT -> stringResource(R.string.amelia_site_sent) to Brand.Green
                AmeliaSiteCheck.Outcome.NOT_A_SITE -> stringResource(R.string.amelia_site_bad) to Brand.Amber
                AmeliaSiteCheck.Outcome.FAILED -> stringResource(R.string.amelia_site_failed) to Brand.Amber
            }
            Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
        }
        if (sending) {
            CircularProgressIndicator(Modifier.padding(4.dp), color = Brand.Sky)
        } else {
            TextButton(onClick = { onSend(site) }, enabled = site.isNotBlank()) {
                Text(stringResource(R.string.amelia_site_check), color = Brand.Sky)
            }
        }
    }
}

// ---- "Как у меня" -----------------------------------------------------------------------------

/** The subscription's end, the location and its delay, the last check of every location, the version. */
@Composable
internal fun MineCard(state: AmeliaUiState) {
    val small = MaterialTheme.typography.bodySmall
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(R.string.amelia_mine_title), color = Brand.Text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        // no end date in the subscription (or never ending): no line
        state.info?.takeIf { it.hasExpire }?.expire?.let {
            Text(stringResource(R.string.amelia_mine_until, AmeliaFormat.date(it)), color = Brand.Text, style = small)
        }
        state.selectedName?.let { name ->
            val ping = state.pingMs?.takeIf { state.phase == ConnPhase.CONNECTED }
            Text(
                if (ping != null) stringResource(R.string.amelia_mine_location_ping, name, ping.toInt())
                else stringResource(R.string.amelia_mine_location, name),
                color = Brand.Text, style = small,
            )
        }
        val s = state.checkSummary
        Text(
            if (s == null) stringResource(R.string.amelia_mine_check_never)
            else stringResource(R.string.amelia_mine_check, AmeliaFormat.dateTime(s.at), s.working, s.checked),
            color = Brand.Text, style = small,
        )
        Text(stringResource(R.string.amelia_mine_version, BuildConfig.VERSION_NAME), color = Brand.Muted, style = small)
    }
}

/** Long press on the main button: "Как у меня" on its own. */
@Composable
internal fun MineDialog(state: AmeliaUiState, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        text = { MineCard(state) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.amelia_close)) } },
    )
}

// ---- "Российские сайты — напрямую" ------------------------------------------------------------

/** Next to "Российские приложения — мимо VPN" (only where the geo bases are there). */
@Composable
internal fun RuDirectBlock(on: Boolean, onChange: (Boolean) -> Unit) {
    Glass(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SwitchRow(stringResource(R.string.amelia_rudirect_title), on, onChange)
            Text(stringResource(R.string.amelia_rudirect_note), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
