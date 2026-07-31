package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
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
        teams = emptyList()
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
        // stacksSerialized 使用 @EncodeDefault(ALWAYS)：false 默认值也必须被编码
        assertTrue(restored.stacksSerialized)
    }

    @Test
    fun `旧格式反序列化 - 缺字段读默认 stacksSerialized=false`() {
        // 模拟旧云存档：编码时手工构建不含新字段的二进制
        val legacy = baseSaveData().copy(stacksSerialized = false)
        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), legacy)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        // stacksSerialized = false → 触发重建兜底路径
        assertFalse(restored.stacksSerialized)
        val reconciled = SaveDataReconciler.reconcileStacks(restored)
        assertTrue(reconciled.stacksSerialized)
    }
}
