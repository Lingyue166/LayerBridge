package com.lingyue.layerbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lingyue.layerbridge.R
import com.lingyue.layerbridge.core.LbPaths
import com.lingyue.layerbridge.ui.components.InfoRow
import com.lingyue.layerbridge.ui.components.SectionCard
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DashboardPage(
    state: UiState,
    log: String,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SystemCard(state)
        InstalledCard(state)
        SourceCard()
        ActivityCard(log)
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
    SectionCard(stringResource(R.string.dash_runtime)) {
        InfoRow(
            label = stringResource(R.string.dash_root),
            value = when {
                state.root == null -> stringResource(R.string.dash_checking)
                state.root?.granted == true -> stringResource(R.string.dash_granted)
                else -> stringResource(R.string.dash_denied)
            },
            spread = true,
        )
        InfoRow(stringResource(R.string.dash_prefix), LbPaths.PREFIX, spread = true)
        InfoRow(stringResource(R.string.dash_module), flag(runtime?.module), spread = true)
        InfoRow(stringResource(R.string.dash_entry), flag(runtime?.entry), spread = true)
        InfoRow(stringResource(R.string.dash_db), flag(runtime?.db), spread = true)
        InfoRow(stringResource(R.string.dash_app), runtime?.appVersion ?: "-", spread = true)
        InfoRow(stringResource(R.string.dash_android), runtime?.android ?: "-", spread = true)
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
    SectionCard(stringResource(R.string.dash_packages)) {
        InfoRow(
            label = stringResource(R.string.dash_installed),
            value = state.installed.size.toString(),
            spread = true,
        )
        state.message.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onBackgroundVariant)
        }
    }
}

@Composable
private fun SourceCard() {
    SectionCard(stringResource(R.string.dash_source)) {
        Text(
            stringResource(R.string.dash_source_none),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

@Composable
private fun ActivityCard(log: String) {
    val tail = remember(log) {
        log.lineSequence().filter { it.isNotBlank() }.toList().takeLast(6).joinToString("\n")
    }
    SectionCard(stringResource(R.string.dash_activity)) {
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
