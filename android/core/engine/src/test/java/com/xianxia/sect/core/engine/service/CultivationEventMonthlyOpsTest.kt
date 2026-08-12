package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyService
import com.xianxia.sect.core.engine.domain.diplomacy.VassalService
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * runSectRecruitmentIfDue（AI 宗门弟子三年一度招募差值判据）单元测试。
 *
 * 背景：AI 宗门弟子招募由"每年 0~6 名"改为"每 3 年 1~5 名"（2026-08-06）。
 * 采用差值判据（非模运算）：老存档/跨版本相位漂移自愈；招募失败时
 * lastAiSectRecruitYear 不更新，次年自动重试（与 refreshRecruitList 同款语义）。
 */
class CultivationEventMonthlyOpsTest {

    private fun createState(
        lastAiSectRecruitYear: Int = 0,
        aiSectDisciples: Map<String, List<Disciple>> = emptyMap(),
        recruitList: List<Disciple> = emptyList()
    ): MutableGameState {
        val tables = DiscipleTables()
        tables.writeAllowed = true
        return MutableGameState(
            gameData = GameData(
                lastAiSectRecruitYear = lastAiSectRecruitYear,
                aiSectDisciples = aiSectDisciples,
                recruitList = recruitList
            ),
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

    private fun makeDisciple(id: String): Disciple = Disciple(id = id)

    @Test
    fun `runSectRecruitmentIfDue - 老档升级后第3年立即触发一次 自愈`() {
        val state = createState(lastAiSectRecruitYear = 0)
        var calls = 0
        state.runSectRecruitmentIfDue(3) { calls++ }
        assertEquals("year=3 且 lastAiSectRecruitYear=0（老档）应立即触发", 1, calls)
        assertEquals("触发后标记应写为当前年", 3, state.gameData.lastAiSectRecruitYear)
    }

    @Test
    fun `runSectRecruitmentIfDue - 未满3年不触发`() {
        val state = createState(lastAiSectRecruitYear = 0)
        var calls = 0
        state.runSectRecruitmentIfDue(2) { calls++ }
        assertEquals("year=2 未满间隔不应触发", 0, calls)
        assertEquals("未触发时标记保持不变", 0, state.gameData.lastAiSectRecruitYear)
    }

    @Test
    fun `runSectRecruitmentIfDue - 满3年再次触发 未满不触发`() {
        val state = createState(lastAiSectRecruitYear = 5)
        var calls = 0
        state.runSectRecruitmentIfDue(7) { calls++ }
        assertEquals("year=7（距上次仅2年）不应触发", 0, calls)
        assertEquals("未触发时标记保持不变", 5, state.gameData.lastAiSectRecruitYear)

        state.runSectRecruitmentIfDue(8) { calls++ }
        assertEquals("year=8（距上次3年）应触发", 1, calls)
        assertEquals("触发后标记应更新为8", 8, state.gameData.lastAiSectRecruitYear)
    }

    @Test
    fun `runSectRecruitmentIfDue - 招募失败不更新标记 次年自动重试`() {
        val state = createState(lastAiSectRecruitYear = 3)
        var calls = 0
        // 首次招募抛异常——真实链路由 safelyRunInState 捕获后继续年变，
        // 此处用 runCatching 模拟捕获语义（runSectRecruitmentIfDue 自身不吞异常）
        runCatching {
            state.runSectRecruitmentIfDue(6) {
                calls++
                error("模拟招募失败")
            }
        }
        assertEquals("失败时 recruitment 已执行", 1, calls)
        assertEquals("失败后标记不得更新（次年自动重试）", 3, state.gameData.lastAiSectRecruitYear)

        // 次年重试成功
        state.runSectRecruitmentIfDue(7) { calls++ }
        assertEquals("次年（距上次4年）应自动重试成功", 2, calls)
        assertEquals("重试成功后标记更新为7", 7, state.gameData.lastAiSectRecruitYear)
    }

    @Test
    fun `runSectRecruitmentIfDue - 同事务buffer写回不覆盖前序修改`() {
        // 年变单事务内：recruitment 写 aiSectDisciples/recruitList 后，
        // 标记 copy 必须保留这些修改（对齐 processSectDisciplesYearlyRecruitment 的 buffer 语义）
        val state = createState(
            lastAiSectRecruitYear = 0,
            aiSectDisciples = mapOf("ai1" to listOf(makeDisciple("old"))),
            recruitList = listOf(makeDisciple("recruit_old"))
        )
        state.runSectRecruitmentIfDue(4) {
            gameData = gameData.copy(
                aiSectDisciples = gameData.aiSectDisciples +
                    ("ai1" to listOf(makeDisciple("old"), makeDisciple("new_ai"))),
                recruitList = gameData.recruitList + makeDisciple("recruit_fresh")
            )
        }
        assertEquals("aiSectDisciples 前序修改应保留", 2, state.gameData.aiSectDisciples["ai1"]!!.size)
        assertTrue(
            "recruitList 前序修改应保留",
            state.gameData.recruitList.map { it.id }.containsAll(listOf("recruit_old", "recruit_fresh"))
        )
        assertEquals("标记应写为当前年", 4, state.gameData.lastAiSectRecruitYear)
    }

    // ═══════════════════════════════════════════════════════════════
    // L3b 年变拆分守卫：T1 立即组（11 项，单事务同步执行）
    // T2 延迟组（11 项，入队不立即执行，drain 后按原相对序执行）
    // ═══════════════════════════════════════════════════════════════

    // 测试夹具：10 个服务引用聚合（T1/T2 顺序断言各自需要），分组类反而引入中间结构
    @Suppress("LongParameterList")
    private class ProcessorHarness(
        val processor: CultivationEventProcessor,
        val vassalService: VassalService,
        val recruitService: RecruitService,
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
        val recruitService = mockSmart<RecruitService>()
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
            recruitService = recruitService,
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
            lawEnforcementProcessor = mockSmart(),
            rngManager = mockSmart(),
            secretRealmService = secretRealmService,
            secretRealmAIProcessor = mockSmart(),
            deathHandler = mockSmart()
        )
        return ProcessorHarness(
            processor, vassalService, recruitService, merchantAndRecruitService,
            discipleLifecycleProcessor, autoBuyService, caveProcessor,
            diplomacyService, diplomacyEventProcessor, secretRealmService
        )
    }

    @Test
    fun `processYearlyEvents - T1 立即组 11 项单事务同步执行 保持原相对序`() {
        val h = createHarness()

        h.processor.processYearlyEvents(2026)

        val inOrder: InOrder = Mockito.inOrder(
            h.vassalService, h.discipleLifecycleProcessor, h.recruitService,
            h.merchantAndRecruitService, h.autoBuyService
        )
        // 原相对序（autoReject 为 object 静态方法，跳过 verify）：
        // #1 → #2 → #3 → #5 → #6(autoReject) → #7 → #8 → #9 → #11 → #20 → #18
        //（2026-08-11 归属修复：autoBuy #18 移至年报快照 #20 之后，新年 1 月购买计入新年）
        inOrder.verify(h.vassalService).processYearlyTribute()
        inOrder.verify(h.vassalService).processYearlyVassalTribute(2026)
        inOrder.verify(h.discipleLifecycleProcessor).processDiscipleAging(2026)
        inOrder.verify(h.recruitService).refreshRecruitList(2026)
        inOrder.verify(h.merchantAndRecruitService).giveMerchantRefreshChanceIfDue(2026)
        inOrder.verify(h.discipleLifecycleProcessor).processYearlyAging(2026)
        inOrder.verify(h.recruitService).ageRecruitList(2026)
        inOrder.verify(h.discipleLifecycleProcessor).processReflectionRelease(2026)
        inOrder.verify(h.autoBuyService).executeAutoBuy(2026, 1)

        // T2 成员不得在 T1 阶段执行
        verify(h.caveProcessor, never()).processSectDisciplesAging(any(), any())
        verify(h.diplomacyService, never()).refreshAllSectTrades(any())
        verify(h.diplomacyEventProcessor, never()).processAIAlliances(any())
        verify(h.secretRealmService, never()).processYearlySpawn(any(), any())
    }

    @Test
    fun `processYearlyEvents - T2 延迟组 11 项入队 不立即执行 drain 后按原相对序`() {
        val h = createHarness()

        h.processor.processYearlyEvents(2026)

        assertEquals("T2 延迟组应入队 11 项", 11, h.processor.yearlyOpsQueue.size)

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
        inOrder.verify(h.caveProcessor).processSectDisciplesYearlyRecruitment(eq(2026), any())
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
