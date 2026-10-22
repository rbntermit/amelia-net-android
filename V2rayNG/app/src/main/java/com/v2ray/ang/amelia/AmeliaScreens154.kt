package com.v2ray.ang.amelia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

/** Settings → "Включать VPN при запуске телефона" and the way to Android's Always-on VPN. */
@Composable
internal fun SettingsBootSection(state: AmeliaUiState, extra: ExtraCallbacks) {
    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(stringResource(R.string.amelia_boot_title), state.bootEnabled, extra.onStartOnBoot)
            if (AmeliaBoot.showPermissionHint(state.bootEnabled, state.vpnPermission)) {
                Text(stringResource(R.string.amelia_boot_need_permission), color = Brand.Amber, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.amelia_boot_always_on), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = extra.onOpenVpnSettings) {
                Text(stringResource(R.string.amelia_boot_open_vpn_settings), color = Brand.Sky)
            }
        }
    }
}

/** Main screen: the VPN cannot come up at boot until the person connected once (Android asks then). */
@Composable
internal fun BootPermissionLine(state: AmeliaUiState) {
    if (!AmeliaBoot.showPermissionHint(state.bootEnabled, state.vpnPermission)) return
    Text(
        stringResource(R.string.amelia_boot_need_permission),
        color = Brand.Muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    )
}
