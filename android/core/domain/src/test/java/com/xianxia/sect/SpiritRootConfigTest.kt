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
    // SpiritRoot 对象 - rollSpiritRootCount（纯函数）值域与分布验证
    // 随机源由调用方提供：测试用固定种子 DeterministicRng 保证可复现
    // （旧版经 GameRandom 的挂钟种子属不可复现来源，已随对象摘除）
    // ============================================================

    /**
     * 确定性随机值序列：`index / count` 等差铺满 `[0, 1)`——
     * **零外部依赖且必然覆盖全部累积区间**（比种子 PRNG 更稳：不依赖抽样运气）
     */
    private fun deterministicRands(count: Int): List<Double> =
        List(count) { index -> index.toDouble() / count.toDouble() }

    @Test
    fun `灵根数量单次映射应落在1到5范围内`() {
        val count = GameConfig.SpiritRoot.rollSpiritRootCount(0.5)
        assertTrue("映射出的灵根数量 $count 应 >= 1", count >= 1)
        assertTrue("映射出的灵根数量 $count 应 <= 5", count <= 5)
    }

    @Test
    fun `灵根数量对全域随机值均应在范围内`() {
        deterministicRands(100).forEachIndexed { index, rand ->
            val count = GameConfig.SpiritRoot.rollSpiritRootCount(rand)
            assertTrue("第${index + 1}次（rand=$rand）: $count 超出范围[1,5]", count in 1..5)
        }
    }

    @Test
    fun `灵根数量映射应覆盖所有可能值`() {
        val results = mutableSetOf<Int>()
        // 等差铺满 [0,1)：必然命中每个累积区间（旧版按权重 1% 的档位需 5000 次采样才不 flaky）
        deterministicRands(5000).forEach { results.add(GameConfig.SpiritRoot.rollSpiritRootCount(it)) }
        for (expected in 1..5) {
            assertTrue("未覆盖灵根数量 $expected (5000次采样)", results.contains(expected))
        }
    }

    @Test
    fun `灵根数量映射边界值语义 — 0.0 取最小权重档, 接近 1.0 走回退`() {
        // 单调性：随机值越小越可能落在低数量档（COUNT_WEIGHTS 升序累积）
        val smallest = GameConfig.SpiritRoot.rollSpiritRootCount(0.0)
        assertEquals("rand=0.0 应落在首个累积区间", 1, smallest)
        // 权重和 <1.0 时回退 5（域日志告警）；当前配置权重和为 1.0，
        // 此处只断言"恒在值域内"以防未来配置漂移导致越界
        val fallbackProbe = GameConfig.SpiritRoot.rollSpiritRootCount(0.999999)
        assertTrue("rand 接近 1.0 的映射必须仍在 [1,5]：实测 $fallbackProbe", fallbackProbe in 1..5)
    }
}
