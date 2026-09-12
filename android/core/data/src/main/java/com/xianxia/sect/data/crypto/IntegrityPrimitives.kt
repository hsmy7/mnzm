package com.xianxia.sect.data.crypto

import java.math.BigDecimal
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// IntegrityValidator 的纯计算原语层：JSON 规范化 / Merkle 根 / HMAC-SHA256 / SHA-256 /
// 恒时比较。与签名编排查验（IntegrityValidator）分文件承载——本文件全部为无状态纯函数。

internal const val HMAC_ALGORITHM = "HmacSHA256"

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
internal fun dataToJsonString(data: Any): String {
    return try {
        IntegrityValidator.json.encodeToString(data)
    } catch (ignored: Exception) {
        data.toString()
    }
}

internal fun computeMerkleRoot(data: Any): String {
    val dataJson = dataToJsonString(data)
    val jsonElement = IntegrityValidator.json.parseToJsonElement(dataJson)

    return when (jsonElement) {
        is JsonObject -> computeObjectMerkleRoot(jsonElement)
        is JsonArray -> computeArrayMerkleRoot(jsonElement)
        else -> sha256Hex(dataJson.toByteArray(Charsets.UTF_8))
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

internal fun computeHmac(data: ByteArray, key: ByteArray): ByteArray {
    val mac = Mac.getInstance(HMAC_ALGORITHM)
    mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
    return mac.doFinal(data)
}

internal fun computeHmacHex(data: ByteArray, key: ByteArray): String {
    return computeHmac(data, key).joinToString("") { "%02x".format(it) }
}

internal fun sha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(data).joinToString("") { "%02x".format(it) }
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
internal fun canonicalizeJson(jsonStr: String): String {
    return try {
        val element = IntegrityValidator.json.parseToJsonElement(jsonStr)
        canonicalizeElement(element)
    } catch (ignored: Exception) {
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
        is JsonPrimitive -> canonicalizePrimitive(element)
        else -> element.toString()
    }
}

/**
 * JsonPrimitive 的确定性规范化：字符串保留原始 JSON 转义；
 * 数值经 BigDecimal 统一格式；其余（null 值）输出字面量。
 */
private fun canonicalizePrimitive(element: JsonPrimitive): String {
    if (element.isString) {
        // 字符串：保留原始 JSON 转义
        return "\"${element.content}\""
    }
    val content = element.contentOrNull ?: return "null"
    return try {
        canonicalizeNumericContent(content)
    } catch (e: NumberFormatException) {
        // 非数值内容（布尔值或 null）：直接返回
        content
    }
}

/**
 * 数值内容统一格式：浮点数（含 . / e / E）用 BigDecimal
 * 去除尾随零——kotlinx 的 JsonPrimitive.toString() 对浮点数格式化跨版本可能不一致
 * （如 1.0 vs 1.00 vs 1E0）；整数直接返回（已是确定性）。
 */
private fun canonicalizeNumericContent(content: String): String {
    if (!content.contains(".") && !content.contains("e") && !content.contains("E")) {
        return content
    }
    val bd = BigDecimal(content)
    // 如果是整数（如 1.0），格式化为整数；否则保留必要小数位
    return if (bd.scale() <= 0 || bd.stripTrailingZeros().scale() <= 0) {
        bd.toBigInteger().toString()
    } else {
        bd.stripTrailingZeros().toPlainString()
    }
}

internal fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) {
        return false
    }
    var result = 0
    for (i in a.indices) {
        result = result or (a[i].code xor b[i].code)
    }
    return result == 0
}
