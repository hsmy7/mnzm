package com.xianxia.sect.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NativeBackedRng/GameRngManager 委托接缝单元测试（纯 JVM Fake 通道）。
 *
 * 守护目标：
 * 1. 委托模式下原始 nextInt 经通道、上层公式（bound 等）本地组合且消耗次数不变
 * 2. exportStates/restoreStates 经通道读写（存档导出与事务回滚守卫的语义基础）
 * 3. detach 往返：分离后本地流从 native 真相源状态无缝续接
 * 4. 委托模式下 initSystemSeed 重播通道种子
 */
class NativeBackedRngTest {

    /** 可编程 Fake 通道：独立驱动各分区流；initSystemSeed 重播全部流 */
    private class FakeChannel(initialSeed: Long = 5_000L) : NativeRngChannel {
        val streams = mutableMapOf<Int, DeterministicRng>()
        val seedCalls = mutableListOf<Long>()
        val restoreCalls = mutableListOf<Int>()
        private var lastSeed = initialSeed

        fun stream(pid: Int): DeterministicRng =
            streams.getOrPut(pid) { DeterministicRng.fromSeed(lastSeed + pid) }

        override fun nextInt(partitionId: Int): Int = stream(partitionId).nextInt()

        override fun snapshot(partitionId: Int): Long = stream(partitionId).snapshot()

        override fun restore(partitionId: Int, state: Long) {
            restoreCalls.add(partitionId)
            stream(partitionId).restore(state)
        }

        override fun initSystemSeed(seed: Long) {
            seedCalls.add(seed)
            lastSeed = seed
            streams.clear()
        }
    }

    @Test
    fun `delegated raw draw goes through channel and bound formula consumes locally`() {
        val channel = FakeChannel()
        val rng: DeterministicRng = NativeBackedRng(RngPartition.SYSTEM.id, channel)
        val pid = RngPartition.SYSTEM.id

        // 原始抽取：先记快照，经委托抽一次后回滚，再用底层流直抽应得同值
        val beforeRaw = channel.snapshot(pid)
        val drawnViaManager = rng.nextInt()
        channel.restore(pid, beforeRaw)
        assertEquals(drawnViaManager, channel.stream(pid).nextInt())

        // bound 公式：以同一起点状态驱动本地参照流，委托实例必须产出同值
        //（子类继承父类 nextInt(bound)，等价性证明委托接线 + 消耗次数一致）
        channel.restore(pid, beforeRaw)
        val expectedBound = DeterministicRng.fromSeed(1L).also { it.restore(beforeRaw) }.nextInt(10)
        channel.restore(pid, beforeRaw)
        assertEquals(expectedBound, rng.nextInt(10))

        // double 同理：同起点本地参照逐位一致
        channel.restore(pid, beforeRaw)
        val expectedDouble = DeterministicRng.fromSeed(1L).also { it.restore(beforeRaw) }.nextDouble()
        channel.restore(pid, beforeRaw)
        assertEquals(expectedDouble, rng.nextDouble(), 0.0)
    }

    @Test
    fun `manager delegation routes export and restore through channel`() {
        val channel = FakeChannel()
        val manager = GameRngManager()
        assertFalse(manager.isDelegatingToNative())

        manager.attachNativeChannel(channel)
        assertTrue(manager.isDelegatingToNative())
        val exported = manager.exportStates()
        // 参与存档的分区键在位；快照值 == 通道内对应流状态。
        // AI_SECT_MIRROR 是**通道型分区**（inSnapshot=false，状态归 C++ aiRng_ 保管
        // 并随同一 9 号键自行落盘）——不进出 rngStates，故此处不遍历它
        RngPartition.entries.filter { it.inSnapshot }.forEach { p ->
            assertEquals(channel.snapshot(p.id), exported[p.id])
        }
        assertEquals(RngPartition.entries.count { it.inSnapshot }, exported.size)
        // restore 推入通道（事务回滚守卫路径）
        manager.restoreStates(mapOf(RngPartition.BATTLE.id to 42L))
        assertTrue(RngPartition.BATTLE.id in channel.restoreCalls)
        assertEquals(42L, channel.snapshot(RngPartition.BATTLE.id))
    }

    @Test
    fun `detach continues local sequence from native truth`() {
        val channel = FakeChannel()
        val manager = GameRngManager().also { it.attachNativeChannel(channel) }
        val pid = RngPartition.EXPLORATION.id

        // native 真相源上消耗若干次并记录"下一次"应为的值
        repeat(3) { channel.nextInt(pid) }
        val truthState = channel.snapshot(pid)
        val probe = DeterministicRng.fromSeed(1L).also { it.restore(truthState) }
        val truthNext = probe.nextInt()

        manager.detachNativeChannel()
        assertFalse(manager.isDelegatingToNative())
        assertEquals(truthNext, manager.getRng(RngPartition.EXPLORATION).nextInt())
    }

    @Test
    fun `initSystemSeed under delegation reseeds native and stays consistent`() {
        val channel = FakeChannel()
        val manager = GameRngManager().also { it.attachNativeChannel(channel) }
        manager.initSystemSeed(777L)
        assertEquals(listOf(777L), channel.seedCalls)
        // 委托实例抽取落到通道重播后的流上（seed + partitionId 口径）
        val fresh = DeterministicRng.fromSeed(777L + RngPartition.BATTLE.id)
        assertEquals(fresh.nextInt(), manager.getRng(RngPartition.BATTLE).nextInt())
    }
}
