package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import java.io.ByteArrayOutputStream
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SaveDataReconciler 堆叠协调测试（2026-08-01 堆叠序列化缺陷修复）。
 *
 * 覆盖：
 * - 新格式（stacksSerialized = true）原样返回
 * - 旧格式从实例重建堆叠并置标记
 * - 序列化往返：堆叠字段真实写入 Protobuf（不再 @Transient 丢失）
 * - 旧格式反序列化（缺字段读默认）语义
 */
class SaveDataReconcilerTest {

    private fun baseSaveData() = SaveData(
        gameData = GameData(),
        disciples = emptyList(),
        pills = emptyList(),
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList(),
            )

    @Test
    fun `reconcileStacks - 新格式原样返回`() {
        val data = baseSaveData().copy(stacksSerialized = true)
        val result = SaveDataReconciler.reconcileStacks(data)
        assertTrue(result.stacksSerialized)
        assertTrue(result.equipmentStacks.isEmpty())
    }

    @Test
    fun `reconcileStacks - 旧格式从游离实例重建并置标记`() {
        val data = baseSaveData().copy(
            stacksSerialized = false,
            equipmentInstances = listOf(
                EquipmentInstance(name = "青锋剑", rarity = 3, slot = EquipmentSlot.WEAPON),
                EquipmentInstance(name = "青锋剑", rarity = 3, slot = EquipmentSlot.WEAPON),
                EquipmentInstance(name = "玄铁甲", rarity = 2, slot = EquipmentSlot.ARMOR, ownerId = "d1", isEquipped = true)
            ),
            manualInstances = listOf(
                ManualInstance(name = "御剑诀", rarity = 3, type = ManualType.ATTACK)
            )
        )
        val result = SaveDataReconciler.reconcileStacks(data)
        assertTrue(result.stacksSerialized)
        assertEquals(1, result.equipmentStacks.size)
        assertEquals(2, result.equipmentStacks[0].quantity)  // 两个游离青锋剑聚合
        assertEquals(1, result.manualStacks.size)
    }

    @Test
    fun `reconcileStacks - 旧格式无游离实例返回空堆叠`() {
        val data = baseSaveData().copy(
            stacksSerialized = false,
            equipmentInstances = listOf(
                EquipmentInstance(name = "青锋剑", rarity = 3, slot = EquipmentSlot.WEAPON, ownerId = "d1", isEquipped = true)
            )
        )
        val result = SaveDataReconciler.reconcileStacks(data)
        assertTrue(result.stacksSerialized)
        assertTrue(result.equipmentStacks.isEmpty())
    }

    @Test
    fun `序列化往返 - 堆叠字段真实写入 Protobuf 不再丢失`() {
        // 守卫：equipmentStacks/manualStacks 曾被标记 @Transient（备份/云存档丢失堆叠），
        // 修复后必须真实序列化——此测试失败即说明字段又被排除出序列化
        val original = baseSaveData().copy(
            stacksSerialized = true,
            equipmentStacks = listOf(
                com.xianxia.sect.core.model.EquipmentStack(name = "青锋剑", rarity = 3, slot = EquipmentSlot.WEAPON, quantity = 5)
            ),
            manualStacks = listOf(
                com.xianxia.sect.core.model.ManualStack(name = "御剑诀", rarity = 3, type = ManualType.ATTACK, quantity = 2)
            )
        )

        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), original)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        assertEquals(1, restored.equipmentStacks.size)
        assertEquals("青锋剑", restored.equipmentStacks[0].name)
        assertEquals(5, restored.equipmentStacks[0].quantity)
        assertEquals(1, restored.manualStacks.size)
        assertEquals(2, restored.manualStacks[0].quantity)
        // 显式编码 true 的往返——验证堆叠字段真实序列化
        assertTrue(restored.stacksSerialized)
    }

    @Test
    fun `显式编码 stacksSerialized=false 反序列化触发重建兜底`() {
        // 注意：显式编码 false 时受 @EncodeDefault(ALWAYS) 影响（encodeDefaults=false
        // 下该字段仍真实写入字节流），并非"缺失字段"——真实缺失字段解码路径
        // 由下方 stripVarintField 测试覆盖
        val legacy = baseSaveData().copy(stacksSerialized = false)
        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), legacy)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        // stacksSerialized = false → 触发重建兜底路径
        assertFalse(restored.stacksSerialized)
        val reconciled = SaveDataReconciler.reconcileStacks(restored)
        assertTrue(reconciled.stacksSerialized)
    }

    @Test
    fun `旧格式反序列化 - 真实缺失字段（剥离 field 55）读默认 stacksSerialized=false`() {
        // 真实模拟旧云存档：物理上不含 field 55 的二进制（kotlinx protobuf 对缺失
        // 字段走默认值——与历史存档可读的事实基础一致）
        val encoded = NullSafeProtoBuf.protoBuf.encodeToByteArray(
            serializer<SaveData>(), baseSaveData().copy(stacksSerialized = false)
        )
        val legacy = stripVarintField(encoded, field = 55)

        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), legacy)
        assertFalse("缺失字段应走默认值 false", restored.stacksSerialized)
        val reconciled = SaveDataReconciler.reconcileStacks(restored)
        assertTrue("协调后应重建并置 true", reconciled.stacksSerialized)
        // 辅助函数只删目标字段：其余字段仍完整
        assertEquals(baseSaveData().gameData, restored.gameData)
    }
}

/** 读取 protobuf varint，返回 (值, 占用字节数)。
 *  不完整（字节耗尽）或超长（>64 位）的 varint 显式抛错，拒绝静默截断。 */
private fun readVarint(bytes: ByteArray, offset: Int): Pair<Long, Int> {
    var result = 0L
    var shift = 0
    var i = offset
    while (true) {
        if (i >= bytes.size) throw IllegalArgumentException("truncated varint at offset=$offset")
        if (shift > 63) throw IllegalArgumentException("varint too long at offset=$offset")
        val b = bytes[i].toInt() and 0xFF
        result = result or ((b and 0x7F).toLong() shl shift)
        i++
        if (b and 0x80 == 0) break
        shift += 7
    }
    return result to (i - offset)
}

/**
 * 剥离顶层指定 varint 字段（wire type 0），模拟旧格式物理上缺失该字段的二进制。
 * 遍历 TLV：跳过 length-delimited/定长字段体，丢弃目标字段（field 号 + wire type 0）。
 */
private fun stripVarintField(bytes: ByteArray, field: Int): ByteArray {
    val kept = ByteArrayOutputStream()
    var i = 0
    while (i < bytes.size) {
        val fieldStart = i
        val (tag, tagLen) = readVarint(bytes, i)
        i += tagLen
        val fieldNum = (tag ushr 3).toInt()
        val wireType = (tag and 0x7).toInt()
        val payloadLen = when (wireType) {
            0 -> readVarint(bytes, i).second // varint 字段的 payload 即值本身
            1 -> 8 // 64-bit
            2 -> {
                // length-delimited：先推进 i 越过长度 varint，再返回内容长度值
                val (len, lenBytes) = readVarint(bytes, i)
                i += lenBytes
                len.toInt()
            }
            5 -> 4 // 32-bit
            else -> throw IllegalArgumentException("unexpected wire type: $wireType")
        }
        if (fieldNum == field && wireType == 0) {
            i += payloadLen // 丢弃目标字段体
            continue
        }
        kept.write(bytes, fieldStart, (i + payloadLen) - fieldStart)
        i += payloadLen
    }
    return kept.toByteArray()
}
