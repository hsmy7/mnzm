package com.xianxia.sect.data.serialization.backwardcompat

import android.util.Log
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.*
import com.xianxia.sect.core.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OldSaveFormatDeserializer @Inject constructor(
    private val serializationEngine: UnifiedSerializationEngine
) {
    companion object {
        private const val TAG = "OldSaveFormat"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true   // 空列表也编码，新 SaveData 要求必填
        }
    }

    fun tryDeserialize(data: ByteArray): SaveData? {
        val oldSaveData = tryDecodeAsOldFormat(data) ?: return null
        return convertToNewFormat(oldSaveData)
    }

    /**
     * 用纯旧类型解码（精确匹配旧 SerializableSaveData 格式）。
     */
    private fun tryDecodeAsOldFormat(data: ByteArray): SerializableSaveData? {
        return try {
            val context = SerializationContext(
                format = SerializationFormat.PROTOBUF,
                compression = CompressionType.NONE,
                includeChecksum = false
            )
            val result = serializationEngine.deserialize<SerializableSaveData>(
                data, context, SerializableSaveData.serializer()
            )
            if (result.isSuccess && result.data != null) {
                Log.i(TAG, "旧格式反序列化成功")
                result.data
            } else {
                Log.w(TAG, "旧格式反序列化返回空")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "旧格式反序列化失败", e)
            null
        }
    }

    /**
     * 用 JSON 桥接 + 手工修复将旧格式转换为新格式。
     *
     * 思路：旧 Serializable* 和新域类型字段名/结构完全相同（代表同一份数据），
     * 通过 JSON 作为中间格式做自动字段名映射。仅 equipment/manuals 两个字段
     * 在重构中拆分重组，需手工转换。
     */
    private fun convertToNewFormat(old: SerializableSaveData): SaveData? {
        return try {
            // 1. 旧类型 → JSON（字段名映射）
            val jsonRoot = json.encodeToJsonElement(SerializableSaveData.serializer(), old).jsonObject

            // 2. 拆旧 equipment → 新 equipmentInstances（类型不同，name-driven JSON不够）
            val equipmentInstancesJson = buildJsonArray(old.equipment) { it.toEquipmentInstanceJson() }
            val manualInstancesJson = buildJsonArray(old.manuals) { it.toManualInstanceJson() }

            // 3. 构建新的 JSON 对象（去掉旧 equipment/manuals，加上新 equipmentInstances/manualInstances/Stacks）
            val newJson = JsonObject(mutableMapOf<String, kotlinx.serialization.json.JsonElement>().apply {
                // 复制所有旧字段
                for ((key, value) in jsonRoot) {
                    when (key) {
                        "equipment", "manuals" -> { /* 跳过，用新的代替 */ }
                        else -> put(key, value)
                    }
                }
                // 添加新格式字段
                put("equipmentInstances", equipmentInstancesJson)
                put("manualInstances", manualInstancesJson)
                put("equipmentStacks", kotlinx.serialization.json.JsonArray(emptyList()))
                put("manualStacks", kotlinx.serialization.json.JsonArray(emptyList()))
            })

            // 4. JSON → 新 SaveData（ignoreUnknownKeys 确保旧专属字段不崩溃）
            var result = json.decodeFromString(SaveData.serializer(), newJson.toString())

            Log.i(TAG, "旧格式→新格式转换成功")
            result
        } catch (e: Exception) {
            Log.e(TAG, "JSON bridge 转换失败", e)
            null
        }
    }

    private fun <T> buildJsonArray(
        items: List<T>,
        converter: (T) -> kotlinx.serialization.json.JsonElement
    ): kotlinx.serialization.json.JsonArray {
        return kotlinx.serialization.json.JsonArray(items.map { converter(it) })
    }

    // ==================== Equipment JSON 转换 ====================

    private fun SerializableEquipment.toEquipmentInstanceJson(): kotlinx.serialization.json.JsonObject {
        val s = stats
        return JsonObject(mutableMapOf(
            "id" to id.json,
            "name" to name.json,
            "rarity" to rarity.json,
            "description" to description.json,
            "slot" to parseSlot(type).name.json,
            "physicalAttack" to (s["physicalAttack"] ?: 0).json,
            "magicAttack" to (s["magicAttack"] ?: 0).json,
            "physicalDefense" to (s["physicalDefense"] ?: 0).json,
            "magicDefense" to (s["magicDefense"] ?: 0).json,
            "speed" to (s["speed"] ?: 0).json,
            "hp" to (s["hp"] ?: 0).json,
            "mp" to (s["mp"] ?: 0).json,
            "critChance" to critChance.json,
            "minRealm" to minRealm.json,
            "ownerId" to (ownerId.ifEmpty { null }?.json ?: kotlinx.serialization.json.JsonNull),
            "isEquipped" to isEquipped.json,
            "nurtureLevel" to nurtureLevel.json,
            "nurtureProgress" to nurtureProgress.json
        ))
    }

    // ==================== Manual JSON 转换 ====================

    private fun SerializableManual.toManualInstanceJson(): kotlinx.serialization.json.JsonObject {
        return JsonObject(mutableMapOf(
            "id" to id.json,
            "name" to name.json,
            "rarity" to rarity.json,
            "description" to description.json,
            "type" to parseManualType(type).name.json,
            "stats" to (stats ?: emptyMap<String, Int>()).let {
                kotlinx.serialization.json.JsonObject(it.mapValues { (_, v) -> v.json })
            },
            "skillName" to (skillName.ifEmpty { null }?.json ?: kotlinx.serialization.json.JsonNull),
            "skillDescription" to (skillDescription.ifEmpty { null }?.json ?: kotlinx.serialization.json.JsonNull),
            "skillType" to skillType.json,
            "skillDamageType" to skillDamageType.json,
            "skillHits" to skillHits.json,
            "skillDamageMultiplier" to skillDamageMultiplier.json,
            "skillCooldown" to skillCooldown.json,
            "skillMpCost" to skillMpCost.json,
            "skillHealPercent" to skillHealPercent.json,
            "skillHealFixed" to skillHealFixed.json,
            "skillHealType" to skillHealType.json,
            "skillBuffType" to (skillBuffType.ifEmpty { null }?.json ?: kotlinx.serialization.json.JsonNull),
            "skillBuffValue" to skillBuffValue.json,
            "skillBuffDuration" to skillBuffDuration.json,
            "skillIsAoe" to skillIsAoe.json,
            "minRealm" to minRealm.json,
            "ownerId" to (ownerId.ifEmpty { null }?.json ?: kotlinx.serialization.json.JsonNull)
        ))
    }

    // ==================== 类型映射辅助 ====================

    private fun parseSlot(type: String): EquipmentSlot = when (type.lowercase()) {
        "weapon" -> EquipmentSlot.WEAPON
        "armor" -> EquipmentSlot.ARMOR
        "boots" -> EquipmentSlot.BOOTS
        "accessory" -> EquipmentSlot.ACCESSORY
        else -> EquipmentSlot.WEAPON
    }

    private fun parseManualType(type: String): ManualType = when (type.lowercase()) {
        "attack" -> ManualType.ATTACK
        "defense" -> ManualType.DEFENSE
        "support" -> ManualType.SUPPORT
        "mind" -> ManualType.MIND
        else -> ManualType.MIND
    }
}

// ==================== JSON Element 扩展 ====================

private val String.json: kotlinx.serialization.json.JsonPrimitive get() = kotlinx.serialization.json.JsonPrimitive(this)
private val Int.json: kotlinx.serialization.json.JsonPrimitive get() = kotlinx.serialization.json.JsonPrimitive(this)
private val Long.json: kotlinx.serialization.json.JsonPrimitive get() = kotlinx.serialization.json.JsonPrimitive(this)
private val Double.json: kotlinx.serialization.json.JsonPrimitive get() = kotlinx.serialization.json.JsonPrimitive(this)
private val Boolean.json: kotlinx.serialization.json.JsonPrimitive get() = kotlinx.serialization.json.JsonPrimitive(this)
