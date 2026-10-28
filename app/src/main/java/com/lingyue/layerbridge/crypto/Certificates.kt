package com.lingyue.layerbridge.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

data class Certificate(
    val version: Int = 1,
    val serial: String,
    val subject: String,
    val issuerKeyId: String,
    val keyId: String,
    val publicKey: String,
    val notBefore: String,
    val notAfter: String,
    val isCA: Boolean,
    val pathLen: Int? = null,
    val usages: List<String> = emptyList(),
    val packagePrefixes: List<String> = emptyList(),
    val signature: String? = null,
) {

    fun canonicalBytes(): ByteArray = buildString {
        append("LBPA-CERT-V1\n")
        append(serial).append('\n')
        append(subject).append('\n')
        append(issuerKeyId).append('\n')
        append(keyId).append('\n')
        append(publicKey).append('\n')
        append(notBefore).append('\n')
        append(notAfter).append('\n')
        append(if (isCA) "true" else "false").append('\n')
        append(pathLen?.toString() ?: "-").append('\n')
        append(usages.sorted().joinToString(",")).append('\n')
        append(packagePrefixes.sorted().joinToString(",").ifEmpty { "-" }).append('\n')
    }.toByteArray(Charsets.UTF_8)

    fun publicKey(): PublicKey = Certificates.parsePublicKey(publicKey)

    fun verifySelf(): Boolean =
        signature != null && Certificates.verify(canonicalBytes(), signature, publicKey())

    fun isWithin(now: Instant): Boolean {
        val start = Instant.parse(notBefore)
        val end = Instant.parse(notAfter)
        return !now.isBefore(start) && !now.isAfter(end)
    }

    fun allowsPackage(packageName: String): Boolean =
        packagePrefixes.isEmpty() || packagePrefixes.any { packageName.startsWith(it) }

    fun hasUsage(usage: String): Boolean = usages.contains(usage)
}

object Certificates {

    private const val ALGORITHM = "SHA256withECDSA"
    private const val KEY_ALGORITHM = "EC"
    private const val CURVE = "secp256r1"

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(KEY_ALGORITHM).apply {
            initialize(ECGenParameterSpec(CURVE))
        }.generateKeyPair()

