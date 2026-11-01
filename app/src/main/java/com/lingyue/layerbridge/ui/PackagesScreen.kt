package com.lingyue.layerbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingyue.layerbridge.R
import com.lingyue.layerbridge.state.InstalledPackage
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PackagesPage(state: UiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val packages = remember(state.installed, state.query) {
        val query = state.query.trim()
        if (query.isEmpty()) state.installed.sortedBy { it.name }
        else state.installed
            .filter { it.name.contains(query, ignoreCase = true) }
            .sortedBy { it.name }
    }
    val widthDp = LocalConfiguration.current.screenWidthDp.dp
    val margin = 16.dp
    val gap = if (widthDp < 360.dp) 8.dp else 12.dp
    val cardWidth = (widthDp - margin * 2 - gap) / 2
    // 字号随屏宽缩放，行高与间距由此推导，避免文字溢出与互相碰撞
    val scale = (widthDp.value / 411f).coerceIn(0.9f, 1.0f)
    val nameSp = (16f * scale).sp
    val metaSp = (12f * scale).sp
    val buttonSp = (13f * scale).sp
    val density = LocalDensity.current
    val nameLine = with(density) { (nameSp * 1.5f).toDp() }
    val metaLine = with(density) { (metaSp * 1.5f).toDp() }
    val cardPadding = 12.dp
    val infoHeight = cardPadding * 2 + nameLine + 4.dp + metaLine
    val actionHeight = 62.dp
    val buttonWidth = ((cardWidth - cardPadding * 2 - 10.dp) / 2).coerceAtLeast(64.dp)
    var detail by remember { mutableStateOf<InstalledPackage?>(null) }
    var pendingRemove by remember { mutableStateOf<InstalledPackage?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        TextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
                contentPadding = PaddingValues(margin),
                verticalItemSpacing = 12.dp,
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                items(packages, key = { it.name }) { pkg ->
                    PackageCard(
                        pkg = pkg,
                        width = cardWidth,
                        cardHeight = infoHeight + actionHeight + 1.dp,
                        infoHeight = infoHeight,
                        actionHeight = actionHeight,
                        nameSize = nameSp,
                        metaSize = metaSp,
                        nameLine = nameLine,
                        metaLine = metaLine,
                        buttonWidth = buttonWidth,
                        padding = cardPadding,
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
                DetailRow(stringResource(R.string.pkg_version), pkg.version)
                DetailRow(stringResource(R.string.pkg_arch), pkg.architecture)
                DetailRow(stringResource(R.string.pkg_format), pkg.format)
                DetailRow(stringResource(R.string.pkg_installed_at), pkg.installedAt)
                DetailRow(stringResource(R.string.pkg_signer), pkg.signer ?: "-")
                DetailRow(stringResource(R.string.pkg_scan), pkg.scan)
                pkg.relations.depends.takeIf { !it.isNullOrBlank() }?.let {
                    DetailRow(stringResource(R.string.pkg_depends), it)
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
    width: Dp,
    cardHeight: Dp,
    infoHeight: Dp,
    actionHeight: Dp,
    nameSize: androidx.compose.ui.unit.TextUnit,
    metaSize: androidx.compose.ui.unit.TextUnit,
    nameLine: Dp,
    metaLine: Dp,
    buttonWidth: Dp,
    padding: Dp,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(width).height(cardHeight),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(infoHeight)
                    .padding(horizontal = padding, vertical = padding),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    pkg.name,
                    style = MiuixTheme.textStyles.title3.copy(fontSize = nameSize),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${pkg.version} · ${pkg.architecture}",
                    style = MiuixTheme.textStyles.footnote2.copy(fontSize = metaSize),
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = padding),
                color = MiuixTheme.colorScheme.dividerLine,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(actionHeight)
                    .padding(horizontal = padding),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.pkg_details),
                    onClick = onClick,
                    modifier = Modifier.width(buttonWidth).height(44.dp),
                    minWidth = 0.dp,
                    minHeight = 0.dp,
                    insideMargin = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                )
                TextButton(
                    text = stringResource(R.string.pkg_uninstall),
                    onClick = onRemove,
                    modifier = Modifier.width(buttonWidth).height(44.dp),
                    minWidth = 0.dp,
                    minHeight = 0.dp,
                    insideMargin = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Text(value, style = MiuixTheme.textStyles.body2)
    }
}
