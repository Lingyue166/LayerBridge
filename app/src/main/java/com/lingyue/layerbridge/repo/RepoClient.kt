package com.lingyue.layerbridge.repo

import com.lingyue.layerbridge.crypto.OpenPgpVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

data class RepoConfig(
    val id: String,
    val name: String,
    val url: String,
    val suite: String = "stable",
    val component: String = "main",
    val architecture: String = "aarch64",
    val keyFingerprint: String? = null,
    val keyAsset: String? = null,
    val enabled: Boolean = true,
) {
    companion object {
        const val TERMUX_FINGERPRINT = "CC72CF8BA7DBFA0182877D045A897D96E57CF20C"

        val termux = RepoConfig(
            id = "termux-main",
            name = "Termux 官方",
            url = "https://packages.termux.dev/apt/termux-main",
            keyFingerprint = TERMUX_FINGERPRINT,
            keyAsset = "trust/termux-autobuilds.gpg",
        )
    }
}

data class IndexResult(
    val packages: List<RepoPackage>,
    val signatureVerified: Boolean,
    val releaseDate: String?,
)

class RepoClient(
    private val cacheDir: File,
    private val keyLoader: (String) -> ByteArray? = { null },
) {

    suspend fun updateIndex(repo: RepoConfig): Result<IndexResult> = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir.mkdirs()
            val releaseUrl = "${repo.url.trimEnd('/')}/dists/${repo.suite}/Release"
            val releaseBytes = fetch(releaseUrl, maxBytes = 8L * 1024 * 1024)
            val release = ReleaseParser.parse(releaseBytes.toString(Charsets.UTF_8))

            var signatureVerified = false
            if (repo.keyFingerprint != null) {
                val key = repo.keyAsset?.let(keyLoader)
                    ?: throw IllegalStateException("缺少仓库公钥：${repo.keyAsset}")
                val sigUrl = "$releaseUrl.gpg"
                val sigBytes = fetch(sigUrl, maxBytes = 1024 * 1024)
                OpenPgpVerifier.verifyDetached(releaseBytes, sigBytes, key, repo.keyFingerprint)
                    .getOrThrow()
                signatureVerified = true
                if (!ReleaseParser.isFresh(release.date)) {
                    throw IllegalStateException("Release 已超过 90 天，拒绝使用")
                }
            }

            val packagesPath = "${repo.component}/binary-${repo.architecture}/Packages.gz"
            val expected = release.hashFor(packagesPath)
                ?: release.hashFor("${repo.component}/binary-${repo.architecture}/Packages")
                ?: throw IllegalStateException("Release 中缺少 Packages 哈希")
            val packagesUrl = "${repo.url.trimEnd('/')}/dists/${repo.suite}/$packagesPath"
            val compressed = fetch(packagesUrl, maxBytes = 64L * 1024 * 1024)
            if (sha256Hex(compressed) != expected) {
                throw IllegalStateException("Packages 哈希不匹配")
            }
            val text = decompress(compressed).toString(Charsets.UTF_8)
            val packages = PackagesParser.parse(text, repo.id, repo.architecture)
            IndexResult(packages, signatureVerified, release.date?.toString())
        }
    }

    suspend fun downloadDeb(
        repo: RepoConfig,
        pkg: RepoPackage,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val filename = pkg.filename ?: throw IllegalArgumentException("缺少 Filename")
            val url = "${repo.url.trimEnd('/')}/$filename"
            val target = File(cacheDir, "${pkg.repoId}-${pkg.name}-${pkg.version}.deb")
            download(url, target, onProgress)
            val expected = pkg.sha256
            if (!expected.isNullOrBlank()) {
                val actual = fileSha256(target)
                if (!actual.equals(expected, ignoreCase = true)) {
                    target.delete()
                    throw IllegalStateException("deb 哈希不匹配")
                }
            }
            target
        }
    }

    private fun fetch(url: String, maxBytes: Long): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "lb/0.1 (Android)")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code：$url")
            val length = connection.contentLengthLong
            if (length > maxBytes) throw IllegalStateException("响应过大：$url")
            return connection.inputStream.use { it.readLimited(maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "lb/0.1 (Android)")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code：$url")
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        onProgress(read, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun decompress(bytes: ByteArray): ByteArray = when {
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() ->
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        bytes.size >= 6 && bytes[0] == 0xfd.toByte() && bytes[1] == 0x37.toByte() ->
            XZInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        else -> bytes
    }

    private fun InputStream.readLimited(maxBytes: Long): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(chunk)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IllegalStateException("响应超过上限")
            buffer.write(chunk, 0, count)
        }
        return buffer.toByteArray()
    }

    private fun fileSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
