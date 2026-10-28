package com.lingyue.layerbridge.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ExecResult(val exitCode: Int, val output: String) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * 统一通过 `su -c` 执行。脚本走 stdin（`sh -s`），避免在设备上落地临时脚本。
 * 所有命令都由调用方构造并做路径/包名校验。
 */
object Shell {

    internal const val MAX_OUTPUT = 1024 * 1024

    /** 默认执行器；测试可直接注入 `sh -c`。 */
    internal val DEFAULT_LAUNCHER = listOf("su", "-c")

    suspend fun isRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching { exec("id -u", timeoutMs = 10_000).output.trim() == "0" }.getOrDefault(false)
    }

    suspend fun exec(command: String, timeoutMs: Long = 120_000): ExecResult =
        withContext(Dispatchers.IO) {
            runProcess(DEFAULT_LAUNCHER, command, timeoutMs) { }
        }

    suspend fun script(
        body: String,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 600_000,
    ): ExecResult = withContext(Dispatchers.IO) {
        val exports = env.entries.joinToString("\n") { (key, value) ->
            "export $key=${PathGuard.quote(value)}"
        }
        val script = buildString {
            if (exports.isNotEmpty()) appendLine(exports)
            appendLine(body)
        }
        runProcess(DEFAULT_LAUNCHER, "sh -s", timeoutMs) { output ->
            output.write(script.toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun writeFile(
        path: String,
        content: String,
        mode: String? = null,
        timeoutMs: Long = 120_000,
    ): ExecResult = withContext(Dispatchers.IO) {
        val parent = path.substringBeforeLast('/', "")
        val command = buildString {
            if (parent.isNotEmpty()) append("mkdir -p ${PathGuard.quote(parent)} && ")
            append("cat > ${PathGuard.quote(path)}")
            if (mode != null) append(" && chmod $mode ${PathGuard.quote(path)}")
        }
        runProcess(DEFAULT_LAUNCHER, command, timeoutMs) { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    suspend fun extractTar(
        prefix: String,
        timeoutMs: Long = 900_000,
        writer: (OutputStream) -> Unit,
    ): ExecResult = withContext(Dispatchers.IO) {
        runProcess(DEFAULT_LAUNCHER, "tar -x -f - -C ${PathGuard.quote(prefix)}", timeoutMs, writer)
    }

    /**
     * stdin 写入与 stdout 读取各占一个线程，主线程只负责等待超时：
     * 双向都可能塞满管道，单线程先写后读会在子进程输出较大时死锁，
     * 而读完 EOF 再 waitFor 又会让挂住的子进程永远等不到超时。
     */
    internal fun runProcess(
        launcher: List<String>,
        command: String,
        timeoutMs: Long,
        stdin: (OutputStream) -> Unit,
    ): ExecResult {
        val process = try {
            ProcessBuilder(launcher + command)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            return ExecResult(-1, "[lb] 无法启动进程：${e.message}")
        }

        val output = ByteArrayOutputStream()
        val reader = thread(name = "lb-stdout", isDaemon = true) {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() < MAX_OUTPUT) {
                            output.write(buffer, 0, minOf(count, MAX_OUTPUT - output.size()))
                        }
                    }
                }
            } catch (_: IOException) {
                // 超时后主线程主动关闭管道
            }
        }

        var writeFailure: Exception? = null
        val writer = thread(name = "lb-stdin", isDaemon = true) {
            try {
                process.outputStream.use { output -> stdin(output) }
            } catch (e: Exception) {
                writeFailure = e
            }
        }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
        }
        reader.join(2_000)
        writer.join(2_000)

        val text = output.toString(Charsets.UTF_8.name())
        if (!finished) return ExecResult(-1, "$text\n[lb] 命令超时")
        writeFailure?.let { return ExecResult(-1, "$text\n[lb] ${it.message}") }
        return ExecResult(process.exitValue(), text)
    }
}
