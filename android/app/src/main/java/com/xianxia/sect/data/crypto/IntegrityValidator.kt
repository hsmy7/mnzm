package com.xianxia.sect.data.crypto

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import com.xianxia.sect.data.serialization.unified.SaveDataConverter
import com.xianxia.sect.data.serialization.unified.SerializableSaveData
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object IntegrityValidator {
    private const val TAG = "IntegrityValidator"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val SIGNATURE_VERSION = 1
    private const val SIGNATURE_LENGTH = 64
    
    internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    
    fun computeFullDataSignature(data: Any, key: ByteArray): String {
        val dataJson = dataToJsonString(data)
        val canonicalJson = canonicalizeJson(dataJson)
        // 在签名 payload 前添加版本号前缀，防止跨版本 kotlinx.serialization 不兼容
        val payloadWithVersion = "${SIGNATURE_VERSION}|${canonicalJson}"
        return computeHmacHex(payloadWithVersion.toByteArray(Charsets.UTF_8), key)
    }
    
    fun computeFullDataSignatureBytes(data: Any, key: ByteArray): ByteArray {
        val dataJson = dataToJsonString(data)
        val canonicalJson = canonicalizeJson(dataJson)
        // 在签名 payload 前添加版本号前缀，与 computeFullDataSignature() 保持一致
        val payloadWithVersion = "${SIGNATURE_VERSION}|${canonicalJson}"
        return computeHmac(payloadWithVersion.toByteArray(Charsets.UTF_8), key)
    }
    
    fun verifyFullDataSignature(data: Any, expectedSignature: String, key: ByteArray): Boolean {
        val actualSignature = computeFullDataSignature(data, key)
        return actualSignature == expectedSignature
    }
    
    fun computeMerkleRoot(data: Any): String {
        val dataJson = dataToJsonString(data)
        val jsonElement = json.parseToJsonElement(dataJson)
        
        return when (jsonElement) {
            is JsonObject -> computeObjectMerkleRoot(jsonElement)
            is JsonArray -> computeArrayMerkleRoot(jsonElement)
            else -> sha256Hex(dataJson.toByteArray(Charsets.UTF_8))
        }
    }
    
    private fun dataToJsonString(data: Any): String {
        return try {
            json.encodeToString(data)
        } catch (e: Exception) {
            data.toString()
        }
    }

    private fun computeObjectMerkleRoot(obj: JsonObject): String {
        val hashes = obj.entries
            .sortedBy { it.key }
            .map { (key, value) ->
                val valueHash = when (value) {
                    is JsonObject -> computeObjectMerkleRoot(value)
                    is JsonArray -> computeArrayMerkleRoot(value)
                    else -> sha256Hex(value.toString().toByteArray(Charsets.UTF_8))
                }
                sha256Hex((key + ":" + valueHash).toByteArray(Charsets.UTF_8))
            }
        
        return if (hashes.isEmpty()) {
            sha256Hex("{}".toByteArray(Charsets.UTF_8))
        } else {
            sha256Hex(hashes.joinToString("|").toByteArray(Charsets.UTF_8))
        }
    }
    
    private fun computeArrayMerkleRoot(array: JsonArray): String {
        val hashes = array.map { element ->
            when (element) {
                is JsonObject -> computeObjectMerkleRoot(element)
                is JsonArray -> computeArrayMerkleRoot(element)
                else -> sha256Hex(element.toString().toByteArray(Charsets.UTF_8))
            }
        }
        
        return if (hashes.isEmpty()) {
            sha256Hex("[]".toByteArray(Charsets.UTF_8))
        } else {
            sha256Hex(hashes.joinToString("|").toByteArray(Charsets.UTF_8))
        }
    }
    
    fun createSignedPayload(data: Any, key: ByteArray, metadata: Map<String, String> = emptyMap()): SignedPayload {
        val timestamp = System.currentTimeMillis()
        val version = SIGNATURE_VERSION
        
        val dataJson = dataToJsonString(data)
        val dataHash = sha256Hex(dataJson.toByteArray(Charsets.UTF_8))
        val merkleRoot = computeMerkleRoot(data)
        
        val payloadToSign = buildString {
            append(version.toString())
            append("|")
            append(timestamp.toString())
            append("|")
            append(dataHash)
            append("|")
            append(merkleRoot)
            metadata.toSortedMap().forEach { (k, v) ->
                append("|")
                append(k)
                append("=")
                append(v)
            }
        }
        
        val signature = computeHmacHex(payloadToSign.toByteArray(Charsets.UTF_8), key)
        
        return SignedPayload(
            version = version,
            timestamp = timestamp,
            dataHash = dataHash,
            merkleRoot = merkleRoot,
            signature = signature,
            metadata = metadata
        )
    }
    
    fun verifySignedPayload(
        data: Any,
        payload: SignedPayload,
        key: ByteArray,
        maxAgeMs: Long = Long.MAX_VALUE
    ): VerificationResult {
        if (payload.version > SIGNATURE_VERSION) {
            return VerificationResult.Invalid("Unsupported signature version: ${payload.version}")
        }
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - payload.timestamp > maxAgeMs) {
            return VerificationResult.Expired(payload.timestamp, currentTime)
        }
        
        val dataJson = dataToJsonString(data)
        val currentDataHash = sha256Hex(dataJson.toByteArray(Charsets.UTF_8))
        
        if (currentDataHash != payload.dataHash) {
            Log.w(TAG, "Data hash mismatch: expected=${payload.dataHash}, actual=$currentDataHash")
            return VerificationResult.Tampered("Data hash mismatch")
        }
        
        val currentMerkleRoot = computeMerkleRoot(data)
        if (currentMerkleRoot != payload.merkleRoot) {
            Log.w(TAG, "Merkle root mismatch: expected=${payload.merkleRoot}, actual=$currentMerkleRoot")
            return VerificationResult.Tampered("Merkle root mismatch")
        }
        
        val payloadToVerify = buildString {
            append(payload.version.toString())
            append("|")
            append(payload.timestamp.toString())
            append("|")
            append(payload.dataHash)
            append("|")
            append(payload.merkleRoot)
            payload.metadata.toSortedMap().forEach { (k, v) ->
                append("|")
                append(k)
                append("=")
                append(v)
            }
        }
        
        val expectedSignature = computeHmacHex(payloadToVerify.toByteArray(Charsets.UTF_8), key)
        if (!constantTimeEquals(payload.signature, expectedSignature)) {
            Log.w(TAG, "Signature mismatch")
            return VerificationResult.Tampered("Signature mismatch")
        }
        
        return VerificationResult.Valid
    }
    
    private fun computeHmac(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }
    
    private fun computeHmacHex(data: ByteArray, key: ByteArray): String {
        return computeHmac(data, key).joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算字符串的HMAC-SHA256十六进制表示（公共方法，供StorageValidator使用）
     * @param data 要签名的字符串数据
     * @param key HMAC密钥
     * @return 十六进制格式的HMAC签名
     */
    fun computeHmacForString(data: String, key: ByteArray): String {
        return computeHmacHex(data.toByteArray(Charsets.UTF_8), key)
    }
    
    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
    
    private fun canonicalizeJson(jsonStr: String): String {
        return try {
            val element = json.parseToJsonElement(jsonStr)
            canonicalizeElement(element)
        } catch (e: Exception) {
            jsonStr
        }
    }
    
    private fun canonicalizeElement(element: JsonElement): String {
        return when (element) {
            is JsonObject -> {
                val entries = element.entries.sortedBy { it.key }
                val parts = entries.map { "\"${it.key}\":${canonicalizeElement(it.value)}" }
                "{${parts.joinToString(",")}}"
            }
            is JsonArray -> {
                val parts = element.map { canonicalizeElement(it) }
                "[${parts.joinToString(",")}]"
            }
            is JsonPrimitive -> {
                // 对数值类型使用 BigDecimal 统一格式化，确保跨版本确定性
                // 问题：kotlinx.serialization 的 JsonPrimitive.toString() 对浮点数的格式化
                //       在不同版本可能不一致（如 1.0 vs 1.00 vs 1E0）
                // 解决：使用 BigDecimal 强制统一为固定精度格式
                if (element.isString) {
                    // 字符串：保留原始 JSON 转义
                    "\"${element.content}\""
                } else if (element.contentOrNull != null) {
                    val content = element.content
                    // 尝试解析为数值并统一格式
                    try {
                        if (content.contains(".") || content.contains("e") || content.contains("E")) {
                            // 浮点数：使用 BigDecimal 格式化，去除尾随零
                            val bd = BigDecimal(content)
                            // 如果是整数（如 1.0），格式化为整数；否则保留必要小数位
                            if (bd.scale() <= 0 || bd.stripTrailingZeros().scale() <= 0) {
                                bd.toBigInteger().toString()
                            } else {
                                bd.stripTrailingZeros().toPlainString()
                            }
                        } else {
                            // 整数：直接返回（已经是确定性的）
                            content
                        }
                    } catch (e: NumberFormatException) {
                        // 非数值内容（布尔值或 null）：直接返回
                        content
                    }
                } else {
                    // null 值
                    "null"
                }
            }
            else -> element.toString()
        }
    }
    
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) {
            return false
        }
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}

