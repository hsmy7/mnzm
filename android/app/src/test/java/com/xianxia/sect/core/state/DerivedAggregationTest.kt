package com.xianxia.sect.core.state

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.StarZone
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.xianxia.sect.core.engine.domain.disciple.calculateCultivationPerPhase
import com.xianxia.sect.core.engine.domain.disciple.getBaseStats
import com.xianxia.sect.core.engine.domain.disciple.getBreakthroughChance
import com.xianxia.sect.core.engine.domain.disciple.getFinalStats
import com.xianxia.sect.core.engine.domain.disciple.getStatsWithEquipment

/**
 * 聚合链合并专项测试：discipleAggregates + sectCombatPower 单一派生链。
 *
 * 语义等价性（与合并前两链对比）：
 * 1. aggregates 覆盖全部弟子（含死亡）——保持原 discipleAggregates 语义
 * 2. combatPower 仅累计存活弟子——保持原 sectCombatPower 语义（指纹缓存保留）
 * 3. 纯 UI 事务（无弟子数据变更）不触发重扫（事件驱动）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DerivedAggregationTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var stateStore: GameStateStoreImpl

    @Before
    fun setUp() {
        // 初始化 statsProvider（toAggregate 依赖）
        DiscipleAggregate.statsProvider = object : DiscipleStatsProvider {
            override fun getBaseStats(disciple: Disciple) =
                DiscipleStatCalculator.getBaseStats(disciple)

            override fun getBaseStats(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getBaseStats(aggregate)

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
                peb: Double, pmb: Double, csb: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                d, manuals, mps, bb, peb, pmb, csb
            )

            override fun calculateCultivationSpeed(
                a: DiscipleAggregate, manuals: Map<String, ManualInstance>,
                mps: Map<String, ManualProficiencyData>, bb: Double, ab: Double,
                peb: Double, pmb: Double, csb: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                a, manuals, mps, bb, peb, pmb, csb
            )

            override fun getBreakthroughChance(
                d: Disciple, iec: Int, oec: Int, pb: Double, ab: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(d, iec, oec, pb, ab)

            override fun getBreakthroughChance(
                a: DiscipleAggregate, iec: Int, oec: Int, pb: Double, ab: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(a, iec, oec, pb, ab)
        }

        stateStore = GameStateStoreImpl(
            applicationScopeProvider = ApplicationScopeProvider(),        )
        stateStore.unsafeAllowMainThreadUpdateForTest = true
    }

    private fun makeDisciple(id: Int, alive: Boolean = true): Disciple = Disciple(
        id = id.toString(),
        name = "弟子$id",
        realm = 9,
        cultivation = 100.0 * id
    ).copy(isAlive = alive)

    /**
     * 轮询等待真实时序下的聚合发射。
     * 注意：stateIn 使用 WhileSubscribed(5_000)——测试必须保持订阅者
     * 上游才启动，否则 value 恒为初始值。
     */
    private fun awaitAggregation(expectedDisciples: Int, timeoutMs: Long = 5_000) {
        var attempts = 0
        while (attempts++ < timeoutMs / 20) {
            if (stateStore.discipleAggregates.value.size == expectedDisciples) return
            Thread.sleep(20)
        }
        fail("聚合超时未发射: disciples=${stateStore.disciples.value.size} " +
            "aggregates=${stateStore.discipleAggregates.value.size}")
    }

    /** 保持聚合链订阅（WhileSubscribed 语义需要活跃订阅者才启动上游） */
    private fun CoroutineScope.keepAggregationSubscribed() {
        launch(Dispatchers.Default) { stateStore.discipleAggregates.collect {} }
        launch(Dispatchers.Default) { stateStore.sectCombatPower.collect {} }
    }

    /** 规范化时间戳后比较（toAggregate 两次调用 updatedAt 毫秒级不同） */
    private fun normalize(a: DiscipleAggregate): DiscipleAggregate =
        a.copy(core = a.core.copy(updatedAt = 0))

    @Test
    fun `aggregates cover all disciples including dead ones`() = runTest {
        backgroundScope.keepAggregationSubscribed()
        stateStore.update {
            discipleTables.insert(makeDisciple(1, alive = true))
            discipleTables.insert(makeDisciple(2, alive = true))
            discipleTables.insert(makeDisciple(3, alive = false))
        }
        awaitAggregation(expectedDisciples = 3)

        val aggregates = stateStore.discipleAggregates.value
        assertEquals(3, aggregates.size)
        // 与手算 toAggregate 列表一致（含死亡弟子，忽略 updatedAt 时间戳）
        val expected = stateStore.disciples.value.map { normalize(it.toAggregate()) }
        assertEquals(
            "aggregates 应与 disciples 全量 toAggregate 一致",
            expected, aggregates.map { normalize(it) }
        )
    }

    @Test
    fun `combatPower sums only alive disciples`() = runTest {
        backgroundScope.keepAggregationSubscribed()
        stateStore.update {
            discipleTables.insert(makeDisciple(1, alive = true))
            discipleTables.insert(makeDisciple(2, alive = true))
            discipleTables.insert(makeDisciple(3, alive = false))
        }
        awaitAggregation(expectedDisciples = 3)

        // 手算：存活弟子战力之和（血炼加成空 map 时；星级按账本反查）
        val expectedPower = stateStore.disciples.value
            .filter { it.isAlive }
            .sumOf {
                SectCombatPowerCalculator.calculateDisciplePower(
                    it.toAggregate(), starOf(it)
                )
            }
        assertEquals("战力应仅累计存活弟子", expectedPower, stateStore.sectCombatPower.value)
    }

    @Test
    fun `pure UI transaction does not trigger re-aggregation`() = runTest {
        backgroundScope.keepAggregationSubscribed()
        stateStore.update {
            discipleTables.insert(makeDisciple(1, alive = true))
        }
        awaitAggregation(expectedDisciples = 1)

        val aggregatesBefore = stateStore.discipleAggregates.value
        val powerBefore = stateStore.sectCombatPower.value

        // 纯 UI 事务：仅改 isLoading 运行态旗标（提交新状态实例），无弟子数据变更
        stateStore.update { isLoading = true }

        Thread.sleep(300)  // 超过 sample(100) 合并窗口

        assertSame(
            "无弟子数据变更的事务不应触发重扫（引用不变）",
            aggregatesBefore, stateStore.discipleAggregates.value
        )
        assertEquals(powerBefore, stateStore.sectCombatPower.value)
    }

    @Test
    fun `burst updates converge to correct final aggregation`() = runTest {
        backgroundScope.keepAggregationSubscribed()
        // 连续 50 次弟子更新（模拟月变批量结算），最终值必须正确收敛
        for (i in 1..50) {
            stateStore.update {
                discipleTables.insert(makeDisciple(i, alive = true))
            }
        }
        // 最终：50 存活弟子
        awaitAggregation(expectedDisciples = 50)

        val aggregates = stateStore.discipleAggregates.value
        assertEquals(50, aggregates.size)
        assertEquals(
            "最终战力应为 50 名存活弟子之和",
            stateStore.disciples.value
                .filter { it.isAlive }
                .sumOf {
                    SectCombatPowerCalculator.calculateDisciplePower(
                        it.toAggregate(), starOf(it)
                    )
                },
            stateStore.sectCombatPower.value
        )
    }

    /**
     * 升星刷新战力：星级不在战力指纹内（指纹与 C++ sectPowerFingerprint 逐位对拍），
     * 缓存命中判据必须并列比对 star，否则弟子境界不变时战力停留在旧星级。
     */
    @Test
    fun `star up refreshes cached combat power`() = runTest {
        backgroundScope.keepAggregationSubscribed()
        val template = "zhouming"
        stateStore.update {
            gameData = gameData.copy(gachaStarMap = mapOf(template to StarZone.BASE_STAR))
            discipleTables.insert(makeStarredDisciple(1, template, cultivation = 100.0))
        }
        awaitAggregation(expectedDisciples = 1)

        val basePower = SectCombatPowerCalculator.calculateDisciplePower(
            stateStore.disciples.value.first().toAggregate(), StarZone.BASE_STAR
        )
        TestPolling.awaitCondition(
            description = "1★ 战力基线未就位",
            stateSnapshot = { "power=${stateStore.sectCombatPower.value} 期望=$basePower" }
        ) { stateStore.sectCombatPower.value == basePower }

        // 升星 + 弟子写入（境界/层数/方差全不变 ⇒ 战力指纹不变）
        stateStore.update {
            gameData = gameData.copy(
                gachaStarMap = mapOf(template to GameConfig.Gacha.MAX_STAR)
            )
            discipleTables.insert(makeStarredDisciple(1, template, cultivation = 880.0))
        }
        val starredPower = (basePower * StarZone(GameConfig.Gacha.MAX_STAR).battleMult).toLong()
        assertTrue("升星后的期望战力必须高于基线", starredPower > basePower)
        TestPolling.awaitCondition(
            description = "升星未刷新缓存战力（指纹相同则星级须作命中判据）",
            stateSnapshot = { "power=${stateStore.sectCombatPower.value} 期望=$starredPower" }
        ) { stateStore.sectCombatPower.value == starredPower }
    }

    /** 带角色模板 id 的弟子（其余口径同 makeDisciple，战力只由境界/层数/方差决定） */
    private fun makeStarredDisciple(id: Int, template: String, cultivation: Double): Disciple =
        Disciple(
            id = id.toString(),
            name = "弟子$id",
            realm = 9,
            cultivation = cultivation
        ).copy(templateId = template)

    /** 与战力汇总同判据的星级解析（账本取当前 gameData 快照） */
    private fun starOf(disciple: Disciple): Int =
        StarZone.starOf(stateStore.gameDataSnapshot.gachaStarMap, disciple.templateId)
}
