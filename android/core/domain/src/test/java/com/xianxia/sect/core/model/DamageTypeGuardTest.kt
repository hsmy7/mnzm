package com.xianxia.sect.core.model

import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DamageType 枚举守卫（五行属性伤害系统 E1，方案 §6）：
 * 活跃值恰 6（物理 + 五行）；退役段 MAGIC 仅存档兼容保留、不得进活跃集；
 * 五行元素 key 与 GameConfig.SpiritRoot.TYPES 一一对应（单一元素真源）。
 */
class DamageTypeGuardTest {

    @Test
    fun `活跃值恰为 6 —— 物理 + 五行`() {
        assertEquals(
            listOf("PHYSICAL", "METAL", "WOOD", "WATER", "FIRE", "EARTH"),
            DamageType.ACTIVE.map { it.name }
        )
        assertEquals(6, DamageType.ACTIVE.size)
    }

    @Test
    fun `MAGIC 为退役段 —— 枚举仍存在但不进活跃集`() {
        // 存档兼容：枚举值不可删（name 序列化），但必须被排除在活跃集外
        assertTrue(DamageType.entries.any { it.name == "MAGIC" })
        assertFalse(DamageType.ACTIVE.contains(DamageType.MAGIC))
        assertFalse(DamageType.ELEMENTAL.contains(DamageType.MAGIC))
        assertNull(DamageType.MAGIC.element)
    }

    @Test
    fun `五行元素 key 与 SpiritRoot TYPES 一一对应`() {
        assertEquals(
            GameConfig.SpiritRoot.TYPES.keys.sorted(),
            DamageType.ELEMENTAL.mapNotNull { it.element }.sorted()
        )
        // 每个元素伤害类型都能映射回 SpiritRoot 配置（颜色/中文名共用同一真源）
        DamageType.ELEMENTAL.forEach { type ->
            assertTrue(GameConfig.SpiritRoot.TYPES.containsKey(type.element))
        }
    }

    @Test
    fun `fromElement 双向映射 —— 未知元素返回 null`() {
        DamageType.ELEMENTAL.forEach { type ->
            assertEquals(type, DamageType.fromElement(type.element))
        }
        assertNull(DamageType.fromElement("wind"))
        assertNull(DamageType.fromElement(null))
        assertNull(DamageType.fromElement("magic"))
    }

    @Test
    fun `物理不受灵根 gate —— elementGate 物理恒 1 元素按集合折算`() {
        // SpiritRoot.elementGate（唯一实现入口）：含 → 1.0；不含 → 0.0
        assertEquals(1.0, SpiritRoot("fire,water").elementGate(null), 0.0)
        assertEquals(1.0, SpiritRoot("fire").elementGate("fire"), 0.0)
        assertEquals(1.0, SpiritRoot("fire,water").elementGate("water"), 0.0)
        assertEquals(1.0, SpiritRoot("fire, water").elementGate("water"), 0.0)
        // 不含 → 恰为 0（开关制，非系数折算，EA1）
        assertEquals(0.0, SpiritRoot("fire").elementGate("water"), 0.0)
        assertEquals(0.0, SpiritRoot("").elementGate("fire"), 0.0)
        assertEquals(0.0, SpiritRoot("metal").elementGate("unknown"), 0.0)
        // 双灵根吃两系
        assertEquals(1.0, SpiritRoot("fire,earth").elementGate("earth"), 0.0)
    }
}
