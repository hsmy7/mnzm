package com.xianxia.sect.data.crypto

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xianxia.sect.data.GsonConfig
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object IntegrityValidator {
    private const val TAG = "IntegrityValidator"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val SIGNATURE_VERSION = 1
    private const val SIGNATURE_LENGTH = 64
    
    private val gson: Gson by lazy { GsonConfig.createGson() }
    
    fun computeFullDataSignature(data: Any, key: ByteArray): String {
        val fullJson = gson.toJson(data)
        val canonicalJson = canonicalizeJson(fullJson)
        return computeHmacHex(canonicalJson.toByteArray(Charsets.UTF_8), key)
    }
    
    fun computeFullDataSignatureBytes(data: Any, key: ByteArray): ByteArray {
        val fullJson = gson.toJson(data)
        val canonicalJson = canonicalizeJson(fullJson)
        return computeHmac(canonicalJson.toByteArray(Charsets.UTF_8), key)
    }
    
    fun verifyFullDataSignature(data: Any, expectedSignature: String, key: ByteArray): Boolean {
        val actualSignature = computeFullDataSignature(data, key)
        return actualSignature == expectedSignature
    }
    
    fun computeMerkleRoot(data: Any): String {
        val json = gson.toJson(data)
        val jsonElement = JsonParser.parseString(json)
        
        return when {
            jsonElement.isJsonObject -> computeObjectMerkleRoot(jsonElement.asJsonObject)
            jsonElement.isJsonArray -> computeArrayMerkleRoot(jsonElement.asJsonArray)
            else -> sha256Hex(json.toByteArray(Charsets.UTF_8))
        }
    }
    
    private fun computeObjectMerkleRoot(obj: JsonObject): String {
        val hashes = obj.entrySet()
            .sortedBy { it.key }
            .map { (key, value) ->
                val valueHash = when {
                    value.isJsonObject -> computeObjectMerkleRoot(value.asJsonObject)
                    value.isJsonArray -> computeArrayMerkleRoot(value.asJsonArray)
                    value.isJsonPrimitive -> sha256Hex(value.toString().toByteArray(Charsets.UTF_8))
                    else -> sha256Hex("null".toByteArray(Charsets.UTF_8))
                }
                sha256Hex((key + ":" + valueHash).toByteArray(Charsets.UTF_8))
            }
        
        return if (hashes.isEmpty()) {
            sha256Hex("{}".toByteArray(Charsets.UTF_8))
        } else {
            sha256Hex(hashes.joinToString("|").toByteArray(Charsets.UTF_8))
        }
    }
    
    private fun computeArrayMerkleRoot(array: com.google.gson.JsonArray): String {
        val hashes = array.map { element ->
            when {
                element.isJsonObject -> computeObjectMerkleRoot(element.asJsonObject)
                element.isJsonArray -> computeArrayMerkleRoot(element.asJsonArray)
                element.isJsonPrimitive -> sha256Hex(element.toString().toByteArray(Charsets.UTF_8))
                else -> sha256Hex("null".toByteArray(Charsets.UTF_8))
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
        
        val dataJson = gson.toJson(data)
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
        
        val dataJson = gson.toJson(data)
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
        if (payload.signature != expectedSignature) {
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
    
    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
    
    private fun canonicalizeJson(json: String): String {
        return try {
            val element = JsonParser.parseString(json)
            canonicalizeElement(element)
        } catch (e: Exception) {
            json
        }
    }
    
    private fun canonicalizeElement(element: JsonElement): String {
        return when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val entries = obj.entrySet().sortedBy { it.key }
                val parts = entries.map { "\"${it.key}\":${canonicalizeElement(it.value)}" }
                "{${parts.joinToString(",")}}"
            }
            element.isJsonArray -> {
                val arr = element.asJsonArray
                val parts = arr.map { canonicalizeElement(it) }
                "[${parts.joinToString(",")}]"
            }
            element.isJsonPrimitive -> element.toString()
            else -> "null"
        }
    }
}

data class SignedPayload(
    val version: Int,
    val timestamp: Long,
    val dataHash: String,
    val merkleRoot: String,
    val signature: String,
    val metadata: Map<String, String> = emptyMap()
) {
    fun toJson(): String {
        return com.xianxia.sect.data.GsonConfig.createGson().toJson(this)
    }
    
    companion object {
        fun fromJson(json: String): SignedPayload? {
            return try {
                com.xianxia.sect.data.GsonConfig.createGson().fromJson(json, SignedPayload::class.java)
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
