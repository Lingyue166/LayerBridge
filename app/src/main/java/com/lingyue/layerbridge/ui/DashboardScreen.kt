package com.lingyue.layerbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lingyue.layerbridge.R
import com.lingyue.layerbridge.core.LbPaths
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DashboardPage(
    state: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SystemCard(state)
        InstalledCard(state)
        SourceCard()
        ActivityCard(state.log)
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { viewModel.refreshInstalled() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) {
            Text(stringResource(R.string.dash_refresh))
        }
    }
}
@Composable
private fun SystemCard(state: UiState) {
    val runtime = state.runtime
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.dash_runtime), style = MiuixTheme.textStyles.title3)
            InfoRow(
                label = stringResource(R.string.dash_root),
                value = when {
                    state.root == null -> stringResource(R.string.dash_checking)
                    state.root?.granted == true -> stringResource(R.string.dash_granted)
                    else -> stringResource(R.string.dash_denied)
                },
            )
            InfoRow(label = stringResource(R.string.dash_prefix), value = LbPaths.PREFIX)
            InfoRow(label = stringResource(R.string.dash_module), value = flag(runtime?.module))
            InfoRow(label = stringResource(R.string.dash_entry), value = flag(runtime?.entry))
            InfoRow(label = stringResource(R.string.dash_db), value = flag(runtime?.db))
            InfoRow(label = stringResource(R.string.dash_app), value = runtime?.appVersion ?: "-")
            InfoRow(label = stringResource(R.string.dash_android), value = runtime?.android ?: "-")
        }
    }
}
@Composable
private fun flag(value: Boolean?): String = when (value) {
    null -> stringResource(R.string.dash_checking)
    true -> stringResource(R.string.dash_ok)
    false -> stringResource(R.string.dash_missing)
}
@Composable
private fun InstalledCard(state: UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.dash_packages), style = MiuixTheme.textStyles.title3)
            InfoRow(
                label = stringResource(R.string.dash_installed),
                value = state.installed.size.toString(),
            )
            state.message.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onBackgroundVariant)
            }
        }
    }
}
@Composable
private fun SourceCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.dash_source), style = MiuixTheme.textStyles.title3)
            Text(
                stringResource(R.string.dash_source_none),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
    }
}
@Composable
private fun ActivityCard(log: String) {
    val tail = remember(log) {
        log.lineSequence().filter { it.isNotBlank() }.toList().takeLast(6).joinToString("\n")
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.dash_activity), style = MiuixTheme.textStyles.title3)
            if (tail.isBlank()) {
                Text(
                    stringResource(R.string.dash_no_activity),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            } else {
                Text(tail, style = MiuixTheme.textStyles.footnote2)
            }
        }
    }
}
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Text(value, style = MiuixTheme.textStyles.body2)
    }
}
