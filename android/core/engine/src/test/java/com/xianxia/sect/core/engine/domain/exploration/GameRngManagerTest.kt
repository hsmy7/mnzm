package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import org.junit.Assert.*
import org.junit.Test

class GameRngManagerTest {

    @Test
    fun `two separate managers with same seed produce same sequence`() {
        val mgr1 = GameRngManager(); mgr1.initSystemSeed(42)
        val mgr2 = GameRngManager(); mgr2.initSystemSeed(42)

        val seq1 = (1..20).map { mgr1.getRng(RngPartition.EXPLORATION).nextInt(100) }
        val seq2 = (1..20).map { mgr2.getRng(RngPartition.EXPLORATION).nextInt(100) }

        assertEquals(seq1, seq2)
    }

    @Test
    fun `different partitions produce different sequences`() {
        val mgr = GameRngManager(); mgr.initSystemSeed(42)
        val seqExpl = (1..10).map { mgr.getRng(RngPartition.EXPLORATION).nextInt(100) }
        val seqBattle = (1..10).map { mgr.getRng(RngPartition.BATTLE).nextInt(100) }
        assertNotEquals(seqExpl, seqBattle)
    }

    @Test
    fun `export and restore round trip`() {
        val mgr1 = GameRngManager(); mgr1.initSystemSeed(42)
        val rng1 = mgr1.getRng(RngPartition.EXPLORATION)
        repeat(5) { rng1.nextInt(100) }
        val states = mgr1.exportStates()
        val expected = rng1.nextInt(100)

        val mgr2 = GameRngManager(); mgr2.initSystemSeed(42)
        mgr2.restoreStates(states)
        val actual = mgr2.getRng(RngPartition.EXPLORATION).nextInt(100)

        assertEquals(expected, actual)
    }

    @Test
    fun `initSystemSeed resets all partitions`() {
        val mgr = GameRngManager(); mgr.initSystemSeed(42)
        val v1 = mgr.getRng(RngPartition.EXPLORATION).nextInt(100)
        mgr.initSystemSeed(99)
        val v2 = mgr.getRng(RngPartition.EXPLORATION).nextInt(100)
        assertNotEquals(v1, v2)
    }

    @Test
    fun `exportStates returns all partitions`() {
        val mgr = GameRngManager(); mgr.initSystemSeed(42)
        assertEquals(RngPartition.values().size, mgr.exportStates().size)
    }
}
