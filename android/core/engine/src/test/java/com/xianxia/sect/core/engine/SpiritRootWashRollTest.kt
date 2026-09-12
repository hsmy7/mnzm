package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.DeterministicRng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiritRootWashRollTest {

    /** 暴力扫种子找到能产出单灵根/双灵根的种子（保证用例不依赖特定种子实现细节） */
    private fun findSeedForRootCount(targetCount: Int, pityCount: Int = 0): Long {
        var seed = 1L
        while (seed < 100_000L) {
            val roll = rollSpiritRootWash(DeterministicRng.fromSeed(seed), pityCount)
            if (roll.rootType.split(",").size == targetCount) return seed
            seed++
        }
        throw AssertionError("未找到产出 $targetCount 灵根的种子")
    }

    @Test
    fun `rollSpiritRootWash - pityCount达阈值时强制单灵根且仅消耗元素洗牌draw`() {
        // 任意种子，保底路径结果不依赖随机值（概率判定被跳过）
        val seed = 20260808L
        val rng = DeterministicRng.fromSeed(seed)
        val roll = rollSpiritRootWash(rng, GameConfig.SpiritRoot.WASH_PITY_THRESHOLD)
        assertEquals(1, roll.rootType.split(",").size)
        assertEquals(0, roll.newPityCount)
        // draw 次数固定 = 5 次 nextInt（5 元素洗牌），未消耗概率判定 nextDouble：
        // 与手动执行 5 次 nextInt 后的 rng 状态完全一致
        val reference = DeterministicRng.fromSeed(seed)
        repeat(5) { reference.nextInt() }
        assertEquals(reference.snapshot(), rng.snapshot())
    }

    @Test
    fun `rollSpiritRootWash - 未达阈值时产出单或双灵根`() {
        val doubleSeed = findSeedForRootCount(2)
        val singleSeed = findSeedForRootCount(1)
        assertEquals(2, rollSpiritRootWash(DeterministicRng.fromSeed(doubleSeed), 0).rootType.split(",").size)
        assertEquals(1, rollSpiritRootWash(DeterministicRng.fromSeed(singleSeed), 0).rootType.split(",").size)
    }

    @Test
    fun `rollSpiritRootWash - 单灵根后保底计数归零`() {
        val singleSeed = findSeedForRootCount(1, pityCount = 1)
        val roll = rollSpiritRootWash(DeterministicRng.fromSeed(singleSeed), 1)
        assertEquals(1, roll.rootType.split(",").size)
        assertEquals(0, roll.newPityCount)
    }

    @Test
    fun `rollSpiritRootWash - 双灵根后保底计数递增`() {
        val doubleSeed = findSeedForRootCount(2, pityCount = 1)
        val roll = rollSpiritRootWash(DeterministicRng.fromSeed(doubleSeed), 1)
        assertEquals(2, roll.rootType.split(",").size)
        assertEquals(2, roll.newPityCount)
    }

    @Test
    fun `rollSpiritRootWash - 产物元素合法且不重复`() {
        var seed = 1L
        while (seed < 5_000L) {
            val roll = rollSpiritRootWash(DeterministicRng.fromSeed(seed), 0)
            val elements = roll.rootType.split(",")
            assertEquals(elements.size, elements.toSet().size)
            assertTrue(
                "非法元素: ${roll.rootType}",
                elements.all { it in GameConfig.SpiritRoot.WASH_ELEMENT_KEYS }
            )
            seed++
        }
    }

    @Test
    fun `rollSpiritRootWash - 固定种子相同输入结果确定`() {
        val rng1 = DeterministicRng.fromSeed(20260808L)
        val rng2 = DeterministicRng.fromSeed(20260808L)
        repeat(20) {
            val roll1 = rollSpiritRootWash(rng1, it % 3)
            val roll2 = rollSpiritRootWash(rng2, it % 3)
            assertEquals(roll1, roll2)
        }
    }

    @Test
    fun `rollSpiritRootWash - 统计概率接近60比40`() {
        // 大样本统计：双灵根比例应接近 0.60（宽松区间 0.5~0.7，防偶发抖动）
        val rng = DeterministicRng.fromSeed(20260808L)
        var doubleCount = 0
        val samples = 10_000
        repeat(samples) {
            val roll = rollSpiritRootWash(rng, 0)
            if (roll.rootType.split(",").size == 2) doubleCount++
        }
        val ratio = doubleCount.toDouble() / samples
        assertTrue("双灵根比例 $ratio 偏离 0.6", ratio in 0.50..0.70)
    }

    @Test
    fun `rollSpiritRootWash - 未达阈值时draw次数固定6次`() {
        // draw 次数守卫：普通路径 = 1 次 nextDouble（内部 1 次 nextInt）+ 5 次洗牌 nextInt
        // = 6 次状态推进。无论产物单双，draw 次数必须恒定（确定性 RNG 要求，
        // 同种子同输入序列完全一致）；多种子覆盖单/双灵根两种分支路径
        repeat(20) { seedIdx ->
            val seed = 20260808L + seedIdx
            val rng = DeterministicRng.fromSeed(seed)
            val roll = rollSpiritRootWash(rng, 0)
            val reference = DeterministicRng.fromSeed(seed)
            repeat(6) { reference.nextInt() }
            assertEquals(
                "种子 $seed 产物=${roll.rootType} draw 次数应固定为 6",
                reference.snapshot(), rng.snapshot()
            )
        }
    }

    @Test
    fun `洗炼元素表与游戏真实元素表保持一致`() {
        // 守卫：WASH_ELEMENT_KEYS 是洗炼产物的候选元素集合，必须与游戏真实灵根元素
        // （SpiritRootGenerator.ELEMENTS，private 不可跨模块引用，此处硬编码镜像）一致。
        // 游戏元素集合变化（新增/删除/改名）时此测试失败，提示同步
        // GameConfig.SpiritRoot.WASH_ELEMENT_KEYS（新增元素还需同步 TYPES 配色表）。
        assertEquals(
            "WASH_ELEMENT_KEYS 与 SpiritRootGenerator.ELEMENTS 漂移——请同步 GameConfig.SpiritRoot.WASH_ELEMENT_KEYS",
            listOf("metal", "wood", "water", "fire", "earth"),
            GameConfig.SpiritRoot.WASH_ELEMENT_KEYS
        )
    }
}
