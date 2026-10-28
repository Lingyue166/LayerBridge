package com.lingyue.layerbridge.core

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ManagerInfo(val id: String, val name: String, val version: String?)

data class RootInfo(val granted: Boolean, val managers: List<ManagerInfo>)

object RootDetector {

    private val KNOWN = listOf(
        "com.topjohnwu.magisk" to "Magisk",
        "me.weishu.kernelsu" to "KernelSU",
        "com.sukisu.ultra" to "SukiSU-Ultra",
        "me.bmax.apatch" to "APatch",
    )

    suspend fun detect(context: Context): RootInfo = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val managers = KNOWN.mapNotNull { (pkg, label) ->
            runCatching {
                val info = pm.getPackageInfo(pkg, 0)
                ManagerInfo(pkg, label, info.versionName)
            }.getOrNull()
        }
        RootInfo(granted = Shell.isRoot(), managers = managers)
    }
}
