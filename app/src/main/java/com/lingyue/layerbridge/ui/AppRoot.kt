package com.lingyue.layerbridge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lingyue.layerbridge.R
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Home

@Composable
fun AppRoot(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.installFromUris(uris) }

    MiuixTheme(controller) {
        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = when (tab) {
                        0 -> "LayerBridge"
                        else -> stringResource(R.string.nav_packages)
                    },
                    actions = {
                        if (tab == 1) {
                            TextButton(
                                text = stringResource(R.string.pkg_install_local),
                                onClick = { picker.launch(arrayOf("*/*")) },
                            )
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = MiuixIcons.Home,
                        label = stringResource(R.string.nav_dashboard),
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = MiuixIcons.All,
                        label = stringResource(R.string.nav_packages),
                    )
                }
            },
        ) { padding ->
            when (tab) {
                0 -> DashboardPage(state, log, viewModel, Modifier.padding(padding))
                else -> PackagesPage(state, viewModel, Modifier.padding(padding))
            }
        }
    }
}
