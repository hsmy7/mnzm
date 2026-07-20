package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SpiritRootGeneratorTest {

    // ============================================================
    // generate
    // ============================================================

    @Test
    fun generate_returnsNonBlankString() {
        val result = SpiritRootGenerator.generate(Random(42))
        assertTrue("Expected non-blank result", result.isNotBlank())
    }

    @Test
    fun generate_returnsCommaSeparatedValidElements() {
        val validElements = setOf("metal", "wood", "water", "fire", "earth")
        val result = SpiritRootGenerator.generate(Random(42))
        val elements = result.split(",")
        for (element in elements) {
            assertTrue("Unexpected element: $element", element.trim() in validElements)
        }
    }

    @Test
    fun generate_fixedSeed_isDeterministic() {
        val result1 = SpiritRootGenerator.generate(Random(999))
        val result2 = SpiritRootGenerator.generate(Random(999))
        assertEquals(result1, result2)
    }

    @Test
    fun generate_produces1To5RootTypes() {
        for (seed in 0..50) {
            val result = SpiritRootGenerator.generate(Random(seed.toLong()))
            val count = result.split(",").size
            assertTrue("Expected 1-5 root types, got $count for seed $seed", count in 1..5)
        }
    }

    @Test
    fun generate_allElementsAreValid() {
        val validElements = setOf("metal", "wood", "water", "fire", "earth")
        for (seed in 0..20) {
            val result = SpiritRootGenerator.generate(Random(seed.toLong()))
            val elements = result.split(",").map { it.trim() }
            for (element in elements) {
                assertTrue("Invalid element: $element", element in validElements)
            }
        }
    }

    @Test
    fun generate_differentSeeds_produceDifferentResults() {
        val results = (1..50).map { SpiritRootGenerator.generate(Random(it.toLong())) }
        val uniqueResults = results.toSet()
        assertTrue("Expected multiple different results, got ${uniqueResults.size}", uniqueResults.size > 1)
    }

    // ============================================================
    // generateWithGameRandom
    // ============================================================

    @Test
    fun generateWithGameRandom_returnsNonBlankString() {
        GameRandom.setSeed(42)
        val result = SpiritRootGenerator.generateWithGameRandom()
        assertTrue("Expected non-blank result", result.isNotBlank())
    }

    @Test
    fun generateWithGameRandom_returnsValidElements() {
        val validElements = setOf("metal", "wood", "water", "fire", "earth")
        GameRandom.setSeed(42)
        val result = SpiritRootGenerator.generateWithGameRandom()
        val elements = result.split(",").map { it.trim() }
        for (element in elements) {
            assertTrue("Unexpected element: $element", element in validElements)
        }
    }

    @Test
    fun generateWithGameRandom_sameSeed_isDeterministic() {
        GameRandom.setSeed(12345)
        val result1 = SpiritRootGenerator.generateWithGameRandom()

        GameRandom.setSeed(12345)
        val result2 = SpiritRootGenerator.generateWithGameRandom()

        assertEquals(result1, result2)
    }

    // ============================================================
    // 概率分布守卫测试（对抗性审查补充）
    // ============================================================

    @Test
    fun `generateRandomSpiritRootCount 分布接近配置权重`() {
        GameRandom.setSeed(123)
        val counts = mutableMapOf(1 to 0, 2 to 0, 3 to 0, 4 to 0, 5 to 0)
        val N = 100000
        repeat(N) {
            val count = GameConfig.SpiritRoot.generateRandomSpiritRootCount()
            counts[count] = counts.getOrDefault(count, 0) + 1
        }

        val expected = GameConfig.SpiritRoot.COUNT_WEIGHTS
        val tolerance = 0.05
        for ((rootCount, weight) in expected) {
            val ratio = counts[rootCount]!!.toDouble() / N
            assertTrue(
                "灵根$rootCount 比例 $ratio 偏离配置权重 $weight 超过 $tolerance",
                kotlin.math.abs(ratio - weight) <= tolerance + 0.001
            )
        }
    }

    @Test
    fun `generate 路径分布接近配置权重`() {
        val rng = DeterministicRng.fromSeed(42)
        val kotlinRng = rng.asKotlinRandom()
        val counts = mutableMapOf(1 to 0, 2 to 0, 3 to 0, 4 to 0, 5 to 0)
        val N = 100000
        repeat(N) {
            val count = SpiritRootGenerator.generate(kotlinRng).split(",").size
            counts[count] = counts.getOrDefault(count, 0) + 1
        }

        val expected = GameConfig.SpiritRoot.COUNT_WEIGHTS
        val tolerance = 0.05
        for ((rootCount, weight) in expected) {
            val ratio = counts[rootCount]!!.toDouble() / N
            assertTrue(
                "灵根$rootCount generate() 比例 $ratio 偏离配置权重 $weight 超过 $tolerance",
                kotlin.math.abs(ratio - weight) <= tolerance + 0.001
            )
        }
    }
}
