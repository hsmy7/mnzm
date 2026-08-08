package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.battle.AISectGarrisonManager
import com.xianxia.sect.core.model.YearlyReport
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog



/**
 * CultivationEventProcessor 月度/年度事件域 Ops 扩展（P4D）。
 */
internal fun MutableGameState.safelyRunInState(name: String, block: MutableGameState.() -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(CultivationEventProcessor.TAG, "月度事件[$name] 异常", e)
        }
    }

    /**
     * AI 宗门弟子周期性招募（每 [CultivationEventProcessor.AI_SECT_RECRUIT_INTERVAL_YEARS] 年）。
     *
     * 差值判据（非模运算）：老存档/跨版本相位漂移自愈；招募失败时 lastAiSectRecruitYear
     * 不更新，次年自动重试（与 refreshRecruitList 同款语义，见 RecruitService.refreshRecruitList）。
     *
     * 基于事务 buffer 写回：先执行 [recruitment]（其内部会写 aiSectDisciples/recruitList），
     * 后写 lastAiSectRecruitYear，保留同事务前序事件对 buffer 的修改，无覆盖。
     */
    internal fun MutableGameState.runSectRecruitmentIfDue(year: Int, recruitment: MutableGameState.() -> Unit) {
        if (year - gameData.lastAiSectRecruitYear >= CultivationEventProcessor.AI_SECT_RECRUIT_INTERVAL_YEARS) {
            recruitment()
            gameData = gameData.copy(lastAiSectRecruitYear = year)
        }
    }

    /**
     * 带状态版本的月度事件处理 — 在已存在的事务内使用。
     * 与 [processMonthlyEvents] 功能相同，但操作在传入的 state 上，
     * 而非打开新的 [stateStore.update]。
     */
