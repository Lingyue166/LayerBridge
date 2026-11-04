package com.lingyue.layerbridge.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lingyue.layerbridge.R
import com.lingyue.layerbridge.core.LbPaths
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 首页：按 KernelSU 风格模板的卡片结构组织 只用模板已有的控件 */
@Composable
fun HomePage(
    state: UiState,
    log: String,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PermissionCard(state) }
        item { InfoCard(state) }
        item { LearnMoreCard() }
        item { ActivityCard(log) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PermissionCard(state: UiState) {
    val granted = state.root?.granted == true
    val manager = state.root?.managers.orEmpty().joinToString(", ") { it.name }
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (granted) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (granted) R.string.home_root_granted else R.string.home_root_denied),
                    style = MiuixTheme.textStyles.title3,
                )
                Text(
                    if (granted) {
                        stringResource(R.string.home_root_manager, manager.ifBlank { "root" })
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
            RowInfo(Icons.Default.Info, stringResource(R.string.home_packages_title), "${state.installed.size}")
            RowInfo(Icons.Default.Info, stringResource(R.string.dash_android), runtime?.android ?: "-")
            RowInfo(
                Icons.Default.Info,
                stringResource(R.string.dash_device),
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
            )
            RowInfo(Icons.Default.Info, stringResource(R.string.dash_app), runtime?.appVersion ?: "0.1.0")
            RowInfo(Icons.Default.Info, stringResource(R.string.home_prefix), LbPaths.PREFIX)
        }
    }
}

@Composable
private fun RowInfo(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.weight(1f),
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
            Text(
                if (tail.isBlank()) stringResource(R.string.dash_no_activity) else tail,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
    }
}
