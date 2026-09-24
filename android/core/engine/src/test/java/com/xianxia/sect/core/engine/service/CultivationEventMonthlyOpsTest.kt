package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.config.ConfigLoader
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyService
import com.xianxia.sect.core.engine.domain.diplomacy.VassalService
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.InOrder
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import javax.inject.Provider

/**
 * L3b 年变拆分守卫单元测试：
 * T1 立即组（单事务同步执行，保持原相对序）+
 * T2 延迟组（入队不立即执行，drain 后按原相对序执行）。
 */
class CultivationEventMonthlyOpsTest {

    private fun createState(): MutableGameState {
        val tables = DiscipleTables()
        tables.writeAllowed = true
        return MutableGameState(
            gameData = GameData(),
            discipleTables = tables,
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
                        battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // L3b 年变拆分守卫：T1 立即组（8 项，单事务同步执行）
    // T2 延迟组（10 项，入队不立即执行，drain 后按原相对序执行）
    // ═══════════════════════════════════════════════════════════════

    // 测试夹具：10 个服务引用聚合（T1/T2 顺序断言各自需要），分组类反而引入中间结构
    @Suppress("LongParameterList")
    private class ProcessorHarness(
        val processor: CultivationEventProcessor,
        val vassalService: VassalService,
        val merchantAndRecruitService: MerchantAndRecruitService,
        val discipleLifecycleProcessor: DiscipleLifecycleProcessor,
        val autoBuyService: AutoBuyService,
        val caveProcessor: CaveExplorationProcessor,
        val diplomacyService: DiplomacyService,
        val diplomacyEventProcessor: DiplomacyEventProcessor,
        val secretRealmService: SecretRealmService
    )

    // 测试夹具组装：状态桩 + 26 依赖 mock + processor 构造，逐行声明不可再拆
    @Suppress("LongMethod")
    private fun createHarness(): ProcessorHarness {
        // Fake 提供真实语义：update 写内部状态、gameData 等 flow 全真实——
        // 等价 mock 时代 gameData stub + update 路由 stub；测试仅 verify 调用序
        val stateStore = FakeAtomicStateStore()

        val vassalService = mockSmart<VassalService>()
        val merchantAndRecruitService = mockSmart<MerchantAndRecruitService>()
        val discipleLifecycleProcessor = mockSmart<DiscipleLifecycleProcessor>()
        val autoBuyService = mockSmart<AutoBuyService>()
        val caveProcessor = mockSmart<CaveExplorationProcessor>()
        val caveProvider = mockSmart<Provider<CaveExplorationProcessor>>()
        whenever(caveProvider.get()).thenReturn(caveProcessor)
        val diplomacyService = mockSmart<DiplomacyService>()
        val diplomacyEventProcessor = mockSmart<DiplomacyEventProcessor>()
        val secretRealmService = mockSmart<SecretRealmService>()

        val processor = CultivationEventProcessor(
            stateStore = stateStore,
            spiritStoneWallet = mockSmart(),
            inventorySystem = mockSmart(),
            inventoryConfig = mockSmart(),
            scopeProvider = mockSmart(),
            discipleService = mockSmart(),
            cultivationCore = mockSmart(),
            breakthroughHandler = mockSmart(),
            cultivationSettlement = mockSmart(),
            battleSystem = mockSmart(),
            merchantAndRecruitService = merchantAndRecruitService,
            caveExplorationProcessor = caveProvider,
            discipleLifecycleProcessor = discipleLifecycleProcessor,
            diplomacyEventProcessor = diplomacyEventProcessor,
            diplomacyService = diplomacyService,
            equipmentManager = mockSmart(),
            manualManager = mockSmart(),
            autoBuyService = autoBuyService,
            vassalService = vassalService,
            disciplePurchaseService = mockSmart(),
            aiSectBeastAttackProcessor = mockSmart(),
            rngManager = mockSmart(),
            secretRealmService = secretRealmService,
            secretRealmAIProcessor = mockSmart(),
            deathHandler = mockSmart(),
            gameConfigProvider = GameConfigProvider(ConfigLoader({ null }))
        )
        return ProcessorHarness(
            processor, vassalService, merchantAndRecruitService,
            discipleLifecycleProcessor, autoBuyService, caveProcessor,
            diplomacyService, diplomacyEventProcessor, secretRealmService
        )
    }

    @Test
    fun `processYearlyEvents - T1 立即组 8 项单事务同步执行 保持原相对序`() {
        val h = createHarness()

        h.processor.processYearlyEvents(2026)

        val inOrder: InOrder = Mockito.inOrder(
            h.vassalService, h.discipleLifecycleProcessor,
            h.merchantAndRecruitService, h.autoBuyService
        )
        // 当前相对序：附庸纳贡 → 附庸年贡 → 弟子老化 → 商人刷新机会 →
        // 年度老化 → 反思释放 → 自动购买
        //（autoBuy 在年报快照之后执行：新年 1 月购买计入新年）
        inOrder.verify(h.vassalService).processYearlyTribute()
        inOrder.verify(h.vassalService).processYearlyVassalTribute(2026)
        inOrder.verify(h.discipleLifecycleProcessor).processDiscipleAging(2026)
        inOrder.verify(h.merchantAndRecruitService).giveMerchantRefreshChanceIfDue(2026)
        inOrder.verify(h.discipleLifecycleProcessor).processYearlyAging(2026)
        inOrder.verify(h.discipleLifecycleProcessor).processReflectionRelease(2026)
        inOrder.verify(h.autoBuyService).executeAutoBuy(2026, 1)

        // T2 成员不得在 T1 阶段执行
        verify(h.caveProcessor, never()).processSectDisciplesAging(any(), any())
        verify(h.diplomacyService, never()).refreshAllSectTrades(any())
        verify(h.diplomacyEventProcessor, never()).processAIAlliances(any())
        verify(h.secretRealmService, never()).processYearlySpawn(any(), any())
    }

    @Test
    fun `processYearlyEvents - T2 延迟组 10 项入队 不立即执行 drain 后按原相对序`() {
        val h = createHarness()

        h.processor.processYearlyEvents(2026)

        assertEquals("T2 延迟组应入队 10 项", 10, h.processor.yearlyOpsQueue.size)

        // 入队后未执行（FIFO 队列，等待 tick drain）
        verify(h.caveProcessor, never()).processSectDisciplesAging(any(), any())
        verify(h.diplomacyService, never()).refreshAllSectTrades(any())

        // drain（模拟 tick 预算充足 → 一次清空）
        h.processor.yearlyOpsQueue.drain(timeBudgetMs = 1000) { op -> op.invoke(createState()) }

        assertEquals("drain 后队列清空", 0, h.processor.yearlyOpsQueue.size)
        val inOrder: InOrder = Mockito.inOrder(
            h.caveProcessor, h.merchantAndRecruitService, h.diplomacyService,
            h.diplomacyEventProcessor, h.discipleLifecycleProcessor, h.secretRealmService
        )
        inOrder.verify(h.caveProcessor).processSectDisciplesAging(eq(2026), any())
        inOrder.verify(h.merchantAndRecruitService).refreshMerchantAcquisition(2026, 1)
        inOrder.verify(h.diplomacyService).refreshAllSectTrades(2026)
        inOrder.verify(h.diplomacyEventProcessor).processCrossSectPartnerMatching(2026, 1)
        inOrder.verify(h.diplomacyEventProcessor).checkAllianceExpiry(2026)
        inOrder.verify(h.diplomacyEventProcessor).checkAllianceFavorDrop()
        inOrder.verify(h.diplomacyEventProcessor).processAIAlliances(2026)
        inOrder.verify(h.diplomacyEventProcessor).processFavorDecay(2026)
        inOrder.verify(h.discipleLifecycleProcessor).processGriefExpiry(2026)
        inOrder.verify(h.secretRealmService).processYearlySpawn(eq(2026), any())
    }
}
