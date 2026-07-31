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
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * loadFromSnapshot 回滚完整性专项测试。
 *
 * 对抗性审查回归：COW 快照隔离下，回滚不能依赖 oldTables.deepCopy()——
 * 提交后的列是 owned 状态（shared=false），clear() 原地清空共享 store，
 * 会破坏 oldTables。回滚必须用内存中的 oldDisciples 列表重建。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameStateStoreRollbackTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var stateStore: GameStateStoreImpl
    private lateinit var repository: GameStateRepository

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

        repository = Mockito.mock(GameStateRepository::class.java)
        stateStore = GameStateStoreImpl(
            applicationScopeProvider = ApplicationScopeProvider(),
            repository = repository
        )
        stateStore.unsafeAllowMainThreadUpdateForTest = true
    }

    private fun makeDisciple(id: Int, cultivation: Double): Disciple = Disciple(
        id = id.toString(),
        name = "弟子$id",
        realm = 9,
        cultivation = cultivation,
        skills = SkillStats(loyalty = 50)
    )

    private fun snapshotArgs(
        disciples: List<Disciple> = emptyList(),
        year: Int = 2
    ): List<Any?> = listOf(
        GameData(gameYear = year, gameMonth = 1),
        disciples,
        emptyList<com.xianxia.sect.core.model.EquipmentStack>(),
        emptyList<EquipmentInstance>(),
        emptyList<com.xianxia.sect.core.model.ManualStack>(),
        emptyList<ManualInstance>(),
        emptyList<com.xianxia.sect.core.model.Pill>(),
        emptyList<com.xianxia.sect.core.model.Material>(),
        emptyList<com.xianxia.sect.core.model.Herb>(),
        emptyList<com.xianxia.sect.core.model.Seed>(),
        emptyList<com.xianxia.sect.core.model.StorageBag>(),
        emptyList<com.xianxia.sect.core.model.ExplorationTeam>(),
        emptyList<com.xianxia.sect.core.model.BattleLog>(),
        false, false, false
    )

    @Test
    fun `loadFromSnapshot failure rolls back disciple data completely`() = runTest {
        // 当前游戏：2 名弟子（修为 150/200）
        stateStore.update {
            discipleTables.insert(makeDisciple(1, cultivation = 150.0))
            discipleTables.insert(makeDisciple(2, cultivation = 200.0))
        }

        // 触发加载异常：repository.setActiveSlot 在弟子写入后执行
        Mockito.`when`(repository.setActiveSlot(any())).thenThrow(
            RuntimeException("模拟存档槽设置失败")
        )

        val args = snapshotArgs(disciples = listOf(makeDisciple(9, cultivation = 999.0)))
        try {
            @Suppress("UNCHECKED_CAST")
            stateStore.loadFromSnapshot(
                gameData = args[0] as GameData,
                disciples = args[1] as List<Disciple>,
                equipmentStacks = args[2] as List<com.xianxia.sect.core.model.EquipmentStack>,
                equipmentInstances = args[3] as List<EquipmentInstance>,
                manualStacks = args[4] as List<com.xianxia.sect.core.model.ManualStack>,
                manualInstances = args[5] as List<ManualInstance>,
                pills = args[6] as List<com.xianxia.sect.core.model.Pill>,
                materials = args[7] as List<com.xianxia.sect.core.model.Material>,
                herbs = args[8] as List<com.xianxia.sect.core.model.Herb>,
                seeds = args[9] as List<com.xianxia.sect.core.model.Seed>,
                storageBags = args[10] as List<com.xianxia.sect.core.model.StorageBag>,
                teams = args[11] as List<com.xianxia.sect.core.model.ExplorationTeam>,
                battleLogs = args[12] as List<com.xianxia.sect.core.model.BattleLog>,
                isPaused = args[13] as Boolean,
                isLoading = args[14] as Boolean,
                isSaving = args[15] as Boolean
            )
            fail("loadFromSnapshot 应抛出模拟异常")
        } catch (e: RuntimeException) {
            // 预期异常：repository.setActiveSlot 模拟失败
            assertTrue("异常应为模拟的存档槽设置失败", e.message?.contains("模拟") == true)
        }

        // 回滚后：2 名弟子数据完整恢复（COW 破坏时修为归零）
        val tables = stateStore.discipleTables
        assertEquals("回滚后弟子数应恢复", 2, tables.count)
        assertEquals("回滚后修为应完整恢复", 150.0, tables.cultivations[1], 0.001)
        assertEquals("回滚后修为应完整恢复", 200.0, tables.cultivations[2], 0.001)
        assertEquals("回滚后姓名应完整恢复", "弟子1", tables.names[1])
        assertEquals("回滚后境界应完整恢复", 9, tables.realms[1])
        // _disciplesFlow 同步恢复
        assertEquals("回滚后 _disciplesFlow 应恢复", 2, stateStore.disciples.value.size)
        assertEquals(150.0, stateStore.disciples.value.find { it.id == "1" }?.cultivation ?: -1.0, 0.001)
    }

    @Test
    fun `loadFromSnapshot success replaces disciple data`() = runTest {
        stateStore.update {
            discipleTables.insert(makeDisciple(1, cultivation = 150.0))
        }

        stateStore.loadFromSnapshot(
            gameData = GameData(gameYear = 2, gameMonth = 1),
            disciples = listOf(makeDisciple(5, cultivation = 500.0)),
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
            isPaused = false, isLoading = false, isSaving = false
        )

        val tables = stateStore.discipleTables
        assertEquals("加载后弟子数应替换", 1, tables.count)
        assertEquals(500.0, tables.cultivations[5], 0.001)
        assertTrue("旧弟子应被清除", !tables.names.contains(1))
    }
}
