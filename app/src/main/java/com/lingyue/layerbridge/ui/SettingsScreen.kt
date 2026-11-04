package com.lingyue.layerbridge.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lingyue.layerbridge.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsPage(state: UiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.settings_security),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
        )
        Card(Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = stringResource(R.string.settings_signature),
                summary = stringResource(R.string.settings_signature_summary),
                checked = state.requireSignature,
                onCheckedChange = viewModel::setRequireSignature,
            )
            SwitchPreference(
                title = stringResource(R.string.settings_malware),
                summary = stringResource(R.string.settings_malware_summary),
                checked = state.malwareScan,
                onCheckedChange = viewModel::setMalwareScan,
            )
        }

        Text(
            stringResource(R.string.settings_maintenance),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = stringResource(R.string.settings_repair_env),
                summary = stringResource(R.string.settings_repair_env_summary),
                onClick = { viewModel.repairEnvironment() },
            )
            ArrowPreference(
                title = stringResource(R.string.settings_refresh),
                summary = stringResource(R.string.settings_refresh_summary),
                onClick = {
                    viewModel.refreshInstalled()
                    viewModel.refreshRuntime()
                },
            )
        }

        Text(
            stringResource(R.string.settings_about),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = "LayerBridge",
                summary = "0.1.0",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Lingyue166/LayerBridge")),
                        )
                    }
                },
            )
        }
        Text(
            stringResource(R.string.settings_version_note),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 24.dp),
        )
    }
}
