package com.v2ray.ang.amelia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

/** Hosted inside the settings' scrollable, inset-aware form; all writes go through its ViewModel. */
@Composable
internal fun SiteRulesSection(
    state: AmeliaUiState,
    onSet: (String, Boolean?) -> Unit,
    onReload: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.amelia_sites_title))
            Text(stringResource(R.string.amelia_sites_note), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.amelia_site_hint)) },
                singleLine = true,
                enabled = state.siteRulesReady && !state.siteRulesBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Brand.Sky, focusedLabelColor = Brand.Sky, cursorColor = Brand.Sky,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { onSet(input, true) },
                enabled = input.isNotBlank() && state.siteRulesReady && !state.siteRulesBusy,
            ) { Text(stringResource(R.string.amelia_sites_vpn), color = Brand.Sky) }
            TextButton(
                onClick = { onSet(input, false) },
                enabled = input.isNotBlank() && state.siteRulesReady && !state.siteRulesBusy,
            ) { Text(stringResource(R.string.amelia_sites_direct), color = Brand.Sky) }
            state.siteRulesError?.let { Text(stringResource(it), color = Brand.Amber) }
            if (state.siteRulesBusy) {
                CircularProgressIndicator(Modifier.padding(4.dp), color = Brand.Sky)
            } else if (!state.siteRulesReady) {
                TextButton(onClick = onReload) { Text(stringResource(R.string.amelia_sites_retry), color = Brand.Sky) }
            } else if (state.siteRules.isEmpty()) {
                Text(stringResource(R.string.amelia_sites_empty), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            }
            state.siteRules.forEach { rule ->
                key(rule.domain) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(
                                if (rule.viaVpn) R.string.amelia_sites_rule_vpn else R.string.amelia_sites_rule_direct,
                                rule.domain,
                            ),
                            color = Brand.Text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { onSet(rule.domain, null) }, enabled = !state.siteRulesBusy) {
                            Text(stringResource(R.string.amelia_sites_delete, rule.domain), color = Brand.Sky)
                        }
                    }
                }
            }
        }
    }
}
