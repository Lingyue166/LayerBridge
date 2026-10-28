package com.lingyue.layerbridge.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

data class ExecResult(val exitCode: Int, val output: String) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * 统一通过 `su -c` 执行。脚本走 stdin（`sh -s`），避免在设备上落地临时脚本。
 * 所有命令都由调用方构造并做路径/包名校验。
 */
object Shell {

    private const val MAX_OUTPUT = 1024 * 1024

    suspend fun isRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching { exec("id -u", timeoutMs = 10_000).output.trim() == "0" }.getOrDefault(false)
    }

    suspend fun exec(command: String, timeoutMs: Long = 120_000): ExecResult =
        withContext(Dispatchers.IO) {
            runProcess(command, timeoutMs) { }
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
        runProcess("sh -s", timeoutMs) { output ->
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
        runProcess(command, timeoutMs) { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    suspend fun extractTar(
        prefix: String,
        timeoutMs: Long = 900_000,
        writer: (OutputStream) -> Unit,
    ): ExecResult = withContext(Dispatchers.IO) {
        runProcess("tar -x -f - -C ${PathGuard.quote(prefix)}", timeoutMs, writer)
    }

    private fun runProcess(
        command: String,
        timeoutMs: Long,
        stdin: (OutputStream) -> Unit,
    ): ExecResult {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        var writeFailure: Exception? = null
        try {
            process.outputStream.use { output -> stdin(output) }
        } catch (e: Exception) {
            writeFailure = e
        }
        val output = ByteArrayOutputStream()
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
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ExecResult(-1, output.toString(Charsets.UTF_8.name()) + "\n[lb] 命令超时")
        }
        if (writeFailure != null) {
            return ExecResult(-1, output.toString(Charsets.UTF_8.name()) + "\n[lb] ${writeFailure.message}")
        }
        return ExecResult(process.exitValue(), output.toString(Charsets.UTF_8.name()))
    }
}
