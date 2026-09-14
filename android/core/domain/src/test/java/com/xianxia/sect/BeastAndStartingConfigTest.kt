package com.xianxia.sect

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test

class BeastAndStartingConfigTest {
    // ============================================================
    // Beast 对象 - TYPES 列表大小
    // ============================================================

    @Test
    fun `妖兽类型列表大小应为8`() {
        assertEquals(8, GameConfig.Beast.TYPES.size)
    }

    // ============================================================
    // Beast 对象 - getType 边界测试
    // ============================================================

    @Test
    fun `getType传入0应返回虎妖`() {
        val beast = GameConfig.Beast.getType(0)
        assertEquals("虎妖", beast.name)
    }

    @Test
    fun `getType传入1应返回狼妖`() {
        val beast = GameConfig.Beast.getType(1)
        assertEquals("狼妖", beast.name)
    }

    @Test
    fun `getType传入7应返回龟妖`() {
        val beast = GameConfig.Beast.getType(7)
        assertEquals("龟妖", beast.name)
    }

    @Test
    fun `getType传入负数索引应回退到第一个类型虎妖`() {
        val beast = GameConfig.Beast.getType(-1)
        assertEquals("虎妖", beast.name)
    }

    @Test
    fun `getType传入越界索引8应回退到第一个类型虎妖`() {
        val beast = GameConfig.Beast.getType(8)
        assertEquals("虎妖", beast.name)
    }

    @Test
    fun `getType传入极大越界索引99应回退到第一个类型虎妖`() {
        val beast = GameConfig.Beast.getType(99)
        assertEquals("虎妖", beast.name)
    }

    // ============================================================
    // Starting 对象 - RESOURCES
    // ============================================================

    @Test
    fun `初始资源灵石应为2000`() {
        assertEquals(2000, GameConfig.Starting.RESOURCES.spiritStones)
    }

    @Test
    fun `初始资源声望应为100`() {
        assertEquals(100, GameConfig.Starting.RESOURCES.reputation)
    }

    @Test
    fun `初始资源灵草应为50`() {
        assertEquals(50, GameConfig.Starting.RESOURCES.spiritHerbs)
    }
}
