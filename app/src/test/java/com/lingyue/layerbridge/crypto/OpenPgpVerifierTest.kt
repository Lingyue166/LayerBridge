package com.lingyue.layerbridge.crypto

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyConverter
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.util.Date

class OpenPgpVerifierTest {

    @Test
    fun verifiesDetachedSignature() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val converter = JcaPGPKeyConverter()
        val pgpPublic = converter.getPGPPublicKey(PublicKeyAlgorithmTags.RSA_GENERAL, keyPair.public, Date())
        val pgpPrivate = converter.getPGPPrivateKey(pgpPublic, keyPair.private)

        val content = "Origin: termux-main\nDate: now\n".toByteArray()
        val generator = PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(PublicKeyAlgorithmTags.RSA_GENERAL, HashAlgorithmTags.SHA256),
        )
        generator.init(PGPSignature.BINARY_DOCUMENT, pgpPrivate)
        generator.update(content)
        val signature = generator.generate()

        val signatureBytes = ByteArrayOutputStream().also { signature.encode(it) }.toByteArray()
        val ringBytes = ByteArrayOutputStream().also {
            PGPPublicKeyRing(listOf(pgpPublic)).encode(it)
        }.toByteArray()
        val fingerprint = OpenPgpVerifier.fingerprintHex(pgpPublic)

        assertTrue(
            OpenPgpVerifier.verifyDetached(content, signatureBytes, ringBytes, fingerprint).isSuccess,
        )
        assertTrue(
            OpenPgpVerifier.verifyDetached(
                "tampered".toByteArray(), signatureBytes, ringBytes, fingerprint,
            ).isFailure,
        )

        val armored = ByteArrayOutputStream().also { out ->
            ArmoredOutputStream(out).use { signature.encode(it) }
        }.toByteArray()
        assertTrue(
            OpenPgpVerifier.verifyDetached(content, armored, ringBytes, fingerprint).isSuccess,
        )
        assertTrue(
            OpenPgpVerifier.verifyDetached(content, signatureBytes, ringBytes, "00").isFailure,
        )
    }
}
