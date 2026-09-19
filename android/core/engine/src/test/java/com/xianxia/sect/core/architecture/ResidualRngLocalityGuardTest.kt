package com.xianxia.sect.core.architecture

import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.NativeRngChannel
import com.xianxia.sect.core.util.RngPartition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ResidualRngLocalityGuardTest — R4.4/B14「残留执行器随机域本地化」行为级守卫。
 *
 * ## 存在理由
 * R4.4 前，AUTHORITATIVE 下 [GameRngManager.rebuildPartitions] 把**全部**
 * `inSnapshot` 分区实例化为 `NativeBackedRng`——残留执行器（月/年变编排、
 * 亲属赠送、突破回落…）的每一次 `nextInt()` 都要跨 JNI 回 C++ 取一个标量
 *（`NativeBackedRng.kt:46` 的 per-roll JNI）。本批把该域独立为
 * [RngPartition.RESIDUAL]（11）并改为**本地 PCG 实例**。
 *
 * ## 判据（行为级，不依赖 native 库）
 * 用**计数替身通道**（[CountingChannel]）装配 AUTHORITATIVE 委托模式，然后
 * 断言：对残留域抽取 N 次 ⇒ 通道计数**增量为 0**；对结算域（BATTLE）抽取
 * N 次 ⇒ 通道计数**增量恰为 N**（对照证明其余分区委托关系不受影响）。
 * 行为级证明优于静态 grep：它能捕获"实例类型正确但内部仍转发"的回归。
 *
 * ## 确定性（红线 3）
 * 同 seed + 同操作序 ⇒ 同序列；bound / double / gaussian 上层公式经继承
 * 消费原始流，消耗次数与产出逐位可复现。老档兼容（红线 3 + 验收门 5）：
 * 无 11 号键的旧 `rngStates` 加载后按 `systemSeed + 11` 确定性重种，
 * 可复跑断言（非仅日志）。
 */
class ResidualRngLocalityGuardTest {

    /**
     * 计数替身通道：内部用独立 [DeterministicRng] 流模拟 C++ 分区，
     * 并统计每一类跨线调用次数——**per-roll JNI 的行为级探针**。
     */
    private class CountingChannel(initialSeed: Long = 4_242L) : NativeRngChannel {
        val streams = mutableMapOf<Int, DeterministicRng>()
        var nextIntCalls = 0
            private set
        var snapshotCalls = 0
            private set
        var restoreCalls = 0
            private set
        private var lastSeed = initialSeed

        private fun stream(pid: Int): DeterministicRng =
            streams.getOrPut(pid) { DeterministicRng.fromSeed(lastSeed + pid) }

        override fun nextInt(partitionId: Int): Int {
            nextIntCalls++
            return stream(partitionId).nextInt()
        }

        override fun snapshot(partitionId: Int): Long {
            snapshotCalls++
            return stream(partitionId).snapshot()
        }

        override fun restore(partitionId: Int, state: Long) {
            restoreCalls++
            stream(partitionId).restore(state)
        }

        override fun initSystemSeed(seed: Long) {
            lastSeed = seed
            streams.clear()
        }

        /** 抽取计数快照（用于增量断言） */
        fun drawCalls(): Int = nextIntCalls
    }

    private fun managerUnderDelegation(channel: CountingChannel, seed: Long = 99_999L) =
        GameRngManager().also {
            it.attachNativeChannel(channel)
            it.initSystemSeed(seed)
        }

    @Test
    fun `残留执行器随机域委托模式下零跨线（per-roll JNI 消除）`() {
        val channel = CountingChannel()
        val manager = managerUnderDelegation(channel)
        assertTrue("前置：应处于委托模式", manager.isDelegatingToNative())

        val residual = manager.getRng(RngPartition.RESIDUAL)
        // 覆盖全部上层公式面：raw / bound / double / gaussian / nextLong
        val before = channel.drawCalls()
        repeat(64) { residual.nextInt() }
        repeat(64) { residual.nextInt(37) }
        repeat(64) { residual.nextDouble() }
        repeat(8) { residual.nextGaussian(1.0, 2.0) }
        repeat(8) { residual.nextLong(1_000L) }
        val delta = channel.drawCalls() - before

        assertEquals(
            "R4.4 红线：残留执行器随机域（RESIDUAL/11）在委托模式下必须**零 per-roll 跨线**——" +
                "实测跨线 $delta 次（应为 0）。若 >0 说明该分区被重新实例化为 NativeBackedRng" +
                "（或本地实例内部发生转发），per-roll JNI 回归",
            0,
            delta
        )
    }

