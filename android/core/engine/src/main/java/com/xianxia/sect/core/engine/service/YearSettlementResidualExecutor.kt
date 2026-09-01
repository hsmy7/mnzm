package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.state.MutableGameState

/**
 * YearSettlementResidualExecutor — 年变真相源切换后的 Kotlin 残留执行器
 * （批 Y-switch：nativeSettleYear 之后的 Kotlin 侧未下沉扇出）。
 *
 * 与月变残留执行器同模式：C++ `runYearSettlement` 执行年变已下沉面
 * （T1-①/②/⑤/⑥/⑦/⑧/⑨/⑩ + 年报快照 + 年俸 + T2-①/⑥/⑦/⑨/⑩/⑪），
 * 本执行器承接未下沉扇出（Kotlin 严格相对序）：
 * - T1-③ 弟子老化死亡链（[DiscipleLifecycleProcessor.processDiscipleAging]——
 *   零 RNG，DAO 清理/DeathEvent 平台效应；runBlocking 与 Kotlin 年变 T1
 *   同构）
 * - T1-④ 招募列表刷新（[RecruitService.refreshRecruitList]——SYSTEM 生成链，
 *   差值判据 year-lastRecruitYear>=3 内部）
 * - T2-② AI 宗门周期性招募（差值判据 [CultivationEventMonthlyOps.runSectRecruitmentIfDue]
 *   + [CaveExplorationProcessor.processSectDisciplesYearlyRecruitment]——AI
 *   独立分区 RNG）
 * - T2-③ 商人收购刷新（[MerchantAndRecruitService.refreshMerchantAcquisition]——
 *   SYSTEM 稀有度曲线）
 * - T2-④ 宗门交易列表刷新（[DiplomacyService.refreshAllSectTrades]——局部
 *   种子 RNG sectId.hashCode()+year）
 *
 * RNG 契约（切换行为基线登记）：C++ 已下沉年变面零 SYSTEM 消耗（T1 各件零
 * RNG、T2-⑪ 为 SECRET_REALM 分区）——残留执行器（T1-④ SYSTEM / T2-③
 * SYSTEM）的消耗序与 Kotlin 原编排基本一致；唯一差异：T1-⑨（C++ 条件性
 * SYSTEM 偷盗钩子）先于 T1-④ 执行（C++ 主真相源先行）——SYSTEM 序
 * ⑨→④→③ vs 原序 ④→⑨→③，属年变编排整体入 C++ 的必然行为基线，C++ 侧
 * GTest 黄金序列锁定、残留侧委托式 NativeBackedRng 保证确定性。
 *
 * 事务契约：必须在 [com.xianxia.sect.core.state.GameStateStore.update]
 * 事务内调用（与月变残留执行器一致）。
 */
@GameService("YearSettlementResidualExecutor")
internal class YearSettlementResidualExecutor(
    private val eventProcessor: CultivationEventProcessor
) {

    /**
     * 执行年变残留扇出（C++ runYearSettlement 之后、反向回导之前）。
     *
     * @param state 可变游戏状态（C++ 结算结果已镜像同步的事务内状态）
     */
    fun execute(state: MutableGameState) {
        val year = state.gameData.gameYear

        // T1-③ 弟子老化死亡链（零 RNG；DAO 清理/DeathEvent 平台效应）
        eventProcessor.discipleLifecycleProcessor.processDiscipleAging(year)
        // T1-④ 招募列表刷新（SYSTEM 生成链；差值判据内部自愈）
        eventProcessor.recruitService.refreshRecruitList(year)
        // T2-② AI 宗门周期性招募（AI 独立分区 RNG；差值判据每 3 年）
        state.runSectRecruitmentIfDue(year) {
            eventProcessor.caveExplorationProcessor.get()
                .processSectDisciplesYearlyRecruitment(year, state)
        }
        // T2-③ 商人收购刷新（SYSTEM 稀有度曲线——RarityTimeProgression）
        eventProcessor.merchantAndRecruitService.refreshMerchantAcquisition(year, 1)
        // T2-④ 宗门交易列表刷新（局部种子 RNG——sectId.hashCode()+year）
        eventProcessor.diplomacyService.refreshAllSectTrades(year)
    }
}
