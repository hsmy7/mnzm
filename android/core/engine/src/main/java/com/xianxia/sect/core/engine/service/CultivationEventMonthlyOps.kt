package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.battle.AISectGarrisonManager
import com.xianxia.sect.core.model.YearlyReport
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog



/**
 * CultivationEventProcessor 月度/年度事件域 Ops 扩展。
 */
/** 单处理器耗时告警阈值（ms）：年变/月变处理器超过即打日志，供性能观测 */
private const val SLOW_OP_THRESHOLD_MS = 25L

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun MutableGameState.safelyRunInState(name: String, block: MutableGameState.() -> Unit) {
        val start = System.currentTimeMillis()
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(CultivationEventProcessor.TAG, "月度事件[$name] 异常", e)
        }
        val elapsed = System.currentTimeMillis() - start
        if (elapsed > SLOW_OP_THRESHOLD_MS) {
            DomainLog.w(CultivationEventProcessor.TAG, "月度事件[$name] 耗时 ${elapsed}ms")
        }
    }

    /**
     * 带状态版本的月度事件处理 — 在已存在的事务内使用。
     * 与 [processMonthlyEvents] 功能相同，但操作在传入的 state 上，
     * 而非打开新的 [stateStore.update]。
     */
internal fun CultivationEventProcessor.processMonthlyEvents(year: Int, month: Int, state: MutableGameState) {
        state.gameData = state.gameData.copy(recruitCountThisMonth = 0)
        state.safelyRunInState("completedMissions") { processCompletedMissionsLazy(year, month) }
        state.safelyRunInState("aiSectOperations") { caveExplorationProcessor.get().processAISectOperations(year, month,
            state) }
        state.safelyRunInState("gameOverCheck") { checkGameOverCondition(state) }
        state.safelyRunInState("scoutExpiry") { processScoutInfoExpiryLazy(year, month, state) }
        state.safelyRunInState("aiBeastAttacksRemaining") { aiSectBeastAttackProcessor.processRemainingTargets(state) }
        if (month == 12) {
            state.safelyRunInState("autoBuy") { autoBuyService.executeAutoBuy(year, month, state) }
        }
        state.safelyRunInState("spiritMineProduction") { cultivationSettlement
            .processSpiritMineProductionMonthly(state) }
        state.safelyRunInState("disciplePurchase") { disciplePurchaseService.executePurchase(year, month, state) }
        state.safelyRunInState("vassalBreakaway") { vassalService.processMonthlyBreakawayCheck(state) }
        state.safelyRunInState("missionRefresh") { processMissionRefreshIfDue(month, state) }
        // 秘境到期检查在前（关闭后 AI 队伍清场，后续不再派遣）
        state.safelyRunInState("secretRealmExpiry") {
            secretRealmService.processMonthlyExpiryCheck(this, year)
        }
        state.safelyRunInState("secretRealmAiTeams") {
            secretRealmAIProcessor.processMonthlyAiTeams(this)
        }
    }

internal fun CultivationEventProcessor.processMonthlyEvents(year: Int, month: Int) {
        // 单事务：所有月度事件原子提交
        stateStore.update {
            // 每月开始时重置招募月度计数
            gameData = gameData.copy(recruitCountThisMonth = 0)
            safelyRunInState("completedMissions") { processCompletedMissionsLazy(year, month) }
            safelyRunInState("aiSectOperations") { caveExplorationProcessor.get().processAISectOperations(year, month,
                this) }
            safelyRunInState("gameOverCheck") { checkGameOverCondition(this) }
            safelyRunInState("scoutExpiry") { processScoutInfoExpiryLazy(year, month, this) }
            safelyRunInState("aiBeastAttacksRemaining") { aiSectBeastAttackProcessor.processRemainingTargets(this) }
            if (month == 12) {
                safelyRunInState("autoBuy") { autoBuyService.executeAutoBuy(year, month, this) }
            }
            safelyRunInState("spiritMineProduction") { cultivationSettlement.processSpiritMineProductionMonthly(this) }
            safelyRunInState("disciplePurchase") { disciplePurchaseService.executePurchase(year, month, this) }
            safelyRunInState("vassalBreakaway") { vassalService.processMonthlyBreakawayCheck(this) }
            safelyRunInState("missionRefresh") { processMissionRefreshIfDue(month, this) }
            // 秘境到期检查在前（关闭后 AI 队伍清场，后续不再派遣）
            safelyRunInState("secretRealmExpiry") {
                secretRealmService.processMonthlyExpiryCheck(this, year)
            }
            safelyRunInState("secretRealmAiTeams") {
                secretRealmAIProcessor.processMonthlyAiTeams(this)
            }
        }
    }
