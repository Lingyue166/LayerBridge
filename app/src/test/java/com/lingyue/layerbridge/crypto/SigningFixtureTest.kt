package com.lingyue.layerbridge.crypto

import com.google.gson.Gson
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.PrivateKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * 生成/复用 PC 端的测试签名材料：
 * - build/sign-fixtures/root.json       信任根（首次生成后复用）
 * - build/sign-fixtures/test-key.pem    测试私钥（PKCS8 base64，仅 PC 保存）
 * - build/testdeb 下每个 deb 生成同名 .sig（文件名含 unsigned 的除外）
 *
 * 复用密钥是为了让设备上已安装的 root.json 不因重跑测试而失效。
 */
class SigningFixtureTest {

    private val projectRoot: File = File(System.getProperty("user.dir")).parentFile
    private val fixtureDir = File(projectRoot, "build/sign-fixtures")
    private val debDir = File(projectRoot, "build/testdeb")

    @Test
    fun signsTestPackagesForLocalTesting() {
        fixtureDir.mkdirs()
        val rootFile = File(fixtureDir, "root.json")
        val keyFile = File(fixtureDir, "test-key.pem")

        val root: Certificate
        val privateKey: PrivateKey
        if (rootFile.isFile && keyFile.isFile) {
            root = Gson().fromJson(rootFile.readText(), Certificate::class.java)
            privateKey = Certificates.parsePrivateKey(keyFile.readText().trim())
        } else {
            val now = Instant.now()
            val keyPair = Certificates.generateKeyPair()
            root = Certificates.createRoot(
                subject = "LayerBridge Test Root",
                keyPair = keyPair,
                notBefore = now.minus(1, ChronoUnit.DAYS),
                notAfter = now.plus(365, ChronoUnit.DAYS),
            )
            privateKey = keyPair.private
            rootFile.writeText(Gson().toJson(root), Charsets.UTF_8)
            keyFile.writeText(Base64.getEncoder().encodeToString(privateKey.encoded), Charsets.UTF_8)
        }

        val debs = debDir.listFiles { f -> f.isFile && f.name.endsWith(".deb") && !f.name.contains("unsigned") }
            .orEmpty()
        assertTrue("build/testdeb 下没有可签名包", debs.isNotEmpty())

        val now = Instant.now()
        debs.forEach { deb ->
            val hash = PackageSigner.sha256File(deb)
            val block = PackageSigner.signDeb(hash, privateKey, root.keyId, emptyList())
            File(fixtureDir, "${deb.name}.sig").writeText(PackageSigner.encode(block), Charsets.UTF_8)
            assertTrue(
                "签名自检失败：${deb.name}",
                PackageSigner.verifyDeb(block, hash, root, "test", now).isSuccess,
            )
        }

        println("signed ${debs.size} package(s) with root ${root.subject} (${root.keyId.take(16)}...)")
    }
}
