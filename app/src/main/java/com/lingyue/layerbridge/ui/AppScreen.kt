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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lingyue.layerbridge.R
import com.lingyue.layerbridge.core.LbPaths
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
                    TextButton(onClick = { viewModel.updateRepo() }) { Text(stringResource(R.string.action_update_index)) }
                    TextButton(onClick = { viewModel.bootstrap() }) { Text(stringResource(R.string.action_init)) }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text(stringResource(R.string.tab_icon_packages)) },
                    label = { Text(stringResource(R.string.tab_packages)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text(stringResource(R.string.tab_icon_environment)) },
                    label = { Text(stringResource(R.string.tab_environment)) },
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
            label = { Text(stringResource(R.string.search_hint)) },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.message.ifEmpty {
                stringResource(R.string.status_summary, state.packages.size, state.installed.size) +
                    if (state.repoVerified) stringResource(R.string.status_verified) else ""
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
                    if (installedVersion != null) stringResource(R.string.badge_installed) else "",
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
            installedVersion != null -> TextButton(onClick = onUninstall) { Text(stringResource(R.string.action_uninstall)) }
            else -> Button(onClick = onInstall) { Text(stringResource(R.string.action_install)) }
        }
    }
}

@Composable
private fun EnvironmentScreen(state: UiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.root_section), style = MaterialTheme.typography.titleMedium)
                val root = state.root
                Text(
                    when {
                        root == null -> stringResource(R.string.root_detecting)
                        root.granted && root.managers.isEmpty() -> stringResource(R.string.root_granted)
                        root.granted -> stringResource(
                            R.string.root_granted_managers,
                            root.managers.joinToString { "${it.name} ${it.version.orEmpty()}" },
                        )
                        root.managers.isEmpty() -> stringResource(R.string.root_no_manager)
                        else -> stringResource(R.string.root_not_granted, root.managers.joinToString { it.name })
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.env_section), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.env_prefix, LbPaths.PREFIX), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.env_installed, state.installed.size), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(if (state.repoVerified) R.string.env_index_verified else R.string.env_index_unverified),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.bootstrap() }) { Text(stringResource(R.string.action_init_prefix)) }
                    Button(onClick = { viewModel.updateRepo() }) { Text(stringResource(R.string.action_update_index)) }
                    TextButton(onClick = { viewModel.refreshInstalled() }) { Text(stringResource(R.string.action_refresh)) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.language_section), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val context = LocalContext.current
                val current = LocaleHelper.storedTag(context)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        LocaleHelper.SYSTEM to R.string.language_system,
                        "zh" to R.string.language_zh,
                        "en" to R.string.language_en,
                    ).forEach { (tag, label) ->
                        FilterChip(
                            selected = current == tag,
                            onClick = {
                                LocaleHelper.save(context, tag)
                                LocaleHelper.activityOf(context)?.recreate()
                            },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.dev_section), style = MaterialTheme.typography.titleMedium)
                SwitchRow(
                    title = stringResource(R.string.dev_require_signature),
                    checked = state.requireSignature,
                    onCheckedChange = viewModel::setRequireSignature,
                )
                SwitchRow(
                    title = stringResource(R.string.dev_malware_scan),
                    checked = state.malwareScan,
                    onCheckedChange = viewModel::setMalwareScan,
                )
                Text(
                    stringResource(R.string.dev_note),
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
