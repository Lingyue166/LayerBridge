package com.lingyue.layerbridge.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.PrivateKey
import java.time.Instant
import java.time.temporal.ChronoUnit

class SigningTest {

    private val now = Instant.parse("2026-10-28T00:00:00Z")
    private val rootKeyPair = Certificates.generateKeyPair()
    private val root: Certificate = Certificates.createRoot(
        subject = "Test Root",
        keyPair = rootKeyPair,
        notBefore = now.minus(1, ChronoUnit.DAYS),
        notAfter = now.plus(3650, ChronoUnit.DAYS),
    )
    private val rootKey: PrivateKey = rootKeyPair.private

    @Test
    fun rootSignedPackageVerifies() {
        val manifest = "{\"format\":\"LAYERBRIDGEPACKONANDROID\"}".toByteArray()
        val payloadHash = PackageSigner.sha256Hex("payload".toByteArray())
        val block = PackageSigner.signLbpa(
            manifestBytes = manifest,
            payloadHashHex = payloadHash,
            key = rootKey,
            keyId = root.keyId,
            chain = emptyList(),
        )
        assertTrue(PackageSigner.verifyLbpa(block, manifest, payloadHash, root, "hello", now).isSuccess)
    }

    @Test
    fun intermediateChainVerifies() {
        val intermediateKey = Certificates.generateKeyPair()
        val intermediate = Certificates.createIntermediate(
            subject = "Community A",
            keyPair = intermediateKey,
            parent = root,
            parentKey = rootKey,
            notBefore = now.minus(1, ChronoUnit.DAYS),
            notAfter = now.plus(365, ChronoUnit.DAYS),
            pathLen = 0,
            packagePrefixes = listOf("com.example."),
        )
        val leafKey = Certificates.generateKeyPair()
        val leaf = Certificates.createLeaf(
            subject = "Builder A",
            keyPair = leafKey,
            parent = intermediate,
            parentKey = intermediateKey.private,
            notBefore = now.minus(1, ChronoUnit.DAYS),
            notAfter = now.plus(30, ChronoUnit.DAYS),
            packagePrefixes = listOf("com.example."),
        )
        val manifest = "manifest".toByteArray()
        val payloadHash = PackageSigner.sha256Hex("payload".toByteArray())
        val block = PackageSigner.signLbpa(
            manifestBytes = manifest,
            payloadHashHex = payloadHash,
            key = leafKey.private,
            keyId = leaf.keyId,
            chain = listOf(leaf, intermediate),
        )
        assertTrue(
            PackageSigner.verifyLbpa(block, manifest, payloadHash, root, "com.example.app", now).isSuccess,
        )
        assertTrue(
            PackageSigner.verifyLbpa(block, manifest, payloadHash, root, "other.app", now).isFailure,
        )
    }

    @Test
    fun tamperedDataFails() {
        val manifest = "manifest".toByteArray()
        val payloadHash = PackageSigner.sha256Hex("payload".toByteArray())
        val block = PackageSigner.signLbpa(manifest, payloadHash, rootKey, root.keyId, emptyList())
        assertTrue(PackageSigner.verifyLbpa(block, manifest, payloadHash, root, "hello", now).isSuccess)
        assertTrue(PackageSigner.verifyLbpa(block, "tampered".toByteArray(), payloadHash, root, "hello", now).isFailure)
        assertTrue(PackageSigner.verifyLbpa(block, manifest, "other-hash", root, "hello", now).isFailure)
    }

    @Test
    fun expiredCertificateFails() {
        val key = Certificates.generateKeyPair()
        val cert = Certificates.createLeaf(
            subject = "Old Leaf",
            keyPair = key,
            parent = root,
            parentKey = rootKey,
            notBefore = now.minus(10, ChronoUnit.DAYS),
            notAfter = now.minus(1, ChronoUnit.DAYS),
        )
        val manifest = "manifest".toByteArray()
        val payloadHash = PackageSigner.sha256Hex("payload".toByteArray())
        val block = PackageSigner.signLbpa(manifest, payloadHash, key.private, cert.keyId, listOf(cert))
        assertTrue(PackageSigner.verifyLbpa(block, manifest, payloadHash, root, "hello", now).isFailure)
    }

    @Test
    fun debSignatureVerifies() {
        val hash = PackageSigner.sha256Hex("deb".toByteArray())
        val block = PackageSigner.signDeb(hash, rootKey, root.keyId, emptyList())
        assertTrue(PackageSigner.verifyDeb(block, hash, root, "hello", now).isSuccess)
        assertTrue(PackageSigner.verifyDeb(block, "other", root, "hello", now).isFailure)
    }

    @Test
    fun blockRoundTrip() {
        val manifest = byteArrayOf(1, 2, 3)
        val payloadHash = PackageSigner.sha256Hex(byteArrayOf(4, 5))
        val block = PackageSigner.signLbpa(manifest, payloadHash, rootKey, root.keyId, emptyList())
        val parsed = PackageSigner.parseBlock(PackageSigner.encode(block))
        assertEquals(block.signature, parsed.signature)
        assertTrue(PackageSigner.verifyLbpa(parsed, manifest, payloadHash, root, "hello", now).isSuccess)
    }
}
