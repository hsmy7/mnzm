package com.xianxia.sect

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test

class SpiritRootConfigTest {
    // ============================================================
    // SpiritRoot 对象 - ELEMENTS 列表
    // ============================================================

    @Test
    fun `五行元素列表应有5个元素`() {
        assertEquals(5, GameConfig.SpiritRoot.ELEMENTS.size)
    }

    @Test
    fun `五行元素应包含金木水火土`() {
        val elements = GameConfig.SpiritRoot.ELEMENTS
        assertTrue(elements.contains("金"))
        assertTrue(elements.contains("木"))
        assertTrue(elements.contains("水"))
        assertTrue(elements.contains("火"))
        assertTrue(elements.contains("土"))
    }

    // ============================================================
    // SpiritRoot 对象 - TYPES map
    // ============================================================

    @Test
    fun `灵根类型映射应有5个条目`() {
        assertEquals(5, GameConfig.SpiritRoot.TYPES.size)
    }

    @Test
    fun `灵根类型应包含metal类型`() {
        assertNotNull(GameConfig.SpiritRoot.TYPES["metal"])
    }

    @Test
    fun `灵根类型应包含wood类型`() {
        assertNotNull(GameConfig.SpiritRoot.TYPES["wood"])
    }

    @Test
    fun `灵根类型应包含water类型`() {
        assertNotNull(GameConfig.SpiritRoot.TYPES["water"])
    }

    @Test
    fun `灵根类型应包含fire类型`() {
        assertNotNull(GameConfig.SpiritRoot.TYPES["fire"])
    }

    @Test
    fun `灵根类型应包含earth类型`() {
        assertNotNull(GameConfig.SpiritRoot.TYPES["earth"])
    }

    // ============================================================
    // SpiritRoot 对象 - get 方法
    // ============================================================

    @Test
    fun `get传入metal应返回金属性配置`() {
        val config = GameConfig.SpiritRoot.get("metal")
        assertEquals("metal", config.type)
        assertEquals("金", config.name)
    }

    @Test
    fun `get传入wood应返回木属性配置`() {
        val config = GameConfig.SpiritRoot.get("wood")
        assertEquals("wood", config.type)
        assertEquals("木", config.name)
    }

    @Test
    fun `get传入water应返回水属性配置`() {
        val config = GameConfig.SpiritRoot.get("water")
        assertEquals("water", config.type)
        assertEquals("水", config.name)
    }

    @Test
    fun `get传入fire应返回火属性配置`() {
        val config = GameConfig.SpiritRoot.get("fire")
        assertEquals("fire", config.type)
        assertEquals("火", config.name)
    }

    @Test
    fun `get传入earth应返回土属性配置`() {
        val config = GameConfig.SpiritRoot.get("earth")
        assertEquals("earth", config.type)
        assertEquals("土", config.name)
    }

    @Test
    fun `get传入无效类型应返回默认metal配置`() {
        val config = GameConfig.SpiritRoot.get("unknown_type")
        assertEquals("metal", config.type)
        assertEquals("金", config.name)
    }

    @Test
    fun `get传入空字符串应返回默认metal配置`() {
        val config = GameConfig.SpiritRoot.get("")
        assertEquals("metal", config.type)
    }

    // ============================================================
    // SpiritRoot 对象 - COUNT_WEIGHTS 权重之和
    // ============================================================

    @Test
    fun `灵根数量权重之和应接近1点0`() {
        val totalWeight = GameConfig.SpiritRoot.COUNT_WEIGHTS.values.sum()
        assertEquals(1.0, totalWeight, 0.001)
    }

    @Test
    fun `灵根数量权重应包含1到5的所有键`() {
        val weights = GameConfig.SpiritRoot.COUNT_WEIGHTS
        for (i in 1..5) {
            assertTrue("缺少权重key $i", weights.containsKey(i))
        }
    }

    // ============================================================
    // SpiritRoot 对象 - generateRandomSpiritRootCount 范围验证
    // ============================================================

    @Test
    fun `随机生成灵根数量应在1到5范围内_单次调用`() {
        val count = GameConfig.SpiritRoot.generateRandomSpiritRootCount()
        assertTrue("生成的灵根数量 $count 应 >= 1", count >= 1)
        assertTrue("生成的灵根数量 $count 应 <= 5", count <= 5)
    }

    @Test
    fun `随机生成灵根数量多次调用均应在范围内`() {
        repeat(100) {
            val count = GameConfig.SpiritRoot.generateRandomSpiritRootCount()
            assertTrue("第${it + 1}次生成: $count 超出范围[1,5]", count in 1..5)
        }
    }

    @Test
    fun `随机生成灵根数量多次调用应覆盖所有可能值`() {
        val results = mutableSetOf<Int>()
        // 灵根数量 1 权重仅 1%（时间种子 GameRandom），500 次采样未覆盖失败率 ≈0.66%（预存 flaky），加至 5000 次
        repeat(5000) {
            results.add(GameConfig.SpiritRoot.generateRandomSpiritRootCount())
        }
        for (expected in 1..5) {
            assertTrue("未覆盖灵根数量 $expected (5000次采样)", results.contains(expected))
        }
    }
}
