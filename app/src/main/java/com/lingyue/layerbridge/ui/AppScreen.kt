package com.lingyue.layerbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lingyue.layerbridge.repo.RepoPackage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerBridgeApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("LayerBridge") },
                actions = {
                    TextButton(onClick = { viewModel.updateRepo() }) { Text("更新索引") }
                    TextButton(onClick = { viewModel.bootstrap() }) { Text("初始化") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("包") },
                    label = { Text("软件包") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text("环") },
                    label = { Text("环境") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> PackagesScreen(state, viewModel, Modifier.padding(padding))
            else -> EnvironmentScreen(state, viewModel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun PackagesScreen(state: UiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val filtered = remember(state.packages, state.query) {
        val query = state.query.trim()
        val source = if (query.isEmpty()) state.packages else state.packages.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.description.orEmpty().contains(query, ignoreCase = true)
        }
        source.sortedBy { it.name }.take(300)
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索包名 / 描述") },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.message.ifEmpty {
                "索引 ${state.packages.size} · 已安装 ${state.installed.size}" +
                    if (state.repoVerified) " · 索引已验签" else ""
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.loading || state.installing != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filtered, key = { it.name }) { pkg ->
                PackageRow(
                    pkg = pkg,
                    installedVersion = state.installedNames[pkg.name]?.version,
                    installing = state.installing == pkg.name,
                    onInstall = { viewModel.install(pkg.name) },
                    onUninstall = { viewModel.uninstall(pkg.name) },
                )
                HorizontalDivider()
            }
        }
        LogPanel(state.log, Modifier.height(180.dp))
    }
}

@Composable
private fun PackageRow(
    pkg: RepoPackage,
    installedVersion: String?,
    installing: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pkg.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${pkg.version} · ${pkg.architecture}" +
                    if (installedVersion != null) " · 已安装" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            pkg.description?.lineSequence()?.firstOrNull()?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            installing -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            installedVersion != null -> TextButton(onClick = onUninstall) { Text("卸载") }
            else -> Button(onClick = onInstall) { Text("安装") }
        }
    }
}

@Composable
private fun EnvironmentScreen(state: UiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Root", style = MaterialTheme.typography.titleMedium)
                val root = state.root
                Text(
                    when {
                        root == null -> "检测中…"
                        root.granted -> "已授权" + if (root.managers.isEmpty()) "" else "（" +
                            root.managers.joinToString { "${it.name} ${it.version.orEmpty()}" } + "）"
                        root.managers.isEmpty() -> "未检测到 root 管理器"
                        else -> "未授权：请在 " + root.managers.joinToString { it.name } + " 中授权"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("环境", style = MaterialTheme.typography.titleMedium)
                Text("前缀：/data/adb/lb/usr", style = MaterialTheme.typography.bodySmall)
                Text("已安装：${state.installed.size} 个包", style = MaterialTheme.typography.bodySmall)
                Text(
                    "索引：" + if (state.repoVerified) "已验签" else "未验签",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.bootstrap() }) { Text("初始化前缀") }
                    Button(onClick = { viewModel.updateRepo() }) { Text("更新索引") }
                    TextButton(onClick = { viewModel.refreshInstalled() }) { Text("刷新") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("开发者选项", style = MaterialTheme.typography.titleMedium)
                SwitchRow(
                    title = "强制授权签名",
                    checked = state.requireSignature,
                    onCheckedChange = viewModel::setRequireSignature,
                )
                SwitchRow(
                    title = "自动恶意检测",
                    checked = state.malwareScan,
                    onCheckedChange = viewModel::setMalwareScan,
                )
                Text(
                    "关闭后仍执行结构校验；坏签名仍会拒绝。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        LogPanel(state.log, Modifier.height(220.dp))
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LogPanel(log: String, modifier: Modifier = Modifier) {
    if (log.isBlank()) return
    Card(modifier = modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
            ) {
                Text(
                    text = log,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
