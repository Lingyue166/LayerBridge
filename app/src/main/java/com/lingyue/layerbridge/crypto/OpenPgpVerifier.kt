package com.lingyue.layerbridge.crypto

import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import java.io.ByteArrayInputStream

/** apt 仓库的 OpenPGP 分离签名验证（Release.gpg / InRelease）。 */
object OpenPgpVerifier {

    fun verifyDetached(
        content: ByteArray,
        signature: ByteArray,
        keyRing: ByteArray,
        expectedFingerprint: String,
    ): Result<String> = runCatching {
        val rings = PGPPublicKeyRingCollection(keyRing, JcaKeyFingerprintCalculator())
        val signatureList = readSignatureList(signature)
        val pgpSignature = signatureList[0]

        val key = rings.keyRings.asSequence()
            .flatMap { it.publicKeys.asSequence() }
            .firstOrNull { fingerprintHex(it) == expectedFingerprint.replace(" ", "").uppercase() }
            ?: throw IllegalArgumentException("repository key fingerprint mismatch")

        if (key.isRevoked) throw IllegalArgumentException("repository key is revoked")
        if (pgpSignature.keyID != key.keyID) {
            throw IllegalArgumentException("signature keyID does not match repository key")
        }

        pgpSignature.init(JcaPGPContentVerifierBuilderProvider(), key)
        pgpSignature.update(content)
        if (!pgpSignature.verify()) throw IllegalArgumentException("OpenPGP signature verification failed")
        fingerprintHex(key)
    }

    private fun readSignatureList(signature: ByteArray): PGPSignatureList {
        // apt 的 Release.gpg 可能是 ASCII armor，也可能是二进制；PGPUtil 两者都能解码。
        var factory: PGPObjectFactory = JcaPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(signature)))
        var obj = factory.nextObject()
        if (obj is PGPCompressedData) {
            factory = JcaPGPObjectFactory(obj.dataStream)
            obj = factory.nextObject()
        }
        return obj as? PGPSignatureList
            ?: throw IllegalArgumentException("not an OpenPGP signature")
    }

    fun fingerprintHex(key: PGPPublicKey): String =
        key.fingerprint.joinToString("") { "%02X".format(it) }
}
