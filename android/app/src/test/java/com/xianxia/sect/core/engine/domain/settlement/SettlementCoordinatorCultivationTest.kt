package com.xianxia.sect.core.engine.domain.settlement

import android.app.Application
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.system.MailSystem
import com.xianxia.sect.core.engine.system.PartnerSystem
import com.xianxia.sect.core.engine.system.ChildBirthSystem
import com.xianxia.sect.core.engine.domain.production.ProductionSubsystem
import com.xianxia.sect.core.engine.domain.production.EconomySubsystem
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.state.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SettlementCoordinator 修炼公式与突破检查的单元测试。
 *
 * 通过 scheduleMonthly() + executeStep() 公开 API 间接测试
 * processCleanDiscipleBatch / processDirtyDiscipleBatch。
 *
 * 核心验证：
 * 1. 修炼公式：alreadyGained 从总额扣一次（非逐月扣）
 * 2. 突破前提：修为可达 maxCultivation
 * 3. dirty 弟子无 BREAKTHROUGH flag 也可正常修炼
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettlementCoordinatorCultivationTest {

    private lateinit var scheduler: SettlementScheduler
    private lateinit var cultivationService: CultivationService
    private lateinit var stateStore: GameStateStore
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var coordinator: SettlementCoordinator
    private lateinit var hfdFlow: MutableStateFlow<HighFrequencyData>

    @Before
    fun setup() {
        scheduler = SettlementScheduler()
        cultivationService = mock(CultivationService::class.java)
        stateStore = mock(GameStateStore::class.java)
        thermalMonitor = mock(ThermalMonitor::class.java)

        hfdFlow = MutableStateFlow(HighFrequencyData())
        `when`(cultivationService.getHighFrequencyData()).thenReturn(hfdFlow)
        `when`(stateStore.focusedDiscipleId).thenReturn(null)
        `when`(thermalMonitor.shouldReduceWorkload()).thenReturn(false)
        `when`(thermalMonitor.shouldEmergencySave()).thenReturn(false)

        coordinator = SettlementCoordinator(
            cultivationService,
            mock(ProductionSubsystem::class.java),
            mock(EconomySubsystem::class.java),
            mock(ExplorationService::class.java),
            mock(MailSystem::class.java),
            mock(ChildBirthSystem::class.java),
            mock(PartnerSystem::class.java),
            stateStore, scheduler,
            mock(SettlementMetricsCollector::class.java),
            mock(GameTimeClock::class.java),
            thermalMonitor
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. 修炼公式 — alreadyGained 从总额扣一次（核心修复验证）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `alreadyGained=0 修为正常增长`() = runTest {
        val s = newCleanShadow("1")
        val before = s.discipleTables.cultivations[1]
        assertEquals(0.0, before, 0.0)

        coordinator.scheduleMonthly(s)
        executeUntilComplete()

        assertTrue("修为应增长", s.discipleTables.cultivations[1] > 0.0)
    }

    @Test
    fun `alreadyGained等于整月值 最终修为等于整月值 无损失`() = runTest {
        // 第一步：空 HFD 结算得 monthlyGain
        val s1 = newCleanShadow("1")
        coordinator.scheduleMonthly(s1)
        executeUntilComplete()
        val monthlyGain = s1.discipleTables.cultivations[1]
        assertTrue("应有月修为增长: $monthlyGain", monthlyGain > 0)

        // 第二步：HFD alreadyGained = monthlyGain
        hfdFlow.value = HighFrequencyData(
            cultivationUpdates = mapOf("1" to monthlyGain)
        )
        val s2 = newCleanShadow("1")
        coordinator.scheduleMonthly(s2)
        executeUntilComplete()

        assertEquals(
            "alreadyGained=monthlyGain 时 totalGain 应 = monthlyGain",
            monthlyGain, s2.discipleTables.cultivations[1], 0.01
        )
    }

    @Test
    fun `alreadyGained超大值 至少保留已获修为`() = runTest {
        hfdFlow.value = HighFrequencyData(
            cultivationUpdates = mapOf("1" to 500.0)
        )
        val s = newCleanShadow("1", realm = 7) // 金丹，maxCult ~800
        val maxCult = s.discipleTables.assemble(1).maxCultivation

        coordinator.scheduleMonthly(s)
        executeUntilComplete()

        // totalGain = max(monthlyGain, alreadyGained) = max(?, 500) >= 500
        // 但 capped at maxCult，所以 actual >= min(500, maxCult)
        val expectedMin = minOf(500.0, maxCult)
        assertTrue(
            "应≥min(alreadyGained, maxCult)=$expectedMin 实际=${s.discipleTables.cultivations[1]}",
            s.discipleTables.cultivations[1] >= expectedMin - 0.01
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. batchMonths > 1 公式验证
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `batchMonths=6 获得多于单月的修为`() = runTest {
        // 单月
        val s1 = newCleanShadow("1", realm = 7)
        coordinator.scheduleMonthly(s1)
        executeUntilComplete()
        val gain1 = s1.discipleTables.cultivations[1]
        assertTrue("单月增益>0: $gain1", gain1 > 0)

        // 热控 + batchMonths=6
        `when`(thermalMonitor.shouldReduceWorkload()).thenReturn(true)
        val s6 = newCleanShadow("1", realm = 7)
        // 在 scheduleMonthly 之前设置 lastSettleMonth，使 computeNonFocusedBatch 输出 batchMonths=6
        setField("nonFocusedLastSettleMonth", -100)
        coordinator.scheduleMonthly(s6)
        executeUntilComplete()
        val gain6 = s6.discipleTables.cultivations[1]

        assertTrue(
            "batchMonths=6 ($gain6) 应大于 batchMonths=1 ($gain1)",
            gain6 > gain1
        )
        // 6个月的增益应当至少是单月的 3 倍（放宽至3倍，避免 cap 影响）
        assertTrue("增益比应≥3, ratio=${gain6 / gain1}", gain6 >= gain1 * 3.0)
    }

    @Test
    fun `batchMonths=6 alreadyGained有值 公式正确不逐月扣`() = runTest {
        val s1 = newCleanShadow("1", realm = 7)
        coordinator.scheduleMonthly(s1)
        executeUntilComplete()
        val monthlyGain = s1.discipleTables.cultivations[1]

        `when`(thermalMonitor.shouldReduceWorkload()).thenReturn(true)
        hfdFlow.value = HighFrequencyData(
            cultivationUpdates = mapOf("1" to monthlyGain)
        )
        val s6 = newCleanShadow("1", realm = 7)
        setField("nonFocusedLastSettleMonth", -100)
        coordinator.scheduleMonthly(s6)
        executeUntilComplete()
        val actual = s6.discipleTables.cultivations[1]

        // 正确: total = max(monthlyGain*6, monthlyGain) = monthlyGain*6
        // 错误(旧): total = (monthlyGain-monthlyGain)*6 + monthlyGain = monthlyGain
        assertTrue(
            "6月+alreadyGained增益($actual)应远超单月($monthlyGain)",
            actual >= monthlyGain * 3.0
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. 突破前提 + 修为上限
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `clean弟子修为正常增长不倒退`() = runTest {
        val s = newCleanShadow("1", realm = 7)
        val tables = s.discipleTables
        val before = 10.0
        tables.cultivations[1] = before
        assertEquals("设置后应立即可读", before, tables.cultivations[1], 0.01)

        coordinator.scheduleMonthly(s)
        executeUntilComplete()

        assertTrue(
            "修为应增长: $before → ${tables.cultivations[1]}",
            tables.cultivations[1] > before
        )
    }

    @Test
    fun `dirty弟子修为正常增长 无需BREAKTHROUGH flag`() = runTest {
        val s = newEquippedShadow("2", realm = 7)
        val tables = s.discipleTables
        // 确认是 dirty
        val cache = SettlementCache(s)
        assertTrue("equipped应在dirtyDiscipleIds", "2" in cache.dirtyDiscipleIds)
        val before = tables.cultivations[2]
        assertEquals(0.0, before, 0.0)

        coordinator.scheduleMonthly(s)
        executeUntilComplete()

        val after = tables.cultivations[2]
        assertTrue(
            "dirty弟子修为应增长: before=$before after=$after",
            after > 0.0
        )
    }

    @Test
    fun `修为不可超过maxCultivation上限`() = runTest {
        val s = newCleanShadow("1", realm = 7)
        val tables = s.discipleTables
        val maxCult = tables.assemble(1).maxCultivation
        tables.cultivations[1] = maxCult - 1.0

        coordinator.scheduleMonthly(s)
        executeUntilComplete()

        assertTrue("不超上限: ${tables.cultivations[1]} ≤ $maxCult",
            tables.cultivations[1] <= maxCult + 0.01
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    private fun newCleanShadow(
        id: String, realm: Int = 9, layer: Int = 1
    ): MutableGameState {
        val tables = DiscipleTables()
        tables.insert(Disciple(
            id = id, name = "测试$id",
            realm = realm, realmLayer = layer,
            cultivation = 0.0,
            isAlive = true, discipleType = "outer",
            lifespan = 80, age = 20
        ))
        return buildShadow(tables)
    }

    private fun newEquippedShadow(
        id: String, realm: Int = 9, layer: Int = 1
    ): MutableGameState {
        val tables = DiscipleTables()
        tables.insert(Disciple(
            id = id, name = "测试$id",
            realm = realm, realmLayer = layer,
            cultivation = 0.0,
            isAlive = true, discipleType = "outer",
            lifespan = 80, age = 20,
            equipment = EquipmentSet(weaponId = "sword_test")
        ))
        return buildShadow(
            tables,
            equipmentInstances = listOf(
                EquipmentInstance(id = "sword_test", name = "剑",
                    slot = EquipmentSlot.WEAPON, rarity = 1)
            )
        )
    }

    private fun buildShadow(
        tables: DiscipleTables,
        equipmentInstances: List<EquipmentInstance> = emptyList()
    ) = MutableGameState(
        gameData = GameData(
            gameYear = 1, gameMonth = 1,
            elderSlots = ElderSlots(), sectPolicies = SectPolicies(),
            residenceSlots = emptyList(), placedBuildings = emptyList(),
            librarySlots = emptyList(),
            autoEquipFromWarehouseRootCounts = emptySet(),
            autoLearnFromWarehouseRootCounts = emptySet()
        ),
        discipleTables = tables,
        equipmentStacks = EntityStore(),
        equipmentInstances = EntityStore(equipmentInstances),
        manualStacks = EntityStore(), manualInstances = EntityStore(),
        pills = EntityStore(), materials = EntityStore(),
        herbs = EntityStore(), seeds = EntityStore(),
        storageBags = EntityStore(),
        teams = emptyList(), battleLogs = emptyList(),
        isPaused = false, isLoading = false, isSaving = false
    )

    private suspend fun executeUntilComplete() {
        var safety = 0
        while (scheduler.hasPendingWork && safety < 100) {
            coordinator.executeStep()
            safety++
        }
        assertFalse("结算应在100步内完成", scheduler.hasPendingWork)
    }

    /** 反射设置 SettlementCoordinator 私有字段 */
    private fun setField(name: String, value: Int) {
        val f = SettlementCoordinator::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.setInt(coordinator, value)
    }
}
