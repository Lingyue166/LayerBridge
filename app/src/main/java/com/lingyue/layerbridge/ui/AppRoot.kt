package com.lingyue.layerbridge.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lingyue.layerbridge.R
import com.lingyue.layerbridge.ui.components.MenuIcon
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun AppRoot(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pendingInstall by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior()
    var graphMode by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        when {
            uris.isEmpty() -> Unit
            uris.size == 1 -> viewModel.installFromUris(uris)
            else -> pendingInstall = uris
        }
    }

    MiuixTheme(controller) {
        Scaffold(
            topBar = {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = when (tab) {
                        0 -> "LayerBridge"
                        else -> stringResource(R.string.nav_packages)
                    },
                    actions = {
                        if (tab == 1) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    MenuIcon(tint = MiuixTheme.colorScheme.onSurface)
                                }
                                OverlayListPopup(
                                    show = menuOpen,
                                    popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
                                    alignment = PopupPositionProvider.Align.End,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    ListPopupColumn {
                                        DropdownImpl(
                                            text = stringResource(
                                                if (graphMode) R.string.menu_cards else R.string.menu_graph,
                                            ),
                                            optionSize = 3,
                                            isSelected = graphMode,
                                            index = 0,
                                            onSelectedIndexChange = {
                                                graphMode = !graphMode
                                                menuOpen = false
                                            },
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.menu_refresh),
                                            optionSize = 3,
                                            isSelected = false,
                                            index = 1,
                                            onSelectedIndexChange = {
                                                viewModel.refreshInstalled()
                                                menuOpen = false
                                            },
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.menu_repair_env),
                                            optionSize = 3,
                                            isSelected = false,
                                            index = 2,
                                            onSelectedIndexChange = {
                                                viewModel.repairEnvironment()
                                                menuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                if (tab == 1 && !graphMode) {
                    FloatingActionButton(
                        onClick = {
                            picker.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/vnd.debian.binary-package",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        modifier = Modifier
                            .padding(bottom = 20.dp, end = 20.dp)
                            .border(
                                0.05.dp,
                                MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                                CircleShape,
                            ),
                        shadowElevation = 0.dp,
                    ) {
                        Icon(
                            MiuixIcons.Add,
                            stringResource(R.string.pkg_install_local),
                            modifier = Modifier.size(32.dp),
                            tint = MiuixTheme.colorScheme.onPrimary,
                        )
                    }
                }
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
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> DashboardPage(
                        state = state,
                        log = log,
                        viewModel = viewModel,
                        modifier = Modifier
                            .padding(padding)
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                    )
                    else -> PackagesPage(
                        state = state,
                        viewModel = viewModel,
                        graphMode = graphMode,
                        modifier = Modifier
                            .padding(padding)
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                    )
                }
                InstallConfirmDialog(
                    uris = pendingInstall,
                    onCancel = { pendingInstall = emptyList() },
                    onConfirm = {
                        viewModel.installFromUris(pendingInstall)
                        pendingInstall = emptyList()
                    },
                )
            }
        }
    }
}

@Composable
private fun InstallConfirmDialog(
    uris: List<Uri>,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (uris.isEmpty()) return
    val context = LocalContext.current
    val names = remember(uris) { uris.map { it.displayName(context) } }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.pkg_install_local),
        onDismissRequest = onCancel,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pkg_install_multiple, names.size))
            names.forEach { name ->
                Text(name, style = MiuixTheme.textStyles.footnote2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = stringResource(R.string.pkg_cancel), onClick = onCancel)
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.pkg_confirm_install))
                }
            }
        }
    }
}

private fun Uri.displayName(context: Context): String = runCatching {
    context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull() ?: lastPathSegment ?: "file"
