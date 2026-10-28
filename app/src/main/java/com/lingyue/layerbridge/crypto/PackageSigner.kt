package com.lingyue.layerbridge.crypto

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.security.PrivateKey
import java.time.Instant

data class SignatureBlock(
    val version: Int = 1,
    val algorithm: String = ALGORITHM,
    val keyId: String = "",
    val certChain: List<Certificate> = emptyList(),
    val signature: String = "",
    val signedAt: String? = null,
) {
    companion object {
        const val ALGORITHM = "ecdsa-p256-sha256"
    }
}

object PackageSigner {

    private val gson = Gson()

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    fun lbpaSigningData(manifestBytes: ByteArray, payloadHashHex: String): ByteArray =
        "LBPA-PKG-V1\n${sha256Hex(manifestBytes)}\n${payloadHashHex.lowercase()}\n"
            .toByteArray(Charsets.UTF_8)

    fun debSigningData(debHashHex: String): ByteArray =
        "LB-DEB-V1\n${debHashHex.lowercase()}\n".toByteArray(Charsets.UTF_8)

    fun signLbpa(
        manifestBytes: ByteArray,
        payloadHashHex: String,
        key: PrivateKey,
        keyId: String,
        chain: List<Certificate>,
    ): SignatureBlock {
        val data = lbpaSigningData(manifestBytes, payloadHashHex)
        return SignatureBlock(
            keyId = keyId,
            certChain = chain,
            signature = Certificates.sign(data, key),
            signedAt = Instant.now().toString(),
        )
    }

    fun signDeb(
        debHashHex: String,
        key: PrivateKey,
        keyId: String,
        chain: List<Certificate>,
    ): SignatureBlock {
        val data = debSigningData(debHashHex)
        return SignatureBlock(
            keyId = keyId,
            certChain = chain,
            signature = Certificates.sign(data, key),
            signedAt = Instant.now().toString(),
        )
    }

    fun verifyLbpa(
        block: SignatureBlock,
        manifestBytes: ByteArray,
        payloadHashHex: String,
        root: Certificate,
        packageName: String,
        now: Instant = Instant.now(),
    ): Result<Certificate> = verify(
        block = block,
        data = lbpaSigningData(manifestBytes, payloadHashHex),
        root = root,
        packageName = packageName,
        now = now,
    )

    fun verifyDeb(
        block: SignatureBlock,
        debHashHex: String,
        root: Certificate,
        packageName: String,
        now: Instant = Instant.now(),
    ): Result<Certificate> = verify(
        block = block,
        data = debSigningData(debHashHex),
        root = root,
        packageName = packageName,
        now = now,
    )

    private fun verify(
        block: SignatureBlock,
        data: ByteArray,
        root: Certificate,
        packageName: String,
        now: Instant,
    ): Result<Certificate> {
        if (block.version != 1) return Result.failure(IllegalArgumentException("不支持的签名版本"))
        if (block.algorithm != SignatureBlock.ALGORITHM) {
            return Result.failure(IllegalArgumentException("不支持的签名算法：${block.algorithm}"))
        }
        if (block.signature.isBlank()) return Result.failure(IllegalArgumentException("签名值为空"))

        val signer = Certificates.verifyChain(block.certChain, root, packageName, now)
            .getOrElse { return Result.failure(it) }
        if (block.keyId != signer.keyId) {
            return Result.failure(IllegalArgumentException("签名 keyId 与证书不匹配"))
        }
        if (!Certificates.verify(data, block.signature, signer.publicKey())) {
            return Result.failure(IllegalArgumentException("签名验证失败"))
        }
        return Result.success(signer)
    }

    fun encode(block: SignatureBlock): String = gson.toJson(block)

    fun parseBlock(json: String): SignatureBlock {
        val obj = JsonParser.parseString(json).asJsonObject
        val chain = obj.getAsJsonArray("certChain")?.map {
            gson.fromJson(it, Certificate::class.java)
        }.orEmpty()
        val version = obj.get("version")?.asInt ?: 1
        val algorithm = obj.get("algorithm")?.asString ?: ""
        val keyId = obj.get("keyId")?.asString ?: ""
        val signature = obj.get("signature")?.asString ?: ""
        val signedAt = obj.get("signedAt")?.asString
        if (signature.isBlank()) throw IllegalArgumentException("缺少签名值")
        return SignatureBlock(version, algorithm, keyId, chain, signature, signedAt)
    }
}
