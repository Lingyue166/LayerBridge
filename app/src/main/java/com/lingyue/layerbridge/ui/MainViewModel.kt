package com.lingyue.layerbridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lingyue.layerbridge.R
import com.lingyue.layerbridge.core.LbPaths
import com.lingyue.layerbridge.core.RootDetector
import com.lingyue.layerbridge.core.RootInfo
import com.lingyue.layerbridge.crypto.TrustStore
import com.lingyue.layerbridge.install.InstallPolicy
import com.lingyue.layerbridge.install.LinuxInstaller
import com.lingyue.layerbridge.repo.DependencyResolver
import com.lingyue.layerbridge.repo.RelationParser
import com.lingyue.layerbridge.repo.RepoClient
import com.lingyue.layerbridge.repo.RepoConfig
import com.lingyue.layerbridge.repo.RepoPackage
import com.lingyue.layerbridge.repo.ResolveOutcome
import com.lingyue.layerbridge.state.InstalledPackage
import com.lingyue.layerbridge.state.RootStateStorage
import com.lingyue.layerbridge.state.StatusDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val packages: List<RepoPackage> = emptyList(),
    val installed: List<InstalledPackage> = emptyList(),
    val query: String = "",
    val root: RootInfo? = null,
    val repoVerified: Boolean = false,
    val repoDate: String? = null,
    val loading: Boolean = false,
    val installing: String? = null,
    val log: String = "",
    val message: String = "",
    val requireSignature: Boolean = true,
    val malwareScan: Boolean = true,
) {
    val installedNames: Map<String, InstalledPackage> get() = installed.associateBy { it.name }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("lb", Application.MODE_PRIVATE)
    private val statusDb = StatusDb(RootStateStorage())
    private val trustStore = TrustStore(File(application.filesDir, "trust"))
    private val installer = LinuxInstaller(statusDb, trustStore)
    private val repo = RepoConfig.termux
    private val repoClient = RepoClient(File(application.cacheDir, "repo")) { asset ->
        runCatching { application.assets.open(asset).readBytes() }.getOrNull()
    }

    private val _state = MutableStateFlow(
        UiState(
            requireSignature = prefs.getBoolean("require_signature", true),
            malwareScan = prefs.getBoolean("malware_scan", true),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshInstalled()
        viewModelScope.launch {
            val root = RootDetector.detect(getApplication())
            _state.update { it.copy(root = root) }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setRequireSignature(enabled: Boolean) {
        prefs.edit().putBoolean("require_signature", enabled).apply()
        _state.update { it.copy(requireSignature = enabled) }
    }

    fun setMalwareScan(enabled: Boolean) {
        prefs.edit().putBoolean("malware_scan", enabled).apply()
        _state.update { it.copy(malwareScan = enabled) }
    }

    private fun str(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    fun clearLog() = _state.update { it.copy(log = "") }

    fun refreshInstalled() {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { statusDb.load() }
            _state.update { it.copy(installed = installed) }
        }
    }

    fun bootstrap() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, log = "") }
            val result = installer.bootstrap()
            result.onSuccess {
                val apk = getApplication<Application>().applicationInfo.sourceDir
                val copy = com.lingyue.layerbridge.core.Shell.exec(
                    "cp ${com.lingyue.layerbridge.core.PathGuard.quote(apk)} " +
                        "${com.lingyue.layerbridge.core.PathGuard.quote("${com.lingyue.layerbridge.core.LbPaths.BASE}/lb.apk")} && " +
                        "chmod 644 ${com.lingyue.layerbridge.core.LbPaths.BASE}/lb.apk",
                )
                if (!copy.isSuccess) appendLog("[lb] 复制 lb.apk 失败：${copy.output}")
                appendLog("[lb] 前缀已初始化：${LbPaths.PREFIX}")
            }
                .onFailure { appendLog("[lb] 初始化失败：${it.message}") }
            _state.update { it.copy(loading = false) }
        }
    }

    fun updateRepo() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = str(R.string.msg_index_updating)) }
            val result = repoClient.updateIndex(repo)
            result.onSuccess { index ->
                _state.update {
                    it.copy(
                        packages = index.packages,
                        repoVerified = index.signatureVerified,
                        repoDate = index.releaseDate,
                        message = if (index.signatureVerified) {
                            str(R.string.msg_index_loaded_verified, index.packages.size)
                        } else {
                            str(R.string.msg_index_loaded, index.packages.size)
                        },
                    )
                }
                appendLog("[repo] ${repo.url}：${index.packages.size} 个包，签名=${index.signatureVerified}")
            }.onFailure {
                _state.update { state -> state.copy(message = str(R.string.msg_index_failed, it.message.orEmpty())) }
                appendLog("[repo] 失败：${it.message}")
            }
            _state.update { it.copy(loading = false) }
        }
    }

    fun install(name: String) {
        if (_state.value.installing != null) return
        viewModelScope.launch {
            val snapshot = _state.value
            val installedVersions = snapshot.installed.associate { it.name to it.version }
            val installedProvides = snapshot.installed
                .flatMap { pkg -> RelationParser.parseProvides(pkg.relations.provides).map { it.name to pkg.version } }
                .toMap()
            val resolver = DependencyResolver(snapshot.packages, installedVersions, installedProvides)
            when (val outcome = resolver.resolve(listOf(name))) {
                is ResolveOutcome.Failure -> {
                    _state.update { it.copy(message = outcome.message) }
                    appendLog("[resolve] ${outcome.message}")
                }
                is ResolveOutcome.Success -> installPlan(outcome.order)
            }
        }
    }

    private suspend fun installPlan(plan: List<RepoPackage>) {
        val policy = InstallPolicy(
            requireSignature = _state.value.requireSignature,
            malwareScan = _state.value.malwareScan,
        )
        var failed = false
        for (pkg in plan) {
            if (_state.value.installedNames.containsKey(pkg.name)) continue
            _state.update { it.copy(installing = pkg.name, message = str(R.string.msg_installing, pkg.name)) }
            appendLog("[lb] 安装 ${pkg.name} ${pkg.version}")
            val download = repoClient.downloadDeb(repo, pkg) { read, total ->
                if (total > 0) {
                    val percent = (read * 100 / total).toInt()
                    _state.update { it.copy(message = str(R.string.msg_downloading, pkg.name, percent)) }
                }
            }
            val deb = download.getOrElse {
                appendLog("[lb] 下载失败：${it.message}")
                failed = true
                break
            }
            val result = installer.installDeb(deb, repo.id, _state.value.repoVerified, policy) { line ->
                appendLog(line)
            }
            result.onFailure {
                appendLog("[lb] 安装失败：${it.message}")
                failed = true
            }
            if (failed) break
        }
        refreshInstalled()
        _state.update {
            it.copy(
                installing = null,
                loading = false,
                message = str(if (failed) R.string.msg_install_failed else R.string.msg_install_done),
            )
        }
    }

    fun uninstall(name: String) {
        if (_state.value.installing != null) return
        viewModelScope.launch {
            _state.update { it.copy(installing = name, message = str(R.string.msg_uninstalling, name)) }
            installer.uninstall(name) { line -> appendLog(line) }
                .onSuccess { _state.update { s -> s.copy(message = str(R.string.msg_uninstalled, name)) } }
                .onFailure { _state.update { s -> s.copy(message = str(R.string.msg_uninstall_failed, it.message.orEmpty())) } }
            refreshInstalled()
            _state.update { it.copy(installing = null) }
        }
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        _state.update { current ->
            val lines = (current.log + line + "\n").lines().takeLast(400)
            current.copy(log = lines.joinToString("\n"))
        }
    }
}
