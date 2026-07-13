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
