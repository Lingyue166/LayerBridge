package com.lingyue.layerbridge.format

import com.google.gson.Gson
import com.lingyue.layerbridge.crypto.Certificate
import com.lingyue.layerbridge.crypto.PackageSigner
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * 校验 scripts/lb-pack.py 产出的 LBPA。
 * 未设置 LB_TEST_LBPA 时跳过（不依赖外部文件）。
 */
class PackedLbpaTest {

    @Test
    fun packedFileParsesAndVerifies() {
        val paths = System.getenv("LB_TEST_LBPA")?.split(":")?.filter { it.isNotBlank() } ?: return
        val rootFile = File(System.getProperty("user.dir")).parentFile.resolve("certs/lb-root.json")
        val root = Gson().fromJson(rootFile.readText(), Certificate::class.java)

        paths.forEach { path ->
            val file = File(path)
            assertTrue("缺少文件：$path", file.isFile)

            val parsed = LbpaFile.parse(file)
            val block = PackageSigner.parseBlock(parsed.signatureJson ?: error("未签名：$path"))
            val verified = PackageSigner.verifyLbpa(
                block = block,
                manifestBytes = parsed.manifestBytes,
                payloadHashHex = parsed.payloadSha256(),
                root = root,
                packageName = parsed.packageName,
            )
            assertTrue("验签失败：$path", verified.getOrElse { throw AssertionError(it) }.keyId == root.keyId)

            val entries = mutableListOf<String>()
            XZInputStream(parsed.payloadStream()).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var e = tar.nextEntry
                    while (e != null) {
                        entries += e.name
                        e = tar.nextEntry
                    }
                }
            }
            assertTrue("载荷缺少 files/：$path", entries.any { it.startsWith("files/") })
        }
    }
}
