package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.util.DeterministicRng
import org.junit.Assert.*
import org.junit.Test

class DeterministicRngTest {

    @Test
    fun `same seed produces same sequence`() {
        val rng1 = DeterministicRng.fromSeed(42)
        val rng2 = DeterministicRng.fromSeed(42)

        val seq1 = (1..20).map { rng1.nextInt(100) }
        val seq2 = (1..20).map { rng2.nextInt(100) }

        assertEquals(seq1, seq2)
    }

    @Test
    fun `different seed produces different sequence`() {
        val rng1 = DeterministicRng.fromSeed(42)
        val rng2 = DeterministicRng.fromSeed(99)

        val seq1 = (1..10).map { rng1.nextInt(100) }
        val seq2 = (1..10).map { rng2.nextInt(100) }

        assertNotEquals(seq1, seq2)
    }

    @Test
    fun `snapshot and restore preserves state`() {
        val rng = DeterministicRng.fromSeed(42)
        repeat(10) { rng.nextInt(100) }

        val snapshot = rng.snapshot()
        val valBefore = rng.nextInt(100)

        rng.restore(snapshot)
        val valAfter = rng.nextInt(100)

        assertEquals("snapshot/restore should yield same nextInt", valBefore, valAfter)
    }

    @Test
    fun `nextInt within bounds`() {
        val rng = DeterministicRng.fromSeed(42)
        val bound = 10
        repeat(1000) {
            val v = rng.nextInt(bound)
            assertTrue("value $v should be >= 0", v >= 0)
            assertTrue("value $v should be < $bound", v < bound)
        }
    }

    @Test
    fun `nextDouble within 0 to 1`() {
        val rng = DeterministicRng.fromSeed(42)
        repeat(1000) {
            val v = rng.nextDouble()
            assertTrue("value $v should be >= 0.0", v >= 0.0)
            assertTrue("value $v should be < 1.0", v < 1.0)
        }
    }

    @Test
    fun `partition isolation - sequences differ`() {
        val rng1 = DeterministicRng.fromSeed(42)
        val rng2 = DeterministicRng.fromSeed(43)

        val seq1 = (1..5).map { rng1.nextInt(100) }
        val seq2 = (1..5).map { rng2.nextInt(100) }

        assertNotEquals(seq1, seq2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nextInt with zero bound throws`() {
        val rng = DeterministicRng.fromSeed(42)
        rng.nextInt(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nextInt with negative bound throws`() {
        val rng = DeterministicRng.fromSeed(42)
        rng.nextInt(-1)
    }

    @Test
    fun `nextGaussian - same seed produces same sequence`() {
        val rng1 = DeterministicRng.fromSeed(42)
        val rng2 = DeterministicRng.fromSeed(42)
        val seq1 = (1..20).map { rng1.nextGaussian() }
        val seq2 = (1..20).map { rng2.nextGaussian() }
        assertEquals(seq1, seq2)
    }

    @Test
    fun `nextGaussian - values are within reasonable range`() {
        val rng = DeterministicRng.fromSeed(42)
        val values = (1..10000).map { rng.nextGaussian() }
        assertTrue("All values should be finite", values.all { it.isFinite() })
        // 99.7% 应落在 [-3, 3] 范围内（3-sigma）
        val within3Sigma = values.count { it in -3.0..3.0 }
        assertTrue("At least 99% within 3 sigma: $within3Sigma/${values.size}",
            within3Sigma >= values.size * 0.99)
    }

    @Test
    fun `nextGaussian - mean approximates 0`() {
        val rng = DeterministicRng.fromSeed(42)
        val values = (1..100000).map { rng.nextGaussian() }
        val mean = values.average()
        assertTrue("Mean should be near 0: $mean", kotlin.math.abs(mean) < 0.03)
    }

    @Test
    fun `nextGaussian - mean and stddev parameters work`() {
        val rng = DeterministicRng.fromSeed(42)
        val values = (1..1000).map { rng.nextGaussian(50.0, 10.0) }
        val mean = values.average()
        assertTrue("Mean should be near 50: $mean", mean in 45.0..55.0)
    }

    @Test
    fun `serialization round trip`() {
        val rng = DeterministicRng.fromSeed(42)
        repeat(5) { rng.nextInt(100) }

        val state = rng.snapshot()

        val restored = DeterministicRng.fromSeed(0) // different seed
        restored.restore(state)

        assertEquals("restored should produce same value",
            rng.nextInt(100), restored.nextInt(100))
    }
}
