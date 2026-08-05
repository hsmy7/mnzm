package com.xianxia.sect.core.state

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0-1 K 项守卫测试：事务失败回滚时 RNG 同步回滚，读档重放逐位一致。
 *
 * 背景：结算事务（突破/叛逃/生育/生产判定）在 stateStore.update 内消费分区 RNG。
 * 事务中途异常时 COW 缓冲丢弃（状态回滚）但 RNG 已前进——游戏循环捕获异常后
 * 继续运行，状态与随机序列永久分叉，读档重放不可复现。
 *
 * 修复：GameStateStoreImpl 在 block 前快照 8 分区状态，异常时恢复后原样抛出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransactionRngRollbackTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    /** 委托真实 GameRngManager 的 Fake RngSnapshotPort（记录快照/恢复次数） */
    private class FakeRngPort(private val manager: GameRngManager) : RngSnapshotPort {
        var snapshotCount = 0
        var restoreCount = 0

        override fun snapshot(): Map<Int, Long> {
            snapshotCount++
            return manager.exportStates()
        }

        override fun restore(states: Map<Int, Long>) {
            restoreCount++
            manager.restoreStates(states)
        }
    }

    private fun createStore(rngPort: RngSnapshotPort = NoopRngSnapshotPort): GameStateStoreImpl {
        val repository = Mockito.mock(GameStateRepository::class.java)
        return GameStateStoreImpl(
            applicationScopeProvider = ApplicationScopeProvider(),
            repository = repository,
            rngSnapshotPort = rngPort
        ).also { it.unsafeAllowMainThreadUpdateForTest = true }
    }

    /** 事务内消费固定 RNG 序列的辅助 */
    private fun MutableGameState.consumeSystemRngSequence(manager: GameRngManager) {
        manager.getRng(RngPartition.SYSTEM).nextDouble()
        manager.getRng(RngPartition.SYSTEM).nextInt(100)
        manager.getRng(RngPartition.BREAKTHROUGH).nextDouble()
    }

    @Before
    fun setUp() {
        DiscipleAggregate.statsProvider = object : DiscipleStatsProvider {
            override fun getBaseStats(disciple: Disciple) =
                DiscipleStatCalculator.getBaseStats(disciple)

            override fun getBaseStats(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getBaseStats(aggregate)

            override fun getTalentEffects(disciple: Disciple) =
                DiscipleStatCalculator.getTalentEffects(disciple)

            override fun getTalentEffects(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getTalentEffects(aggregate)

            override fun getStatsWithEquipment(d: Disciple, e: Map<String, EquipmentInstance>) =
                DiscipleStatCalculator.getStatsWithEquipment(d, e)

            override fun getStatsWithEquipment(
                a: DiscipleAggregate, e: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(a, e)

            override fun getFinalStats(
                d: Disciple, e: Map<String, EquipmentInstance>,
                m: Map<String, ManualInstance>, p: Map<String, ManualProficiencyData>
            ) = DiscipleStatCalculator.getFinalStats(d, e, m, p)

            override fun getFinalStats(
                a: DiscipleAggregate, e: Map<String, EquipmentInstance>,
                m: Map<String, ManualInstance>, p: Map<String, ManualProficiencyData>
            ) = DiscipleStatCalculator.getFinalStats(a, e, m, p)

            override fun calculateCultivationSpeed(
                d: Disciple, manuals: Map<String, ManualInstance>,
                mps: Map<String, ManualProficiencyData>, bb: Double, ab: Double,
                peb: Double, pmb: Double, csb: Double, pcb: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                d, manuals, mps, bb, peb, pmb, csb, pcb, gcp
            )

            override fun calculateCultivationSpeed(
                a: DiscipleAggregate, manuals: Map<String, ManualInstance>,
                mps: Map<String, ManualProficiencyData>, bb: Double, ab: Double,
                peb: Double, pmb: Double, csb: Double, pcb: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                a, manuals, mps, bb, peb, pmb, csb, pcb, gcp
            )

            override fun getBreakthroughChance(
                d: Disciple, iec: Int, oec: Int, pb: Double, ab: Double,
                gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(d, iec, oec, pb, ab, gcp, mdb)

            override fun getBreakthroughChance(
                a: DiscipleAggregate, iec: Int, oec: Int, pb: Double, ab: Double,
                gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(a, iec, oec, pb, ab, gcp, mdb)
        }
    }

    /** 场景 A（核心）：异常事务不污染序列——重试后与无异常路径终态一致 */
    @Test
    fun `failed transaction restores RNG so retry matches clean replay`() = runTest {
        // M1：无异常世界——唯一事务消费固定序列
        val mgr1 = GameRngManager().apply { initSystemSeed(42L) }
        val port1 = FakeRngPort(mgr1)
        val store1 = createStore(port1)
        store1.update { consumeSystemRngSequence(mgr1) }

        // M2：异常世界（同种子）——第一次事务消费同样序列后抛异常，重试再消费
        val mgr2 = GameRngManager().apply { initSystemSeed(42L) }
        val port2 = FakeRngPort(mgr2)
        val store2 = createStore(port2)
        try {
            store2.update {
                consumeSystemRngSequence(mgr2)
                error("模拟结算事务异常")
            }
        } catch (@Suppress("SwallowedException") e: IllegalStateException) {
            // 预期：异常原样传播（游戏循环会捕获并继续运行）
        }
        store2.update { consumeSystemRngSequence(mgr2) }

        // 断言：RNG 终态逐分区一致（M2 的异常事务未污染序列）
        assertEquals("异常事务后 RNG 应回滚，重试与无异常路径逐位一致", mgr1.exportStates(), mgr2.exportStates())
        assertEquals("无异常世界不应触发恢复", 0, port1.restoreCount)
        assertEquals("异常世界应恰好恢复一次", 1, port2.restoreCount)
        assertEquals("两次顶层事务各快照一次", 2, port2.snapshotCount)
    }

    /** 场景 B：嵌套 update（重入路径）不重复快照，异常时恢复一次 */
    @Test
    fun `nested update does not snapshot twice and single restore on failure`() = runTest {
        val mgr = GameRngManager().apply { initSystemSeed(7L) }
        val port = FakeRngPort(mgr)
        val store = createStore(port)

        try {
            store.update {
                consumeSystemRngSequence(mgr)
                // 重入路径：直接写 buffer，不经过 executeBlockWithRngGuard
                store.update {
                    mgr.getRng(RngPartition.SYSTEM).nextInt(50)
                    mgr.getRng(RngPartition.EXPLORATION).nextDouble()
                }
                error("外层事务异常")
            }
        } catch (@Suppress("SwallowedException") e: IllegalStateException) {
            // 预期：外层事务异常原样传播
        }

        assertEquals("仅外层事务快照一次", 1, port.snapshotCount)
        assertEquals("仅恢复一次", 1, port.restoreCount)
        // 异常事务（含嵌套消费）完全回滚后，RNG 停在事务前（初始）状态
        val initial = GameRngManager().apply { initSystemSeed(7L) }.exportStates()
        assertEquals("异常事务完全回滚后 RNG 停在初始状态", initial, mgr.exportStates())
    }

    /** 场景 C：读档失败（rollbackLoad）后 RNG 保持读档前状态 */
    @Test
    fun `failed loadFromSnapshot keeps RNG at pre-load state`() = runTest {
        val mgr = GameRngManager().apply { initSystemSeed(99L) }
        val port = FakeRngPort(mgr)
        val repository = Mockito.mock(GameStateRepository::class.java)
        val store = GameStateStoreImpl(
            applicationScopeProvider = ApplicationScopeProvider(),
            repository = repository,
            rngSnapshotPort = port
        ).also { it.unsafeAllowMainThreadUpdateForTest = true }

        // 读档前：事务消费 RNG 形成已知状态
        store.update { consumeSystemRngSequence(mgr) }
        val before = mgr.exportStates()

        // 触发加载异常：repository.setActiveSlot 在写入后执行
        Mockito.`when`(repository.setActiveSlot(any())).thenThrow(RuntimeException("模拟读档失败"))
        val newData = GameData(gameYear = 5, rngStates = mapOf(0 to 123L, 1 to 456L))
        try {
            store.loadFromSnapshot(
                gameData = newData,
                disciples = emptyList(),
                equipmentStacks = emptyList(),
                equipmentInstances = emptyList(),
                manualStacks = emptyList(),
                manualInstances = emptyList(),
                pills = emptyList(),
                materials = emptyList(),
                herbs = emptyList(),
                seeds = emptyList(),
                storageBags = emptyList(),
                teams = emptyList(),
                battleLogs = emptyList(),
                isPaused = false,
                isLoading = false,
                isSaving = false
            )
        } catch (@Suppress("SwallowedException") e: RuntimeException) {
            // 预期：repository.setActiveSlot 模拟读档失败
        }

        assertEquals("读档失败后 RNG 应恢复读档前状态", before, mgr.exportStates())
        assertEquals("读档失败触发一次恢复", 1, port.restoreCount)
    }

    /** 场景 D：读档成功——状态 + RNG 原子切换为新档值 */
    @Test
    fun `successful loadFromSnapshot atomically switches RNG to save states`() = runTest {
        val mgr = GameRngManager().apply { initSystemSeed(5L) }
        val port = FakeRngPort(mgr)
        val store = createStore(port)
        store.update { consumeSystemRngSequence(mgr) }

        val newStates = mapOf(0 to 111L, 1 to 222L, 2 to 333L, 3 to 444L)
        val newData = GameData(gameYear = 3, rngStates = newStates)
        store.loadFromSnapshot(
            gameData = newData,
            disciples = emptyList(),
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            storageBags = emptyList(),
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )

        // 新档 rngStates 只有 4 个分区——restore 只覆盖存在的分区，其余保持现状
        val restored = mgr.exportStates()
        newStates.forEach { (id, state) -> assertEquals("分区 $id 应切换为新档状态", state, restored[id]) }
        // 成功路径 restore 一次是有意的原子切换（状态 + RNG 同步），非回滚
        assertEquals("读档成功触发一次 RNG 切换", 1, port.restoreCount)
    }

    /** 场景 E：CancellationException 穿透且不触发恢复 */
    @Test
    fun `cancellation propagates without RNG restore`() = runTest {
        val mgr = GameRngManager().apply { initSystemSeed(3L) }
        val port = FakeRngPort(mgr)
        val store = createStore(port)

        try {
            store.update {
                mgr.getRng(RngPartition.SYSTEM).nextDouble()
                throw CancellationException("协程取消")
            }
        } catch (@Suppress("SwallowedException") e: CancellationException) {
            // 预期：原样传播
        }

        assertEquals("取消不触发恢复", 0, port.restoreCount)
        // RNG 保持已前进状态（取消非事务失败）
        val afterCancel = mgr.exportStates()
        val mgrClean = GameRngManager().apply { initSystemSeed(3L) }
        mgrClean.getRng(RngPartition.SYSTEM).nextDouble()
        assertEquals(mgrClean.exportStates(), afterCancel)
    }

    /** 场景 F：事务提交成功不触发恢复 */
    @Test
    fun `successful transaction does not restore`() = runTest {
        val mgr = GameRngManager().apply { initSystemSeed(11L) }
        val port = FakeRngPort(mgr)
        val store = createStore(port)

        store.update { consumeSystemRngSequence(mgr) }
        store.update {
            discipleTables.insert(
                Disciple(
                    id = "1", name = "测试弟子", realm = 1,
                    cultivation = 100.0, skills = SkillStats(loyalty = 50)
                )
            )
        }

        assertEquals(0, port.restoreCount)
        assertTrue(port.snapshotCount >= 2)
    }
}