    @Test
    fun `其余分区委托关系不受影响（对照证明）`() {
        val channel = CountingChannel()
        val manager = managerUnderDelegation(channel)

        // 对照臂：结算分区（BATTLE）仍逐 roll 委托——抽取 N 次 = 跨线恰 N 次。
        // 本断言是"消的是调用频率、不是通道"的反向证明：
        // 若有人图省事把全部分区都改本地，本用例红。
        val battle = manager.getRng(RngPartition.BATTLE)
        val before = channel.drawCalls()
        val draws = 50
        repeat(draws) { battle.nextInt() }
        assertEquals(
            "结算分区（BATTLE）必须保持逐 roll 委托 native 单一真相源" +
                "（红线：其余分区的既有委托关系与序列不动）",
            draws,
            channel.drawCalls() - before
        )

        // 通道型分区（AI_SECT_MIRROR）语义不变：仍走通道（C++ aiRng_ 本体）
        val mirror = manager.getRng(RngPartition.AI_SECT_MIRROR)
        val beforeMirror = channel.drawCalls()
        repeat(draws) { mirror.nextInt() }
        assertEquals(
            "AI_SECT_MIRROR(9) 的通道型语义不得被波及（inSnapshot=false、C++ 保管流态）",
            draws,
            channel.drawCalls() - beforeMirror
        )
    }

    @Test
    fun `本地 PCG 分区与通道流互不干扰`() {
        val channel = CountingChannel()
        val manager = managerUnderDelegation(channel)
        val residual = manager.getRng(RngPartition.RESIDUAL)

        // 残留域抽 200 次后，通道内任何分区状态都不得变化
        // 唯一跨线来自 before/after 两次 exportStates 采样，且各只覆盖
        // **委托式分区**（本地 PCG 分区的 snapshot 在 Kotlin 侧完成，不跨线）
        val delegatingPartitionCount = RngPartition.entries.count { it.inSnapshot && !it.isLocal }
        val beforeStates = manager.exportStates()
        val snapshotCallsBefore = channel.snapshotCalls
        repeat(200) { residual.nextInt(11) }
        val snapshotCallsAfterDraws = channel.snapshotCalls
        val afterStates = manager.exportStates()

        assertEquals(
            "本地 PCG 分区的抽取不得触碰通道内任何分区的流态",
            beforeStates.filterKeys { it != RngPartition.RESIDUAL.id },
            afterStates.filterKeys { it != RngPartition.RESIDUAL.id }
        )
        // 抽取本身贡献 0 次跨线（下面这个断言是本用例的核心）
        assertEquals(
            "残留域抽取必须**零跨线**：200 次抽取后通道 snapshot 调用增量为 0",
            0,
            snapshotCallsAfterDraws - snapshotCallsBefore
        )
        // 对照：走一遍导出面确实会跨线——且次数恰为委托式分区数
        val exportSnapshots = channel.snapshotCalls - snapshotCallsAfterDraws
        assertEquals(
            "导出面的跨线次数须恰等于**委托式**分区数（本地 PCG 分区不跨线）",
            delegatingPartitionCount,
            exportSnapshots
        )
    }

    @Test
    fun `确定性——同 seed 同操作序得到同序列（含上层公式）`() {
        val a = managerUnderDelegation(CountingChannel(), seed = 777_777L)
        val b = managerUnderDelegation(CountingChannel(), seed = 777_777L)

        val ra = a.getRng(RngPartition.RESIDUAL)
        val rb = b.getRng(RngPartition.RESIDUAL)
        val seqA = buildList {
            repeat(30) { add(ra.nextInt()) }
            repeat(30) { add(ra.nextInt(29)) }
            repeat(10) { add(ra.nextDouble()) }
            repeat(5) { add(ra.nextGaussian(0.5, 1.5)) }
            repeat(5) { add(ra.nextLong(997L)) }
        }
        val seqB = buildList {
            repeat(30) { add(rb.nextInt()) }
            repeat(30) { add(rb.nextInt(29)) }
            repeat(10) { add(rb.nextDouble()) }
            repeat(5) { add(rb.nextGaussian(0.5, 1.5)) }
            repeat(5) { add(rb.nextLong(997L)) }
        }
        assertEquals(
            "同 seed 同操作序必须得到逐位相同的序列（红线：确定性）——" +
                "包含 bound/double/gaussian/long 上层公式在内的完整消费面",
            seqA,
            seqB
        )
        // 非平凡性自检：不同 seed 必须给出不同序列（防「恒等序列」假绿）
        val c = managerUnderDelegation(CountingChannel(), seed = 777_778L)
        assertNotEquals("不同 seed 的序列不得相同（防假绿）", seqA, listOf(c.getRng(RngPartition.RESIDUAL).nextInt()))
    }

