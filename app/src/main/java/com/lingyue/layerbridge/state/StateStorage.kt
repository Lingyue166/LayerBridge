package com.lingyue.layerbridge.state

import com.lingyue.layerbridge.core.PathGuard
import com.lingyue.layerbridge.core.Shell
import java.io.File

/**
 * 状态文件读写抽象：App 走 `su`（RootStateStorage），CLI 与单测走本地文件。
 * 具体路径由 [StatusDb] 决定，这里只负责搬运文本。
 */
interface StateStorage {
    suspend fun readText(path: String): String?
    suspend fun writeText(path: String, content: String, mode: String = "644")
    suspend fun delete(path: String)
}

/** 以当前进程身份读写；CLI 以 root 运行，测试跑在临时目录。 */
class LocalStateStorage : StateStorage {

    override suspend fun readText(path: String): String? =
        File(path).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    override suspend fun writeText(path: String, content: String, mode: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        val temp = File(path + ".tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(content, Charsets.UTF_8)
            temp.delete()
        }
    }

    override suspend fun delete(path: String) {
        File(path).delete()
    }
}

/** App 进程无权写前缀目录，统一经 `su` 落地。 */
class RootStateStorage : StateStorage {

    override suspend fun readText(path: String): String? {
        val quoted = PathGuard.quote(path)
        if (!Shell.exec("test -f $quoted").isSuccess) return null
        val result = Shell.exec("cat $quoted")
        if (!result.isSuccess) error("读取状态失败：$path：${result.output}")
        return result.output
    }

    override suspend fun writeText(path: String, content: String, mode: String) {
        // 先写临时文件再原子替换：中途被杀也不会把状态文件截断成半截
        val temp = "$path.tmp"
        val written = Shell.writeFile(temp, content, mode)
        if (!written.isSuccess) error("写入状态失败：$path：${written.output}")
        val moved = Shell.exec("mv -f ${PathGuard.quote(temp)} ${PathGuard.quote(path)}")
        if (!moved.isSuccess) error("替换状态失败：$path：${moved.output}")
    }

    override suspend fun delete(path: String) {
        Shell.exec("rm -f -- ${PathGuard.quote(path)}")
    }
}