internal fun CultivationEventProcessor.processMonthlyEvents(year: Int, month: Int, state: MutableGameState) {
        state.gameData = state.gameData.copy(recruitCountThisMonth = 0)
        state.safelyRunInState("autoRecruit") {
            RecruitService.processAutoRecruit(state)
        }
        state.safelyRunInState("theft") { lawEnforcementProcessor.processTheftIfNeeded() }
        state.safelyRunInState("lawEnforcement") { lawEnforcementProcessor.processLawEnforcementMonthly() }
        state.safelyRunInState("completedMissions") { processCompletedMissionsLazy(year, month) }
        state.safelyRunInState("aiSectOperations") { caveExplorationProcessor.get().processAISectOperations(year, month, state) }
        state.safelyRunInState("gameOverCheck") { checkGameOverCondition(state) }
        state.safelyRunInState("scoutExpiry") { processScoutInfoExpiryLazy(year, month, state) }
        state.safelyRunInState("aiBeastAttacksRemaining") { aiSectBeastAttackProcessor.processRemainingTargets(state) }
        if (month == 12) {
            state.safelyRunInState("autoBuy") { autoBuyService.executeAutoBuy(year, month, state) }
        }
        state.safelyRunInState("spiritMineProduction") { cultivationSettlement.processSpiritMineProductionMonthly(state) }
        state.safelyRunInState("disciplePurchase") { disciplePurchaseService.executePurchase(year, month, state) }
        state.safelyRunInState("monthlyCultivation") { processMonthlyCultivationAndAuto(state) }
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
            // 每月开始时重置招募月度计数，使当月招募享有完整上限配额
            gameData = gameData.copy(recruitCountThisMonth = 0)
            safelyRunInState("autoRecruit") {
                RecruitService.processAutoRecruit(this)
            }
            safelyRunInState("theft") { lawEnforcementProcessor.processTheftIfNeeded() }
            safelyRunInState("lawEnforcement") { lawEnforcementProcessor.processLawEnforcementMonthly() }
            safelyRunInState("completedMissions") { processCompletedMissionsLazy(year, month) }
            safelyRunInState("aiSectOperations") { caveExplorationProcessor.get().processAISectOperations(year, month, this) }
            safelyRunInState("gameOverCheck") { checkGameOverCondition(this) }
            safelyRunInState("scoutExpiry") { processScoutInfoExpiryLazy(year, month, this) }
            safelyRunInState("aiBeastAttacksRemaining") { aiSectBeastAttackProcessor.processRemainingTargets(this) }
            if (month == 12) {
                safelyRunInState("autoBuy") { autoBuyService.executeAutoBuy(year, month, this) }
            }
            safelyRunInState("spiritMineProduction") { cultivationSettlement.processSpiritMineProductionMonthly(this) }
            safelyRunInState("disciplePurchase") { disciplePurchaseService.executePurchase(year, month, this) }
            safelyRunInState("monthlyCultivation") { processMonthlyCultivationAndAuto(this) }
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
internal fun CultivationEventProcessor.processMonthlyCultivationAndAuto(state: MutableGameState) {
        val data = state.gameData
        val tables = state.discipleTables
        val aliveIds = tables.ids.filter { tables.isAlive[it] == 1 }
        if (aliveIds.isEmpty()) return
        // HP/MP 恢复（兜底，已由每旬检查补充）。
        // 使用 recoverHpMpForAllDisciples（非逐弟子循环）— 此方法遍历时 equipmentMap/manualMap
        // 只构建一次，比 N 次 recoverHpMpSingle 调用更高效。
    }
internal fun CultivationEventProcessor.processYearlyEvents(year: Int) {
        // 单事务：所有年变事件原子提交
        // 子服务内部 stateStore.update 通过重入缓冲共享同一副本
        stateStore.update {
            safelyRunInState("yearlyTribute") { vassalService.processYearlyTribute() }
            safelyRunInState("yearlyVassalTribute") { vassalService.processYearlyVassalTribute(year) }
            safelyRunInState("discipleAging") {
                discipleLifecycleProcessor.processDiscipleAging(year)
            }
            safelyRunInState("sectDisciplesAging") {
                caveExplorationProcessor.get().processSectDisciplesAging(year, this)
            }
            safelyRunInState("refreshRecruitList") {
                // 差值判据（非模运算）：老档相位漂移自愈；失败时 lastRecruitYear 不更新，次年自动重试
                if (year - gameData.lastRecruitYear >= CultivationEventProcessor.RECRUIT_REFRESH_INTERVAL_YEARS) {
                    recruitService.refreshRecruitList(year)
                }
            }
            safelyRunInState("autoReject") {
                RecruitService.processAutoReject(this)
            }
            safelyRunInState("merchantRefreshChance") {
                merchantAndRecruitService.giveMerchantRefreshChanceIfDue(year)
            }
            safelyRunInState("yearlyAging") {
                discipleLifecycleProcessor.processYearlyAging(year)
            }
            safelyRunInState("recruitAging") {
                recruitService.ageRecruitList(year)
            }
            safelyRunInState("sectYearlyRecruitment") {
                val processor = caveExplorationProcessor.get()
                runSectRecruitmentIfDue(year) { processor.processSectDisciplesYearlyRecruitment(year, this) }
            }
            safelyRunInState("autoBuy") { autoBuyService.executeAutoBuy(year, 1) }
            safelyRunInState("refreshAcquisition") {
                merchantAndRecruitService.refreshMerchantAcquisition(year, 1)
            }
            // 每 3 年强制刷新所有 AI 宗门交易列表（差值判据与懒刷新统一）
            safelyRunInState("sectTradeRefresh") { diplomacyService.refreshAllSectTrades(year) }
            safelyRunInState("partnerMatching") {
                diplomacyEventProcessor.processCrossSectPartnerMatching(year, 1)
            }
            safelyRunInState("allianceExpiry") {
                diplomacyEventProcessor.checkAllianceExpiry(year)
            }
            safelyRunInState("allianceFavorDrop") {
                diplomacyEventProcessor.checkAllianceFavorDrop()
            }
            safelyRunInState("aiAlliances") { diplomacyEventProcessor.processAIAlliances(year) }
            safelyRunInState("reflectionRelease") {
                discipleLifecycleProcessor.processReflectionRelease(year)
            }
            safelyRunInState("favorDecay") { diplomacyEventProcessor.processFavorDecay(year) }
            // 年度报告 + 驻军轮换
            safelyRunInState("garrisonAndReport") { runGarrisonAndReport(year, this) }
            safelyRunInState("griefExpiry") {
                discipleLifecycleProcessor.processGriefExpiry(year)
            }
            safelyRunInState("ancientSecretRealmSpawn") {
                secretRealmService.processYearlySpawn(year, this)
            }
        }
    }

    /**
     * 年变：驻军轮换 + 年度报告快照（单次原子 update）。
     * 已从 [processYearlyEvents] 内联代码提取，降低函数复杂度。
     */
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
            annualDesertedDisciples = 0,
            annualTheftCount = 0
        )
    }