@Serializable
data class SignedPayload(
    val version: Int,
    val timestamp: Long,
    val dataHash: String,
    val merkleRoot: String,
    val signature: String,
    val metadata: Map<String, String> = emptyMap()
) {
    fun toJson(): String {
        return com.xianxia.sect.data.crypto.IntegrityValidator.json.encodeToString(this)
    }
    
    companion object {
        fun fromJson(json: String): SignedPayload? {
            return try {
                com.xianxia.sect.data.crypto.IntegrityValidator.json.decodeFromString<SignedPayload>(json)
            } catch (e: Exception) {
                null
            }
        }
    }
}

sealed class VerificationResult {
    data object Valid : VerificationResult()
    data class Invalid(val reason: String) : VerificationResult()
    data class Expired(val signedAt: Long, val currentTime: Long) : VerificationResult()
    data class Tampered(val reason: String) : VerificationResult()
    
    val isValid: Boolean get() = this is Valid
    val isTampered: Boolean get() = this is Tampered
    val isExpired: Boolean get() = this is Expired
}

data class IntegrityReport(
    val isValid: Boolean,
    val dataHash: String,
    val merkleRoot: String,
    val signatureValid: Boolean,
    val hashValid: Boolean,
    val merkleValid: Boolean,
    val errors: List<String> = emptyList()
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}
