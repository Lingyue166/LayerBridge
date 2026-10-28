package com.lingyue.layerbridge.deb

import com.lingyue.layerbridge.state.InstalledPackage
import com.lingyue.layerbridge.state.LocalStateStorage
import com.lingyue.layerbridge.state.StatusDb
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking

class DebFileTest {

    @Test
    fun parsesControlAndScripts() {
        val deb = createDeb(
            control = """
                Package: hello
                Version: 2.12.1-1
                Architecture: aarch64
                Depends: libc
                Description: GNU hello
                 long description
            """.trimIndent(),
            scripts = mapOf("postinst" to "#!/data/data/com.termux/files/usr/bin/bash\necho ok\n"),
            files = mapOf("bin/hello" to "#!/system/bin/sh\necho hello\n"),
        )

        val parsed = DebFile(deb)
        val control = parsed.readControl()
        assertEquals("hello", control.packageName)
        assertEquals("2.12.1-1", control.version)
        assertEquals("aarch64", control.architecture)
        assertTrue(control.scripts.containsKey("postinst"))

        val data = parsed.dataTarInputStream()
        val entry = data.nextEntry
        assertEquals("bin/hello", entry.name)
    }

    @Test
    fun relocatesTermuxShebang() {
        val script = "#!/data/data/com.termux/files/usr/bin/bash\necho hi\n"
        val rewritten = ShebangRelocator.rewrite(script.toByteArray(), "/data/local/lb/usr")
        assertEquals("#!/data/local/lb/usr/bin/bash\necho hi\n", rewritten.toString(Charsets.UTF_8))
    }

    @Test
    fun leavesNonTermuxShebang() {
        val script = "#!/system/bin/sh\necho hi\n".toByteArray()
        assertArrayEquals(script, ShebangRelocator.rewrite(script, "/data/local/lb/usr"))
    }

    @Test
    fun statusDbRoundTrip() {
        val dir = createTempDir()
        val db = StatusDb(LocalStateStorage(), dir.absolutePath)
        val pkg = InstalledPackage(
            name = "hello",
            version = "1.0",
            architecture = "aarch64",
            format = "lbpa",
            repo = "local",
            installedAt = "2026-10-28T00:00:00Z",
            signer = "Community A",
            scan = "pass",
            scriptsStatus = "ok",
        )
        runBlocking {
            db.upsert(pkg)
            assertEquals(listOf(pkg), db.load())

            db.writeFileList("hello", listOf("bin/hello", "../evil", "share/doc/x"))
            assertEquals(listOf("bin/hello", "share/doc/x"), db.readFileList("hello"))

            db.remove("hello")
            assertTrue(db.load().isEmpty())
            db.remove("ghost")
        }
    }

    private fun createTempDir(): File = kotlin.io.path.createTempDirectory("lb-test").toFile()

    private fun createDeb(
        control: String,
        scripts: Map<String, String>,
        files: Map<String, String>,
    ): File {
        val controlTar = ByteArrayOutputStream()
        TarArchiveOutputStream(controlTar).use { tar ->
            addEntry(tar, "control", control.toByteArray())
            scripts.forEach { (name, content) -> addEntry(tar, name, content.toByteArray()) }
        }
        val dataTar = ByteArrayOutputStream()
        TarArchiveOutputStream(dataTar).use { tar ->
            files.forEach { (name, content) -> addEntry(tar, name, content.toByteArray()) }
        }

        val ar = ByteArrayOutputStream()
        ar.write("!<arch>\n".toByteArray())
        writeArEntry(ar, "debian-binary", "2.0\n".toByteArray())
        writeArEntry(ar, "control.tar.gz", gzip(controlTar.toByteArray()))
        writeArEntry(ar, "data.tar.gz", gzip(dataTar.toByteArray()))

        val file = File.createTempFile("lb-test-", ".deb")
        file.writeBytes(ar.toByteArray())
        file.deleteOnExit()
        return file
    }

    private fun addEntry(tar: TarArchiveOutputStream, name: String, bytes: ByteArray) {
        val entry = TarArchiveEntry(name)
        entry.size = bytes.size.toLong()
        entry.mode = 0b111_101_101
        tar.putArchiveEntry(entry)
        tar.write(bytes)
        tar.closeArchiveEntry()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun writeArEntry(out: ByteArrayOutputStream, name: String, bytes: ByteArray) {
        val header = StringBuilder()
        header.append(name.padEnd(16))
        header.append("0".padEnd(12))
        header.append("0".padEnd(6))
        header.append("0".padEnd(6))
        header.append("100644".padEnd(8))
        header.append(bytes.size.toString().padEnd(10))
        header.append("`\n")
        out.write(header.toString().toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        if (bytes.size % 2 != 0) out.write('\n'.code)
    }

    @Test
    fun parsesConffilesForUpgradeKeep() {
        val deb = createDeb(
            control = """
                Package: hello
                Version: 1.0
                Architecture: aarch64
                Conffiles:
                 /etc/hello.conf abc123
                 /etc/other.conf def456
                 /not/absolute-noop abc
            """.trimIndent(),
            scripts = emptyMap(),
            files = mapOf("usr/bin/hello" to "x"),
        )

        val files = DebFile(deb).readControl().conffiles

        assertEquals(listOf("etc/hello.conf", "etc/other.conf", "not/absolute-noop"), files)
    }
}
