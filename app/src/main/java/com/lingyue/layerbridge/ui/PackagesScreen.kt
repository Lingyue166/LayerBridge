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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.lingyue.layerbridge.R
import com.lingyue.layerbridge.state.InstalledPackage
import com.lingyue.layerbridge.ui.components.CardActionButton
import com.lingyue.layerbridge.ui.components.InfoRow
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PackagesPage(
    state: UiState,
    viewModel: MainViewModel,
    graphMode: Boolean,
    modifier: Modifier = Modifier,
) {
    if (graphMode) {
        Column(modifier = modifier.fillMaxSize()) {
            TextField(
                value = state.query,
                onValueChange = { viewModel.setQuery(it.replace("\n", "")) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                label = stringResource(R.string.pkg_search_hint),
                useLabelAsPlaceholder = true,
                cornerRadius = 24.dp,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            DependencyGraph(state, viewModel, Modifier.weight(1f))
        }
        return
    }
    val metrics = rememberPackageCardMetrics()
    val packages = remember(state.installed, state.query) {
        val query = state.query.trim()
        if (query.isEmpty()) state.installed.sortedBy { it.name }
        else state.installed.filter { it.name.contains(query, ignoreCase = true) }.sortedBy { it.name }
    }
    var refreshing by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()
    var detail by remember { mutableStateOf<InstalledPackage?>(null) }
    var pendingRemove by remember { mutableStateOf<InstalledPackage?>(null) }
    val openDetail = remember { { pkg: InstalledPackage -> detail = pkg } }
    val removeRequest = remember { { pkg: InstalledPackage -> pendingRemove = pkg } }

    Column(modifier = modifier.fillMaxSize()) {
        TextField(
            value = state.query,
            onValueChange = { viewModel.setQuery(it.replace("\n", "")) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            label = stringResource(R.string.pkg_search_hint),
            useLabelAsPlaceholder = true,
            cornerRadius = 24.dp,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
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
            PullToRefresh(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    viewModel.refreshInstalled { refreshing = false }
                },
                modifier = Modifier.fillMaxSize(),
                pullToRefreshState = pullState,
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = metrics.margin,
                        end = metrics.margin,
                        top = metrics.margin,
                        bottom = metrics.margin + 80.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(metrics.gap),
                    horizontalArrangement = Arrangement.spacedBy(metrics.gap),
                ) {
                    items(packages, key = { it.name }) { pkg ->
                        PackageCard(
                            pkg = pkg,
                            metrics = metrics,
                            query = state.query,
                            onClick = { openDetail(pkg) },
                            onRemove = { removeRequest(pkg) },
                        )
                    }
                }
            }
        }
    }

    PackageDialogs(detail, pendingRemove, viewModel) {
        detail = null
        pendingRemove = null
    }
}

@Composable
internal fun PackageDialogs(
    detail: InstalledPackage?,
    pendingRemove: InstalledPackage?,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    detail?.let { pkg ->
        OverlayBottomSheet(
            show = true,
            title = pkg.name,
            onDismissRequest = onDismiss,
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
            onDismissRequest = onDismiss,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.pkg_uninstall_message, pkg.name),
                    style = MiuixTheme.textStyles.body2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = stringResource(R.string.pkg_cancel),
                        onClick = onDismiss,
                    )
                    Button(
                        onClick = {
                            viewModel.uninstall(pkg.name)
                            onDismiss()
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PackageCard(
    pkg: InstalledPackage,
    metrics: PackageCardMetrics,
    query: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    highlighted: Boolean = false,
    dimmed: Boolean = false,
    onDoubleTap: (() -> Unit)? = null,
) {
    val baseModifier = Modifier
        .width(metrics.cardWidth)
        .height(metrics.cardHeight)
        .alpha(if (dimmed) 0.3f else 1f)
        .then(
            if (highlighted) {
                Modifier.border(
                    2.dp,
                    MiuixTheme.colorScheme.primary,
                    androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                )
            } else Modifier,
        )
    val content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.infoHeight)
                    .padding(horizontal = metrics.padding, vertical = metrics.padding),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    highlight(pkg.name, query),
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
    if (onDoubleTap != null) {
        Card(
            modifier = baseModifier.combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleTap,
            ),
            content = content,
        )
    } else {
        Card(
            onClick = onClick,
            modifier = baseModifier,
            content = content,
        )
    }
}

/** 搜索高亮：包含匹配的关键字加粗并着色 */
@Composable
internal fun highlight(text: String, query: String): AnnotatedString {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return AnnotatedString(text)
    val color = MiuixTheme.colorScheme.primary
    return buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val match = text.indexOf(trimmed, index, ignoreCase = true)
            if (match < 0) {
                append(text.substring(index))
                break
            }
            append(text.substring(index, match))
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                append(text.substring(match, match + trimmed.length))
            }
            index = match + trimmed.length
        }
    }
}
