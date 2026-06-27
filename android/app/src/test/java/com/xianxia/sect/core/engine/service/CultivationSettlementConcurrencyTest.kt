package com.xianxia.sect.core.engine.service

import android.app.Application
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 修复回归测试：弟子批量消失 bug（异步 clear+insert 覆盖竞态）。
 *
 * 根因：processAnnualSalary 等函数在入口捕获快照后通过 scope.launch 异步
 * clear()+insert(陈旧快照)，与 createSettlementShadow().deepCopy() 并发，
 * 导致 shadow 捕获到空 ids，swapFromShadow 整体覆盖活表 → 全体弟子消失。
 *
 * 修复：改为 suspend，在 stateStore.update 事务内读取最新 discipleTables
 * 并同步操作，消除异步覆盖竞态。
 *
 * 本测试验证：每次操作后弟子数量不丢失（bug 核心症状）+ 功能正确性。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CultivationSettlementConcurrencyTest {

    private lateinit var stateStore: GameStateStore
    private lateinit var scopeProvider: ApplicationScopeProvider
    private lateinit var cultivationSettlement: CultivationSettlement
    private lateinit var lifecycleProcessor: DiscipleLifecycleProcessor

    @Before
    fun setUp() {
        scopeProvider = ApplicationScopeProvider()
        stateStore = GameStateStoreImpl(scopeProvider, mock(GameStateRepository::class.java))
        cultivationSettlement = CultivationSettlement(
            stateStore,
            mock(InventorySystem::class.java),
            InventoryConfig(),
            mock(BattleSystem::class.java),
            mock(ProductionCoordinator::class.java),
            mock(ProductionSlotRepository::class.java),
            mock(DiscipleService::class.java),
            mock(com.xianxia.sect.core.engine.service.CultivationCore::class.java),
            mock(DiscipleBreakthroughHandler::class.java),
            scopeProvider
        )
        lifecycleProcessor = DiscipleLifecycleProcessor(
            stateStore,
            InventoryConfig(),
            scopeProvider,
            mock(ProductionSlotRepository::class.java)
        )
        runBlocking {
            stateStore.reset()
            stateStore.update {
                gameData = GameData(
                    gameYear = 2,
                    gameMonth = 1,
                    spiritStones = 100_000L
                )
            }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            delay(100)
            stateStore.reset()
        }
        scopeProvider.close()
    }

    // ═══════════════════════════════════════════════════════════════
    // processAnnualSalary
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `processAnnualSalary_灵石充足_全员发放忠诚加一`() = runTest {
        insertDisciples(5)
        val before = getDisciples()
        assertEquals(5, before.size)
        assertEquals(50, before[0].skills.loyalty)

        cultivationSettlement.processAnnualSalary(2)

        val after = getDisciples()
        assertEquals("弟子数量必须不变", 5, after.size)
        assertEquals("忠诚度应 +1", 51, after[0].skills.loyalty)
        assertEquals("俸禄次数应 +1", 1, after[0].skills.salaryPaidCount)
    }

    @Test
    fun `processAnnualSalary_灵石不足_全员不发`() = runTest {
        insertDisciples(3)
        stateStore.update {
            gameData = gameData.copy(spiritStones = 0L)
        }
        val before = getDisciples()

        cultivationSettlement.processAnnualSalary(2)

        val after = getDisciples()
        assertEquals("弟子数量不变", 3, after.size)
        assertEquals("忠诚度不变", before[0].skills.loyalty, after[0].skills.loyalty)
        assertEquals("俸禄次数不变", before[0].skills.salaryPaidCount, after[0].skills.salaryPaidCount)
    }

    @Test
    fun `processAnnualSalary_空弟子列表_不崩溃`() = runTest {
        cultivationSettlement.processAnnualSalary(2)
        assertEquals(0, getDisciples().size)
    }

    // ═══════════════════════════════════════════════════════════════
    // processResidenceLoyalty
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `processResidenceLoyalty_居所弟子_忠诚度提升且弟子数量不变`() = runTest {
        insertDisciples(4)
        stateStore.update {
            gameData = gameData.copy(
                residenceSlots = listOf(
                    ResidenceSlot(buildingInstanceId = "b1", slotIndex = 0, discipleId = "1"),
                    ResidenceSlot(buildingInstanceId = "b1", slotIndex = 1, discipleId = "2")
                )
            )
        }

        cultivationSettlement.processResidenceLoyalty()

        val after = getDisciples()
        assertEquals("弟子数量必须不变", 4, after.size)
        val d1 = after.find { it.id == "1" }!!
        val d3 = after.find { it.id == "3" }!!
        assertTrue("居所弟子忠诚度应提升", d1.skills.loyalty > 50)
        assertEquals("非居所弟子忠诚度不变", 50, d3.skills.loyalty)
    }

    // ═══════════════════════════════════════════════════════════════
    // processGriefExpiry
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `processGriefExpiry_悲伤过期_清除griefEndYear且弟子数量不变`() = runTest {
        insertDisciples(3)
        stateStore.update {
            val d = discipleTables.assemble(1)
            discipleTables.remove(1)
            discipleTables.insert(d.copy(social = d.social.copy(griefEndYear = 2)))
        }

        lifecycleProcessor.processGriefExpiry(2)

        val after = getDisciples()
        assertEquals("弟子数量必须不变", 3, after.size)
        assertNull("griefEndYear 应被清除", after.find { it.id == "1" }!!.social.griefEndYear)
    }

    @Test
    fun `processGriefExpiry_悲伤未过期_保留griefEndYear`() = runTest {
        insertDisciples(2)
        stateStore.update {
            val d = discipleTables.assemble(1)
            discipleTables.remove(1)
            discipleTables.insert(d.copy(social = d.social.copy(griefEndYear = 5)))
        }

        lifecycleProcessor.processGriefExpiry(2)

        val after = getDisciples()
        assertEquals(2, after.size)
        assertEquals(5, after.find { it.id == "1" }!!.social.griefEndYear)
    }

    // ═══════════════════════════════════════════════════════════════
    // processReflectionRelease
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `processReflectionRelease_闭关结束_状态重置且弟子数量不变`() = runTest {
        insertDisciples(3)
        stateStore.update {
            val d = discipleTables.assemble(1)
            discipleTables.remove(1)
            discipleTables.insert(d.copy(
                status = DiscipleStatus.REFLECTING,
                statusData = mapOf("reflectionEndYear" to "2")
            ))
        }

        lifecycleProcessor.processReflectionRelease(2)

        val after = getDisciples()
        assertEquals("弟子数量必须不变", 3, after.size)
        val d1 = after.find { it.id == "1" }!!
        assertEquals("状态应重置为IDLE", DiscipleStatus.IDLE, d1.status)
        assertTrue("忠诚度应提升", d1.skills.loyalty > 50)
    }

    @Test
    fun `processReflectionRelease_闭关未结束_状态不变`() = runTest {
        insertDisciples(2)
        stateStore.update {
            val d = discipleTables.assemble(1)
            discipleTables.remove(1)
            discipleTables.insert(d.copy(
                status = DiscipleStatus.REFLECTING,
                statusData = mapOf("reflectionEndYear" to "5")
            ))
        }

        lifecycleProcessor.processReflectionRelease(2)

        val after = getDisciples()
        assertEquals(2, after.size)
        assertEquals(DiscipleStatus.REFLECTING, after.find { it.id == "1" }!!.status)
    }

    @Test
    fun `processReflectionRelease_无闭关弟子_不崩溃`() = runTest {
        insertDisciples(3)
        lifecycleProcessor.processReflectionRelease(2)
        assertEquals(3, getDisciples().size)
    }

    // ═══════════════════════════════════════════════════════════════
    // settleSalaryOnBreakthrough
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `settleSalaryOnBreakthrough_突破时_补发俸禄且弟子数量不变`() = runTest {
        insertDisciples(3)

        cultivationSettlement.settleSalaryOnBreakthrough("1", 2)

        val after = getDisciples()
        assertEquals("弟子数量必须不变", 3, after.size)
        val d1 = after.find { it.id == "1" }!!
        assertTrue("突破弟子忠诚度应提升", d1.skills.loyalty > 50)
    }

    @Test
    fun `settleSalaryOnBreakthrough_弟子不存在_不崩溃`() = runTest {
        insertDisciples(2)
        cultivationSettlement.settleSalaryOnBreakthrough("999", 2)
        assertEquals(2, getDisciples().size)
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    private suspend fun insertDisciples(count: Int) {
        stateStore.update {
            for (i in 1..count) {
                discipleTables.insert(
                    Disciple(
                        id = i.toString(),
                        name = "弟子$i",
                        realm = 9,
                        realmLayer = 1,
                        cultivation = 0.0,
                        isAlive = true,
                        discipleType = "inner",
                        lifespan = 80,
                        age = 20,
                        skills = SkillStats(loyalty = 50)
                    )
                )
            }
        }
    }

    private suspend fun getDisciples(): List<Disciple> =
        stateStore.updateAndReturn { discipleTables.assembleAll() }
}
