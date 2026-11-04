package com.lingyue.layerbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lingyue.layerbridge.R
import com.lingyue.layerbridge.core.LbPaths
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomePage(
    state: UiState,
    log: String,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onOpenPackages: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(state) }
        item { PackageCard(state, onOpenPackages) }
        item { InfoCard(state) }
        item { LearnMoreCard() }
        item { ActivityCard(log) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StatusCard(state: UiState) {
    val granted = state.root?.granted == true
    val managers = state.root?.managers.orEmpty().joinToString(", ") { it.name }
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (granted) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                    modifier = Modifier.size(30.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (granted) R.string.home_root_granted else R.string.home_root_denied),
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (granted) {
                        stringResource(R.string.home_root_manager, managers.ifBlank { "root" })
                    } else {
                        stringResource(R.string.home_root_summary)
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
    }
}

@Composable
private fun PackageCard(state: UiState, onOpenPackages: () -> Unit) {
    Card(onClick = onOpenPackages, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_packages_title), style = MiuixTheme.textStyles.title3)
                Text(
                    stringResource(R.string.home_packages_summary, state.installed.size),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
            Text(
                stringResource(R.string.home_open),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun InfoCard(state: UiState) {
    val runtime = state.runtime
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.home_info),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            InfoLine(stringResource(R.string.dash_android), runtime?.android ?: "-")
            InfoLine(
                stringResource(R.string.dash_device),
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
            )
            InfoLine(stringResource(R.string.dash_app), runtime?.appVersion ?: "0.1.0")
            InfoLine(stringResource(R.string.home_prefix), LbPaths.PREFIX)
            InfoLine(stringResource(R.string.home_module), status(runtime?.module))
            InfoLine(stringResource(R.string.home_entry), status(runtime?.entry))
            InfoLine(stringResource(R.string.home_db), status(runtime?.db))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
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

@Composable
private fun LearnMoreCard() {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.dash_project),
            summary = stringResource(R.string.dash_project_summary),
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/Lingyue166/LayerBridge"),
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun ActivityCard(log: String) {
    val tail = remember(log) {
        log.lineSequence().filter { it.isNotBlank() }.toList().takeLast(6).joinToString("\n")
    }
    Card(Modifier.fillMaxWidth()) {
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
private fun status(value: Boolean?): String = when (value) {
    null -> stringResource(R.string.dash_checking)
    true -> stringResource(R.string.dash_ok)
    false -> stringResource(R.string.dash_missing)
}
