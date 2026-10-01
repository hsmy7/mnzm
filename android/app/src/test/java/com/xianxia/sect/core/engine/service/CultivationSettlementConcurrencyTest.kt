package com.xianxia.sect.core.engine.service

import android.app.Application
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.salaryPaidCount
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config



/**
 * 并发回归测试：弟子批量消失 bug（异步 clear+insert 覆盖竞态）。
 *
 * 结算路径约束：必须在 stateStore.update 事务内读取最新 discipleTables
 * 并同步操作——若在入口捕获快照后经 scope.launch 异步 clear()+insert(陈旧快照)，
 * 会与 stateStore.update 事务并发，事务内读到空 ids，整体覆盖活表 → 全体弟子消失。
 *
 * 本测试验证：每次操作后弟子数量不丢失（核心症状）+ 功能正确性。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CultivationSettlementConcurrencyTest {

    private lateinit var stateStore: GameStateStore
    private lateinit var scopeProvider: ApplicationScopeProvider
    private lateinit var cultivationSettlement: CultivationSettlement
    private lateinit var lifecycleProcessor: DiscipleLifecycleProcessor
    private lateinit var spiritStoneWallet: SpiritStoneWallet
    private val ledger = SpiritStoneLedger()
    private val eventBus = mock(EventBus::class.java)

    @Before
    fun setUp() {
        scopeProvider = ApplicationScopeProvider()
        stateStore = GameStateStoreImpl(scopeProvider)
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = true
        spiritStoneWallet = SpiritStoneWallet(stateStore, ledger, eventBus)
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
        cultivationSettlement = CultivationSettlement(
            stateStore,
            scopeProvider,
            spiritStoneWallet,
            mock()
        )
        lifecycleProcessor = DiscipleLifecycleProcessor(
            stateStore,
            scopeProvider,
            mock(com.xianxia.sect.core.engine.domain.production.ProductionCoordinator::class.java),
            mock(com.xianxia.sect.core.event.EventBusPort::class.java),
            mock(com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup::class.java),
            mock(com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService::class.java),
            com.xianxia.sect.core.engine.di.IoDispatcher(),
            com.xianxia.sect.core.engine.system.InventorySystem(
                stateStore,
                InventoryConfig(),
            ),
            deathHandler = mock(com.xianxia.sect.core.exploration.DiscipleDeathHandler::class.java)
        )
    }

    @After
    fun tearDown() {
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = false
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
    fun `processAnnualSalary_灵石充足_全员发放且计数落账`() = runTest {
        insertDisciples(5)
        val before = getDisciples()
        assertEquals(5, before.size)
        assertEquals(0, before[0].skills.salaryPaidCount)

        cultivationSettlement.processAnnualSalary(2)

        val after = getDisciples()
        assertEquals("弟子数量必须不变", 5, after.size)
        assertEquals("俸禄次数应 +1", 1, after[0].skills.salaryPaidCount)
    }

    @Test
    fun `processAnnualSalary_灵石不足_不发俸禄不改计数`() = runTest {
        insertDisciples(3)
        stateStore.update {
            gameData = gameData.copy(spiritStones = 0L)
        }
        val before = getDisciples()

        cultivationSettlement.processAnnualSalary(2)

        val after = getDisciples()
        assertEquals("弟子数量不变", 3, after.size)
        assertEquals("俸禄次数不变", before[0].skills.salaryPaidCount, after[0].skills.salaryPaidCount)
    }

    @Test
    fun `processAnnualSalary_空弟子列表_不崩溃`() = runTest {
        cultivationSettlement.processAnnualSalary(2)
        assertEquals(0, getDisciples().size)
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
        // GameStateStoreImpl 的 update 内 mutationVersion 检测对
        // clear+insert 未触发 _disciplesFlow 刷新（引用未变，mutationVersion
        // 在 reentrantBuffer 提交路径中可能被跳过）。
        // 直接读 stateStore 的 _discipleTables 确认写入已生效。
        assertTrue("道德度应提升, actual=" + d1.skills.morality, d1.skills.morality > 50)
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
        assertEquals("俸禄次数应 +1", 1, d1.skills.salaryPaidCount)
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
                        discipleType = "inner"
                    )
                )
            }
        }
    }

    private suspend fun getDisciples(): List<Disciple> =
        stateStore.updateAndReturn { discipleTables.assembleAll() }
}
