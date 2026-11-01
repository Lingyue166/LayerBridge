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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingyue.layerbridge.R
import com.lingyue.layerbridge.state.InstalledPackage
import com.lingyue.layerbridge.ui.components.CardActionButton
import com.lingyue.layerbridge.ui.components.InfoRow
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PackagesPage(state: UiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val metrics = rememberPackageCardMetrics()
    val packages = remember(state.installed, state.query) {
        val query = state.query.trim()
        if (query.isEmpty()) state.installed.sortedBy { it.name }
        else state.installed.filter { it.name.contains(query, ignoreCase = true) }.sortedBy { it.name }
    }
    var detail by remember { mutableStateOf<InstalledPackage?>(null) }
    var pendingRemove by remember { mutableStateOf<InstalledPackage?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        TextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            label = stringResource(R.string.pkg_search_hint),
            useLabelAsPlaceholder = true,
        )
        if (packages.isEmpty()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.pkg_empty),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(metrics.margin),
                verticalItemSpacing = metrics.gap,
                horizontalArrangement = Arrangement.spacedBy(metrics.gap),
            ) {
                items(packages, key = { it.name }) { pkg ->
                    PackageCard(
                        pkg = pkg,
                        metrics = metrics,
                        onClick = { detail = pkg },
                        onRemove = { pendingRemove = pkg },
                    )
                }
            }
        }
    }

    detail?.let { pkg ->
        OverlayBottomSheet(
            show = true,
            title = pkg.name,
            onDismissRequest = { detail = null },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow(stringResource(R.string.pkg_version), pkg.version)
                InfoRow(stringResource(R.string.pkg_arch), pkg.architecture)
                InfoRow(stringResource(R.string.pkg_format), pkg.format)
                InfoRow(stringResource(R.string.pkg_installed_at), pkg.installedAt)
                InfoRow(stringResource(R.string.pkg_signer), pkg.signer ?: "-")
                InfoRow(stringResource(R.string.pkg_scan), pkg.scan)
                pkg.relations.depends.takeIf { !it.isNullOrBlank() }?.let {
                    InfoRow(stringResource(R.string.pkg_depends), it)
                }
            }
        }
    }

    pendingRemove?.let { pkg ->
        OverlayBottomSheet(
            show = true,
            title = stringResource(R.string.pkg_uninstall_title),
            onDismissRequest = { pendingRemove = null },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.pkg_uninstall_message, pkg.name),
                    style = MiuixTheme.textStyles.body2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = stringResource(R.string.pkg_cancel),
                        onClick = { pendingRemove = null },
                    )
                    Button(
                        onClick = {
                            viewModel.uninstall(pkg.name)
                            pendingRemove = null
                        },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(stringResource(R.string.pkg_confirm))
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageCard(
    pkg: InstalledPackage,
    metrics: PackageCardMetrics,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(metrics.cardWidth).height(metrics.cardHeight),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.infoHeight)
                    .padding(horizontal = metrics.padding, vertical = metrics.padding),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    pkg.name,
                    style = MiuixTheme.textStyles.title3.copy(fontSize = metrics.nameSize),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${pkg.version} · ${pkg.architecture}",
                    style = MiuixTheme.textStyles.footnote2.copy(fontSize = metrics.metaSize),
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = metrics.padding),
                color = MiuixTheme.colorScheme.dividerLine,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.actionHeight)
                    .padding(horizontal = metrics.padding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CardActionButton(
                    text = stringResource(R.string.pkg_details),
                    onClick = onClick,
                    width = metrics.buttonWidth,
                    height = metrics.buttonHeight,
                )
                CardActionButton(
                    text = stringResource(R.string.pkg_uninstall),
                    onClick = onRemove,
                    width = metrics.buttonWidth,
                    height = metrics.buttonHeight,
                )
            }
        }
    }
}
