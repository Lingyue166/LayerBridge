package com.lingyue.layerbridge.crypto

import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider

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
            ?: throw IllegalArgumentException("仓库公钥指纹不匹配")

        if (key.isRevoked) throw IllegalArgumentException("仓库公钥已被撤销")
        if (pgpSignature.keyID != key.keyID) {
            throw IllegalArgumentException("签名 keyID 与仓库公钥不匹配")
        }

        pgpSignature.init(JcaPGPContentVerifierBuilderProvider(), key)
        pgpSignature.update(content)
        if (!pgpSignature.verify()) throw IllegalArgumentException("OpenPGP 签名验证失败")
        fingerprintHex(key)
    }

    private fun readSignatureList(signature: ByteArray): PGPSignatureList {
        var factory: PGPObjectFactory = JcaPGPObjectFactory(signature)
        var obj = factory.nextObject()
        if (obj is PGPCompressedData) {
            factory = JcaPGPObjectFactory(obj.dataStream)
            obj = factory.nextObject()
        }
        return obj as? PGPSignatureList
            ?: throw IllegalArgumentException("不是 OpenPGP 签名")
    }

    fun fingerprintHex(key: PGPPublicKey): String =
        key.fingerprint.joinToString("") { "%02X".format(it) }
}