    @Test
    fun `老档兼容——无 11 号键的 rngStates 按 systemSeed 加 11 确定性重种`() {
        // 构造 R4.4 前的旧档：含 0..8 / 10，**独缺 11**
        val legacyStates = mapOf(
            0 to 101L, 1 to 102L, 2 to 103L, 3 to 104L, 4 to 105L,
            5 to 106L, 6 to 107L, 7 to 108L, 8 to 109L, 10 to 110L
        )
        val seed = 555_555L

        fun load() = GameRngManager().also {
            it.attachNativeChannel(CountingChannel())
            it.initSystemSeed(seed)
            it.restoreStates(legacyStates)
        }

        // 可复跑断言：两次独立加载得到逐位相同的残留域序列
        val first = load()
        val second = load()
        val seqFirst = List(40) { first.getRng(RngPartition.RESIDUAL).nextInt() }
        val seqSecond = List(40) { second.getRng(RngPartition.RESIDUAL).nextInt() }
        assertEquals("旧档缺 11 号键 ⇒ 必须按 systemSeed+11 确定性重种（两次加载同序列）", seqFirst, seqSecond)

        // 且等于播种公式的直接推演（systemSeed + id 起播）
        val expected = DeterministicRng.fromSeed(seed + RngPartition.RESIDUAL.id)
        val seqExpected = List(40) { expected.nextInt() }
        assertEquals(
            "重种必须等价于 fromSeed(systemSeed + 11)（与 MISSION(8)/CHAT(10) 同款语义）",
            seqExpected,
            seqFirst
        )

        // 旧档已有键的分区不受重种影响
        assertEquals(101L, first.exportStates()[0])
        assertEquals(110L, first.exportStates()[10])
        // 11 号键进入导出面（inSnapshot=true）
        assertTrue("RESIDUAL 必须参与 rngStates 导出", first.exportStates().containsKey(11))
    }

    @Test
    fun `快照恢复往返——本地 state 真实使用且可回滚`() {
        val channel = CountingChannel()
        val manager = managerUnderDelegation(channel)
        val residual = manager.getRng(RngPartition.RESIDUAL)

        repeat(17) { residual.nextInt() }
        val snapshot = residual.snapshot()
        val expectedNext = DeterministicRng.fromSeed(1L).also { it.restore(snapshot) }.nextInt()

        repeat(9) { residual.nextInt() }
        residual.restore(snapshot)   // 事务回滚路径
        assertEquals(
            "本地 PCG 的 snapshot/restore 必须走本地 state（事务回滚语义保持）",
            expectedNext,
            residual.nextInt()
        )

        // 快照值是真实流态而非常量 0（NativeBackedRng 的本地 state 不使用 ⇒ 恒 0）
        assertNotEquals("本地 state 必须真实使用（不得像 NativeBackedRng 那样恒为 0）", 0L, snapshot)
        assertTrue("前置：确认是委托模式（本地化不应依赖回退臂）", manager.isDelegatingToNative())
    }

    @Test
    fun `本地 PCG 分区不产生任何导出面之外的键`() {
        val manager = GameRngManager().also { it.attachNativeChannel(CountingChannel()) }
        val exported = manager.exportStates()
        assertEquals(
            "rngStates 的键集必须恰为 inSnapshot 分区全集（schema 零变更：仍是 Map<Int,Long>）",
            RngPartition.entries.filter { it.inSnapshot }.map { it.id }.toSet(),
            exported.keys
        )
        assertEquals("RESIDUAL 唯一新键 = 11", 11, exported.keys.max())
    }
}