internal fun CultivationEventProcessor.processYearlyEvents(year: Int) {
        // L3b 年变分帧：拆为 T1 立即组（单事务，保原相对序）+ T2 延迟组（入队，
        // 由 tick 预算 drain 逐 tick 分摊）。重活（AI 老化/外交/秘境）移出
        // 1 月单事务，消除"1 月卡死数秒"（工作随存档规模无界增长）。
        // 分组依据见 docs/architecture.md 惰性结算章节；T2 全部有差值判据自愈
        // （下年补跑）或延迟无感语义，且存档前 flush 保证"快照 ⇒ 队列已空"。
        stateStore.update {
            // T1 立即组（8 项）：状态重推导必须当月立即、
            // garrisonAndReport（#20）与纳贡同事务（buffer 依赖）
            safelyRunInState("yearlyTribute") { vassalService.processYearlyTribute() }
            safelyRunInState("yearlyVassalTribute") { vassalService.processYearlyVassalTribute(year) }
            safelyRunInState("discipleAging") {
                discipleLifecycleProcessor.processDiscipleAging(year)
            }
            safelyRunInState("merchantRefreshChance") {
                merchantAndRecruitService.giveMerchantRefreshChanceIfDue(year)
            }
            safelyRunInState("yearlyAging") {
                discipleLifecycleProcessor.processYearlyAging(year)
            }
            safelyRunInState("reflectionRelease") {
                discipleLifecycleProcessor.processReflectionRelease(year)
            }
            // 年度报告 + 驻军轮换（与纳贡同事务：annual* 字段必须计入年报）
            safelyRunInState("garrisonAndReport") { runGarrisonAndReport(year, this) }
            // 1 月自动购买置于年报快照后（新年 1 月购买计入
            // 新年年报——与年俸 processAnnualSalary 快照后执行的归属一致；
            // 12 月 autoBuy 不受影响，本就属旧年）
            safelyRunInState("autoBuy") { autoBuyService.executeAutoBuy(year, 1) }
            // T2 延迟组（8 项）入队：FIFO = 年变原相对序（#4→#12→#13→#15→#16→#17→#19→#22）
            // 必须与 T1 同事务提交：若在事务外入队，存档线程 flush 可能在
            // "T1 提交 → 入队"之间排空队列并取快照，快照缺失全部 T2（竞态窗口）。
            // 入队仅写内存队列（无状态修改），事务内执行无副作用。
            enqueueYearlyOps(year)
        }
    }

    /**
     * L3b：年变延迟组入队（T2 8 项）。
     *
     * 全部有自愈/延迟无感语义：差值判据（lastTradeYear 等）
     * 跳过次年自动补跑；AI 老化/外交/秘境晚 1 tick 无感。
     * 防御：入口先 clear —— 年变双触发时丢弃旧批次防重复执行（差值判据兜底自愈）。
     */
internal fun CultivationEventProcessor.enqueueYearlyOps(year: Int) {
        yearlyOpsQueue.clear()
        val ops: List<Pair<String, MutableGameState.() -> Unit>> = listOf(
            // #4 AI 弟子老化
            "sectDisciplesAging" to { caveExplorationProcessor.get().processSectDisciplesAging(year, this) },
            // #12 商人收购刷新
            "refreshAcquisition" to { merchantAndRecruitService.refreshMerchantAcquisition(year, 1) },
            // #13 AI 宗门交易列表刷新（每 3 年强制，差值判据与懒刷新统一）
            "sectTradeRefresh" to { diplomacyService.refreshAllSectTrades(year) },
            // #15 联盟到期
            "allianceExpiry" to { diplomacyEventProcessor.checkAllianceExpiry(year) },
            // #16 联盟好感衰减检查
            "allianceFavorDrop" to { diplomacyEventProcessor.checkAllianceFavorDrop() },
            // #17 AI 联盟建立
            "aiAlliances" to { diplomacyEventProcessor.processAIAlliances(year) },
            // #19 好感衰减
            "favorDecay" to { diplomacyEventProcessor.processFavorDecay(year) },
            // #22 远古秘境刷新
            "ancientSecretRealmSpawn" to { secretRealmService.processYearlySpawn(year, this) }
        )
        ops.forEach { (name, op) ->
            yearlyOpsQueue.enqueue {
                safelyRunInState(name) { op(this) }
            }
        }
    }

    /**
     * 年变：驻军轮换 + 年度报告快照（单次原子 update）。
     * 已从 [processYearlyEvents] 内联代码提取，降低函数复杂度。
     */
@Suppress("UnusedParameter") // year: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
internal fun CultivationEventProcessor.runGarrisonAndReport(year: Int, state: MutableGameState) {
        // 基于事务 buffer 读写：年变单事务内前序事件（纳贡/俸禄等）写入的
        // annual* 字段必须计入年报，禁止读已提交快照（与招募列表不刷新同源修复）。
        val currentData = state.gameData
        val rotated = AISectGarrisonManager.rotateGarrisonSlots(currentData)
        val report = YearlyReport(
            year = currentData.gameYear - 1,
            totalIncome = currentData.annualTotalIncome,
            totalExpenditure = currentData.annualTotalExpenditure,
            incomeBySource = currentData.annualIncomeBySource,
            expenditureByReason = currentData.annualExpenditureByReason,
            equipmentBySource = currentData.annualEquipmentBySource,
            pillBySource = currentData.annualPillBySource,
            herbBySource = currentData.annualHerbBySource,
            alchemyCompleted = currentData.annualAlchemyCount,
            forgeCompleted = currentData.annualForgeCount,
            herbsHarvested = currentData.annualHerbCount,
            newDisciples = currentData.annualNewDisciples,
            deceasedDisciples = currentData.annualDeceasedDisciples,
            desertedDisciples = currentData.annualDesertedDisciples
        )
        state.gameData = currentData.copy(
            worldMapSects = rotated.worldMapSects,
            yearlyReports = (currentData.yearlyReports + report)
                .takeLast(GameConfig.Logs.MAX_YEARLY_REPORTS),
            annualIncomeBySource = emptyMap(),
            annualExpenditureByReason = emptyMap(),
            annualTotalIncome = 0L,
            annualTotalExpenditure = 0L,
            annualEquipmentBySource = emptyMap(),
            annualPillBySource = emptyMap(),
            annualHerbBySource = emptyMap(),
            annualAlchemyCount = 0,
            annualForgeCount = 0,
            annualHerbCount = 0,
            annualNewDisciples = 0,
            annualDeceasedDisciples = 0,
            annualDesertedDisciples = 0
        )
    }
