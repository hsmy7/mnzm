package com.xianxia.sect

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test

class RarityConfigTest {
    // ============================================================
    // Rarity 对象 - CONFIGS 基本结构
    // ============================================================

    @Test
    fun `稀有度配置应包含6个条目`() {
        assertEquals(6, GameConfig.Rarity.CONFIGS.size)
    }

    @Test
    fun `稀有度配置的key应为1到6`() {
        val keys = GameConfig.Rarity.CONFIGS.keys
        for (i in 1..6) {
            assertTrue("缺少key $i", keys.contains(i))
        }
    }

    // ============================================================
    // Rarity 对象 - get 方法
    // ============================================================

    @Test
    fun `传入有效稀有度1应返回凡品配置`() {
        val config = GameConfig.Rarity.get(1)
        assertEquals(1, config.level)
        assertEquals("凡品", config.name)
    }

    @Test
    fun `传入有效稀有度2应返回灵品配置`() {
        val config = GameConfig.Rarity.get(2)
        assertEquals(2, config.level)
        assertEquals("灵品", config.name)
    }

    @Test
    fun `传入有效稀有度3应返回宝品配置`() {
        val config = GameConfig.Rarity.get(3)
        assertEquals(3, config.level)
        assertEquals("宝品", config.name)
    }

    @Test
    fun `传入有效稀有度4应返回玄品配置`() {
        val config = GameConfig.Rarity.get(4)
        assertEquals(4, config.level)
        assertEquals("玄品", config.name)
    }

    @Test
    fun `传入有效稀有度5应返回地品配置`() {
        val config = GameConfig.Rarity.get(5)
        assertEquals(5, config.level)
        assertEquals("地品", config.name)
    }

    @Test
    fun `传入有效稀有度6应返回天品配置`() {
        val config = GameConfig.Rarity.get(6)
        assertEquals(6, config.level)
        assertEquals("天品", config.name)
    }

    @Test
    fun `传入无效稀有度99应返回默认凡品配置`() {
        val config = GameConfig.Rarity.get(99)
        assertEquals("凡品", config.name)
        assertEquals(1.0, config.multiplier, 0.001)
    }

    @Test
    fun `传入无效稀有度0应返回默认凡品配置`() {
        val config = GameConfig.Rarity.get(0)
        assertEquals("凡品", config.name)
    }

    @Test
    fun `传入负数稀有度应返回默认凡品配置`() {
        val config = GameConfig.Rarity.get(-1)
        assertEquals("凡品", config.name)
    }

    // ============================================================
    // Rarity 对象 - getName 方法
    // ============================================================

    @Test
    fun `getName传入1应返回凡品`() {
        assertEquals("凡品", GameConfig.Rarity.getName(1))
    }

    @Test
    fun `getName传入2应返回灵品`() {
        assertEquals("灵品", GameConfig.Rarity.getName(2))
    }

    @Test
    fun `getName传入3应返回宝品`() {
        assertEquals("宝品", GameConfig.Rarity.getName(3))
    }

    @Test
    fun `getName传入4应返回玄品`() {
        assertEquals("玄品", GameConfig.Rarity.getName(4))
    }

    @Test
    fun `getName传入5应返回地品`() {
        assertEquals("地品", GameConfig.Rarity.getName(5))
    }

    @Test
    fun `getName传入6应返回天品`() {
        assertEquals("天品", GameConfig.Rarity.getName(6))
    }

    @Test
    fun `getName传入无效值应返回凡品`() {
        assertEquals("凡品", GameConfig.Rarity.getName(99))
    }

    // ============================================================
    // Rarity 对象 - getColor 方法
    // ============================================================

    @Test
    fun `getColor传入1应返回灰色`() {
        assertEquals("#b8b8b8", GameConfig.Rarity.getColor(1))
    }

    @Test
    fun `getColor传入2应返回绿色`() {
        assertEquals("#afcb8a", GameConfig.Rarity.getColor(2))
    }

    @Test
    fun `getColor传入3应返回蓝色`() {
        assertEquals("#9fc2ee", GameConfig.Rarity.getColor(3))
    }

    @Test
    fun `getColor传入4应返回紫色`() {
        assertEquals("#c0a2dd", GameConfig.Rarity.getColor(4))
    }

    @Test
    fun `getColor传入5应返回橙色`() {
        assertEquals("#e7c67d", GameConfig.Rarity.getColor(5))
    }

    @Test
    fun `getColor传入6应返回红色`() {
        assertEquals("#e3a0a0", GameConfig.Rarity.getColor(6))
    }

    // ============================================================
    // Rarity 对象 - multiplier 值递增验证
    // ============================================================

    @Test
    fun `凡品倍率应为1点0`() {
        assertEquals(1.0, GameConfig.Rarity.get(1).multiplier, 0.001)
    }

    @Test
    fun `灵品倍率应为1点3`() {
        assertEquals(1.3, GameConfig.Rarity.get(2).multiplier, 0.001)
    }

    @Test
    fun `宝品倍率应为1点6`() {
        assertEquals(1.6, GameConfig.Rarity.get(3).multiplier, 0.001)
    }

    @Test
    fun `玄品倍率应为2点0`() {
        assertEquals(2.0, GameConfig.Rarity.get(4).multiplier, 0.001)
    }

    @Test
    fun `地品倍率应为2点5`() {
        assertEquals(2.5, GameConfig.Rarity.get(5).multiplier, 0.001)
    }

    @Test
    fun `天品倍率应为3点2`() {
        assertEquals(3.2, GameConfig.Rarity.get(6).multiplier, 0.001)
    }

    @Test
    fun `稀有度倍率应随等级严格递增`() {
        val multipliers = (1..6).map { GameConfig.Rarity.get(it).multiplier }
        for (i in 0 until multipliers.size - 1) {
            assertTrue(
                "倍率在level ${i + 1}(${multipliers[i]}) 到 ${i + 2}(${multipliers[i + 1]}) 未递增",
                multipliers[i] < multipliers[i + 1]
            )
        }
    }
}