    fun keyId(publicKey: PublicKey): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey.encoded).toHex()

    fun createRoot(
        subject: String,
        keyPair: KeyPair,
        notBefore: Instant,
        notAfter: Instant,
        pathLen: Int = 1,
    ): Certificate = signCertificate(
        serial = randomSerial(),
        subject = subject,
        issuerKeyId = keyId(keyPair.public),
        keyId = keyId(keyPair.public),
        publicKey = keyPair.public,
        notBefore = notBefore,
        notAfter = notAfter,
        isCA = true,
        pathLen = pathLen,
        usages = listOf("cert-signing", "package-signing"),
        packagePrefixes = emptyList(),
        signingKey = keyPair.private,
    )

    fun createIntermediate(
        subject: String,
        keyPair: KeyPair,
        parent: Certificate,
        parentKey: PrivateKey,
        notBefore: Instant,
        notAfter: Instant,
        pathLen: Int = 0,
        packagePrefixes: List<String> = emptyList(),
    ): Certificate = signCertificate(
        serial = randomSerial(),
        subject = subject,
        issuerKeyId = parent.keyId,
        keyId = keyId(keyPair.public),
        publicKey = keyPair.public,
        notBefore = notBefore,
        notAfter = notAfter,
        isCA = true,
        pathLen = pathLen,
        usages = listOf("cert-signing", "package-signing"),
        packagePrefixes = packagePrefixes,
        signingKey = parentKey,
    )

    fun createLeaf(
        subject: String,
        keyPair: KeyPair,
        parent: Certificate,
        parentKey: PrivateKey,
        notBefore: Instant,
        notAfter: Instant,
        packagePrefixes: List<String> = emptyList(),
    ): Certificate = signCertificate(
        serial = randomSerial(),
        subject = subject,
        issuerKeyId = parent.keyId,
        keyId = keyId(keyPair.public),
        publicKey = keyPair.public,
        notBefore = notBefore,
        notAfter = notAfter,
        isCA = false,
        pathLen = null,
        usages = listOf("package-signing"),
        packagePrefixes = packagePrefixes,
        signingKey = parentKey,
    )

    private fun signCertificate(
        serial: String,
        subject: String,
        issuerKeyId: String,
        keyId: String,
        publicKey: PublicKey,
        notBefore: Instant,
        notAfter: Instant,
        isCA: Boolean,
        pathLen: Int?,
        usages: List<String>,
        packagePrefixes: List<String>,
        signingKey: PrivateKey,
    ): Certificate {
        val unsigned = Certificate(
            serial = serial,
            subject = subject,
            issuerKeyId = issuerKeyId,
            keyId = keyId,
            publicKey = Base64.getEncoder().encodeToString(publicKey.encoded),
            notBefore = notBefore.toString(),
            notAfter = notAfter.toString(),
            isCA = isCA,
            pathLen = pathLen,
            usages = usages,
            packagePrefixes = packagePrefixes,
            signature = null,
        )
        val signature = sign(unsigned.canonicalBytes(), signingKey)
        return unsigned.copy(signature = signature)
    }

    fun sign(data: ByteArray, privateKey: PrivateKey): String {
        val signer = Signature.getInstance(ALGORITHM)
        signer.initSign(privateKey)
        signer.update(data)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    fun verify(data: ByteArray, signature: String, publicKey: PublicKey): Boolean = try {
        val verifier = Signature.getInstance(ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(data)
        verifier.verify(Base64.getDecoder().decode(signature))
    } catch (e: Exception) {
        false
    }

    fun parsePublicKey(encoded: String): PublicKey =
        KeyFactory.getInstance(KEY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))

    fun parsePrivateKey(encoded: String): PrivateKey =
        KeyFactory.getInstance(KEY_ALGORITHM)
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))

    /**
     * 验证证书链。chain 为叶证书到中间证书（不含根）；root 为固定信任锚。
     * 返回签名证书。
     */
    fun verifyChain(
        chain: List<Certificate>,
        root: Certificate,
        packageName: String,
        now: Instant = Instant.now(),
    ): Result<Certificate> {
        if (!root.verifySelf()) return Result.failure(IllegalArgumentException("根证书自签名无效"))
        if (!root.isWithin(now)) return Result.failure(IllegalArgumentException("根证书不在有效期"))
        if (!root.allowsPackage(packageName)) return Result.failure(IllegalArgumentException("根证书限制包名"))
        if (!root.isCA || !root.hasUsage("cert-signing")) {
            return Result.failure(IllegalArgumentException("根证书不是 CA"))
        }
        if (chain.size > 5) return Result.failure(IllegalArgumentException("证书链过长"))

        if (chain.isEmpty()) {
            if (!root.hasUsage("package-signing")) {
                return Result.failure(IllegalArgumentException("根证书无包签名用途"))
            }
            return Result.success(root)
        }

        chain.forEachIndexed { index, cert ->
            val parent = if (index + 1 < chain.size) chain[index + 1] else root
            if (!cert.isWithin(now)) {
                return Result.failure(IllegalArgumentException("证书过期：${cert.subject}"))
            }
            if (!cert.allowsPackage(packageName)) {
                return Result.failure(IllegalArgumentException("证书限制包名：${cert.subject}"))
            }
            if (cert.keyId != keyIdOf(cert)) {
                return Result.failure(IllegalArgumentException("证书 keyId 不匹配"))
            }
            if (cert.issuerKeyId != parent.keyId) {
                return Result.failure(IllegalArgumentException("证书签发者不匹配：${cert.subject}"))
            }
            val signature = cert.signature
                ?: return Result.failure(IllegalArgumentException("证书缺少签名：${cert.subject}"))
            if (!verify(cert.canonicalBytes(), signature, parent.publicKey())) {
                return Result.failure(IllegalArgumentException("证书签名无效：${cert.subject}"))
            }
            if (index == 0) {
                if (!cert.hasUsage("package-signing")) {
                    return Result.failure(IllegalArgumentException("叶证书无包签名用途"))
                }
            } else {
                if (!cert.isCA || !cert.hasUsage("cert-signing")) {
                    return Result.failure(IllegalArgumentException("上级证书不是 CA：${cert.subject}"))
                }
                val caBelow = (1 until index).count { chain[it].isCA }
                val limit = cert.pathLen ?: 0
                if (limit >= 0 && caBelow > limit) {
                    return Result.failure(IllegalArgumentException("CA 路径长度超限：${cert.subject}"))
                }
            }
        }

        val rootCaBelow = (1 until chain.size).count { chain[it].isCA }
        val rootLimit = root.pathLen ?: 0
        if (rootLimit >= 0 && rootCaBelow > rootLimit) {
            return Result.failure(IllegalArgumentException("根证书路径长度超限"))
        }
        return Result.success(chain.first())
    }

    private fun keyIdOf(cert: Certificate): String = keyId(cert.publicKey())

    private fun randomSerial(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(16)
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
