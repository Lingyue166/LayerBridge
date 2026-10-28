package com.lingyue.layerbridge.format

import com.google.gson.Gson
import com.lingyue.layerbridge.crypto.Certificates
import com.lingyue.layerbridge.crypto.PackageSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

class LbpaTest {

    private val now = Instant.parse("2026-10-28T00:00:00Z")

    @Test
    fun parsesUnsignedPackage() {
        val payload = "payload-bytes".toByteArray()
        val manifest = LbpaManifest(
            format = "LAYERBRIDGEPACKONANDROID",
            formatVersion = 1,
            packageInfo = LbpaPackageInfo("hello", "1.0", "aarch64"),
            payload = LbpaPayload("xz", payload.size.toLong(), PackageSigner.sha256Hex(payload)),
            scripts = LbpaScripts(postinst = "control/postinst.sh"),
            expose = listOf("bin/hello"),
        )
        val file = buildLbpa(manifest, payload, signedBlock = null)
        val parsed = LbpaFile.parse(file)
        assertEquals("hello", parsed.packageName)
        assertEquals(payload.size.toLong(), parsed.payloadSize)
        assertEquals(PackageSigner.sha256Hex(payload), parsed.payloadSha256())
        assertEquals(null, parsed.signatureJson)
    }

    @Test
    fun parsesSignedPackage() {
        val payload = "payload".toByteArray()
        val rootKey = Certificates.generateKeyPair()
        val root = Certificates.createRoot(
            "Root", rootKey, now.minus(1, ChronoUnit.DAYS), now.plus(365, ChronoUnit.DAYS),
        )
        val manifest = LbpaManifest(
            format = "LAYERBRIDGEPACKONANDROID",
            formatVersion = 1,
            packageInfo = LbpaPackageInfo("hello", "1.0", "aarch64"),
            payload = LbpaPayload("xz", payload.size.toLong(), PackageSigner.sha256Hex(payload)),
        )
        val manifestBytes = Gson().toJson(manifest).toByteArray()
        val block = PackageSigner.signLbpa(
            manifestBytes, PackageSigner.sha256Hex(payload), rootKey.private, root.keyId, emptyList(),
        )
        val file = buildLbpa(manifest, payload, signedBlock = block)
        val parsed = LbpaFile.parse(file)
        val signature = PackageSigner.parseBlock(parsed.signatureJson!!)
        assertTrue(
            PackageSigner.verifyLbpa(
                signature, parsed.manifestBytes, PackageSigner.sha256Hex(payload), root, "hello", now,
            ).isSuccess,
        )
    }

    @Test(expected = LbpaFormatException::class)
    fun rejectsTrailingBytes() {
        val payload = "payload".toByteArray()
        val manifest = LbpaManifest(
            format = "LAYERBRIDGEPACKONANDROID",
            formatVersion = 1,
            packageInfo = LbpaPackageInfo("hello", "1.0", "aarch64"),
            payload = LbpaPayload("xz", payload.size.toLong(), PackageSigner.sha256Hex(payload)),
        )
        val file = buildLbpa(manifest, payload, signedBlock = null)
        file.appendBytes(byteArrayOf(0))
        LbpaFile.parse(file)
    }

    @Test(expected = LbpaFormatException::class)
    fun rejectsUnknownFormatVersion() {
        val payload = "payload".toByteArray()
        val manifest = LbpaManifest(
            format = "LAYERBRIDGEPACKONANDROID",
            formatVersion = 2,
            packageInfo = LbpaPackageInfo("hello", "1.0", "aarch64"),
            payload = LbpaPayload("xz", payload.size.toLong(), PackageSigner.sha256Hex(payload)),
        )
        LbpaFile.parse(buildLbpa(manifest, payload, signedBlock = null))
    }

    private fun buildLbpa(
        manifest: LbpaManifest,
        payload: ByteArray,
        signedBlock: com.lingyue.layerbridge.crypto.SignatureBlock?,
    ): File {
        val manifestBytes = Gson().toJson(manifest).toByteArray()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.write(LbpaFile.MAGIC)
            data.writeShort(1)
            data.writeShort(if (signedBlock != null) 1 else 0)
            data.writeInt(manifestBytes.size)
            data.write(manifestBytes)
            data.write(payload)
            if (signedBlock != null) {
                val blockBytes = PackageSigner.encode(signedBlock).toByteArray()
                data.writeInt(blockBytes.size)
                data.write(blockBytes)
            }
        }
        val file = File.createTempFile("lb-test-", ".lbpa")
        file.writeBytes(out.toByteArray())
        file.deleteOnExit()
        return file
    }

    @Test
    fun parsesUpgradeBlock() {
        val payload = "payload".toByteArray()
        val manifest = LbpaManifest(
            format = "LAYERBRIDGEPACKONANDROID",
            formatVersion = 1,
            packageInfo = LbpaPackageInfo("hello", "2.0", "aarch64"),
            payload = LbpaPayload("xz", payload.size.toLong(), PackageSigner.sha256Hex(payload)),
            upgrade = LbpaUpgrade(
                keep = listOf("etc/hello.conf"),
                remove = listOf("share/old"),
                scripts = LbpaUpgradeScripts(pre = "control/upgrade-pre.sh"),
            ),
        )

        val parsed = LbpaFile.parse(buildLbpa(manifest, payload, signedBlock = null))

        assertEquals(listOf("etc/hello.conf"), parsed.manifest.upgrade?.keep)
        assertEquals(listOf("share/old"), parsed.manifest.upgrade?.remove)
        assertEquals("control/upgrade-pre.sh", parsed.manifest.upgrade?.scripts?.pre)
    }

    @Test
    fun rejectsUpgradePathsOutsidePrefix() {
        val payload = "payload".toByteArray()
        val manifest = LbpaManifest(
            format = "LAYERBRIDGEPACKONANDROID",
            formatVersion = 1,
            packageInfo = LbpaPackageInfo("hello", "2.0", "aarch64"),
            payload = LbpaPayload("xz", payload.size.toLong(), PackageSigner.sha256Hex(payload)),
            upgrade = LbpaUpgrade(keep = listOf("../etc/passwd")),
        )

        assertThrows(LbpaFormatException::class.java) {
            LbpaFile.parse(buildLbpa(manifest, payload, signedBlock = null))
        }
    }

    @Test
    fun parsesDataPathsForUninstallRetention() {
        val payload = "payload".toByteArray()
        val manifest = LbpaManifest(
            format = "LAYERBRIDGEPACKONANDROID",
            formatVersion = 1,
            packageInfo = LbpaPackageInfo("hello", "1.0", "aarch64"),
            payload = LbpaPayload("xz", payload.size.toLong(), PackageSigner.sha256Hex(payload)),
            data = listOf("etc/hello.conf", "var/lib/hello"),
        )

        val parsed = LbpaFile.parse(buildLbpa(manifest, payload, signedBlock = null))

        assertEquals(listOf("etc/hello.conf", "var/lib/hello"), parsed.manifest.data)
    }

    @Test
    fun rejectsDataPathsOutsidePrefix() {
        val payload = "payload".toByteArray()
        val manifest = LbpaManifest(
            format = "LAYERBRIDGEPACKONANDROID",
            formatVersion = 1,
            packageInfo = LbpaPackageInfo("hello", "1.0", "aarch64"),
            payload = LbpaPayload("xz", payload.size.toLong(), PackageSigner.sha256Hex(payload)),
            data = listOf("../etc/passwd"),
        )

        assertThrows(LbpaFormatException::class.java) {
            LbpaFile.parse(buildLbpa(manifest, payload, signedBlock = null))
        }
    }
}
