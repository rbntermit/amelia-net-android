package com.v2ray.ang.amelia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

// ---- "Что видно снаружи" in the "🩺 Не работает?" window ----------------------------------------

/** The button, then what /app/whoami said and what the VPN does with IPv6 and DNS by its settings. */
@Composable
internal fun OutsideSection(outside: OutsideUi?, onCheck: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onCheck, enabled = outside?.loading != true) {
            Text(stringResource(R.string.amelia_outside_button), color = Brand.Sky)
        }
        when {
            outside == null -> Unit
            outside.loading -> CircularProgressIndicator(Modifier.padding(4.dp), color = Brand.Sky)
            else -> {
                outside.verdict?.let { OutsideVerdict(it) }
                outside.local?.let { OutsideLocal(it) }
            }
        }
    }
}

@Composable
private fun OutsideVerdict(v: AmeliaWhoami.Verdict) {
    val (text, color) = when (v.kind) {
        AmeliaWhoami.Kind.ABROAD ->
            stringResource(R.string.amelia_outside_abroad, v.country ?: stringResource(R.string.amelia_outside_unknown_country)) to Brand.Green
        AmeliaWhoami.Kind.OUR_RUSSIAN -> stringResource(R.string.amelia_outside_our_russian) to Brand.Green
        AmeliaWhoami.Kind.LEAK ->
            (v.operator?.let { stringResource(R.string.amelia_outside_leak_op, it) } ?: stringResource(R.string.amelia_outside_leak)) to Brand.Amber
        AmeliaWhoami.Kind.VPN_OFF -> stringResource(R.string.amelia_outside_vpn_off, AmeliaWhoami.seenAs(v)) to Brand.Text
        AmeliaWhoami.Kind.ROUTER ->
            stringResource(R.string.amelia_outside_router, v.country ?: stringResource(R.string.amelia_outside_unknown_country)) to Brand.Green
        AmeliaWhoami.Kind.UNKNOWN -> stringResource(R.string.amelia_outside_unknown) to Brand.Muted
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    // the address for the kinds where the line above has no place for it
    if (v.ip != null && v.kind in setOf(AmeliaWhoami.Kind.ABROAD, AmeliaWhoami.Kind.OUR_RUSSIAN, AmeliaWhoami.Kind.ROUTER)) {
        Text(stringResource(R.string.amelia_outside_ip, v.ip), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OutsideLocal(l: AmeliaWhoami.Local) {
    val small = MaterialTheme.typography.bodySmall
    Text(
        stringResource(if (l.ipv6InTunnel) R.string.amelia_outside_ipv6_tunnel else R.string.amelia_outside_ipv6_blocked),
        color = Brand.Text, style = small,
    )
    Text(
        stringResource(if (l.dnsInTunnel) R.string.amelia_outside_dns_tunnel else R.string.amelia_outside_dns_direct),
        color = if (l.dnsInTunnel) Brand.Text else Brand.Amber, style = small,
    )
    Text(stringResource(R.string.amelia_outside_dns_note), color = Brand.Muted, style = small)
}

// ---- "Российские приложения — мимо VPN" ---------------------------------------------------------

/** On top of "Приложения мимо VPN": the switch, what it means, and which apps it added. */
@Composable
internal fun RuAppsBlock(on: Boolean, addedLabels: List<String>, onChange: (Boolean) -> Unit) {
    Glass(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SwitchRow(stringResource(R.string.amelia_ruapps_title), on, onChange)
            Text(stringResource(R.string.amelia_ruapps_note), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            if (on) {
                Text(
                    if (addedLabels.isEmpty()) stringResource(R.string.amelia_ruapps_none)
                    else stringResource(R.string.amelia_ruapps_added, addedLabels.joinToString(", ")),
                    color = Brand.Text, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
