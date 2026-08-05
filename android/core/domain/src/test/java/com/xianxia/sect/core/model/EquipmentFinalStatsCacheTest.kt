package com.xianxia.sect.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2（P1-C）守卫测试：EquipmentInstance.getFinalStats 引用/值语义缓存。
 *
 * 背景：getFinalStats 是属性计算链的内层热点（每弟子每装备调用，91 处
 * getFinalStats 调用点的公共内层）。EquipmentInstance 是不可变 COW 对象，
 * 内容即版本——值语义键缓存（data class equals/hashCode）永不需要失效逻辑。
 * 本测试锁定：
 * 1. 同实例重复调用返回同对象（缓存命中）
 * 2. 同内容不同实例共享缓存（值语义）
 * 3. 内容变化（copy/孕养）重算（缓存正确性）
 */
class EquipmentFinalStatsCacheTest {

    private fun makeInstance(
        id: String = java.util.UUID.randomUUID().toString(),
        name: String = "铁剑",
        physicalAttack: Int = 10,
        nurtureLevel: Int = 0
    ): EquipmentInstance = EquipmentInstance(
        id = id,
        name = name,
        rarity = 1,
        physicalAttack = physicalAttack,
        nurtureLevel = nurtureLevel
    )

    @Test
    fun `same instance repeated calls return same object`() {
        val instance = makeInstance()
        val first = instance.getFinalStats()
        val second = instance.getFinalStats()
        assertSame("缓存命中应返回同对象", first, second)
    }

    @Test
    fun `equal-content instances share cache`() {
        // 同 id（data class 值相等）→ 值语义键命中缓存
        val a = makeInstance(id = "shared-id")
        val b = makeInstance(id = "shared-id")
        assertEquals(a, b)
        assertSame("同内容实例应共享缓存", a.getFinalStats(), b.getFinalStats())
    }

    @Test
    fun `content change recomputes stats`() {
        val base = makeInstance(physicalAttack = 10)
        val baseStats = base.getFinalStats()
        // 孕养提升 → 新实例（copy）→ 最终属性应提升且不与旧缓存冲突
        val nurtured = base.copy(nurtureLevel = 10)
        val nurturedStats = nurtured.getFinalStats()
        assertNotEquals("孕养后属性应变化", baseStats.physicalAttack, nurturedStats.physicalAttack)
        assertTrue("孕养后物攻应更高", baseStats.physicalAttack < nurturedStats.physicalAttack)
    }

    @Test
    fun `nurture multiplier applied correctly`() {
        val instance = makeInstance(physicalAttack = 100)
        assertEquals("无孕养倍率 1.0", 100, instance.getFinalStats().physicalAttack)
        val nurtured = instance.copy(nurtureLevel = 25)
        // 满级孕养倍率上限 4.0
        assertEquals(400, nurtured.getFinalStats().physicalAttack)
    }
}
