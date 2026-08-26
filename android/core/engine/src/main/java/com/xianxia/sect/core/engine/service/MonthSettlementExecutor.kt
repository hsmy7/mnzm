package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.processBloodRefinementCompletions
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.state.MutableGameState

/**
 * MonthSettlementExecutor — 月变结算纯编排器（计划 v2 阶段 2 / T2.2）。
 *
 * 从 [com.xianxia.sect.core.GameEngineCore.processMonthYearChange] 的 monthChanged
 * 分支原样提取的编排逻辑：生产 tick 与跨语言对拍测试共用同一入口
 * （God Method 拆分 + 对拍基准双重需要）。
 *
 * 八步事务顺序（与 C++ `gamecore::system::runMonthSettlement` 逐位对应；
 * 语义权威 = 各被调方法源码，RNG 分区调用点表见 .superpowers/sdd/t2-2-semantics.md）：
 * 1. 政策月度灵石扣除（不足自动关闭政策，结果返回给调用方做事务外决策）
 * 2. 政策月度忠诚/道德效果（含教化之道低道德偷盗判定钩子）
 * 3. AI 兽袭进攻目标预计算（写入 aiSectBeastDirectTargets，EXPLORATION 分区）
 * 4. systemManager 七系统月变扇出（@SystemPriority 升序：
 *    Alchemy→Forge→Planting→ChildBirth→Exploration→Partner→Mail）
 * 5. 血炼完成检测（到期逐条结算，零 RNG）
 * 6. 月度自动排班 + 住所忠诚度（P0.2 合入同一事务）
 * 7. 丹药持续效果全量月衰减（每月 3 旬口径，2026-08 接回语义）
 * 8. processMonthlyEventsOnState 十六子事件（★ 单原子提交 policy + 月变）
 *
 * 行为契约：与提取前的 monthChanged 分支逐行等价，生产行为零变化。
 * 事务外三件（SomeDisabled → checkpointAllProduction / missionCheck 回调 /
 * spiritStoneWallet.flushPendingEvents）保留在调用方，不入本执行器。
 */
@GameService("MonthSettlementExecutor")
internal class MonthSettlementExecutor(
    private val cultivationService: CultivationService,
    private val aiSectBeastAttackProcessor: AISectBeastAttackProcessor,
    private val systemManager: SystemManager
) {

    /**
     * 执行一次月变结算（八步单事务编排）。
     *
     * 必须在 [com.xianxia.sect.core.state.GameStateStore.update] 事务内调用
     * （与生产 tick 路径一致）。
     *
     * @param state 可变游戏状态（事务内就地修改）
     * @return 政策费用结果——事务外决定是否重算生产 checkpoints
     */
    fun execute(state: MutableGameState): PolicyCostResult {
        // 1) 政策月度灵石扣除（策略成本结果需要在事务外检查以决定是否重算生产 checkpoints）
        val policyResult = cultivationService.processPolicyCosts(state)
        // 2) 政策月度非消耗效果（道德/忠诚增减等，与扣费同事务）
        cultivationService.processPolicyMonthlyEffects(state)
        // 3) AI 预计算进攻目标（写入 aiSectBeastDirectTargets），巡视楼处理时会查看
        aiSectBeastAttackProcessor.precomputeTargets(
            state, state.gameData.gameYear, state.gameData.gameMonth
        )
        // 4) 系统月变扇出
        systemManager.onMonthlyEvent(state)
        // 5) 血炼完成检测
        state.processBloodRefinementCompletions()
        // 6) P0.2: 自动排班 + 住所忠诚度合入同一事务，减少月度独立事务数量
        cultivationService.processMonthlyAutoAssignments(state)
        // 7) 月结丹药持续效果衰减（2026-08 修复：接回后 duration 按每月 3 旬衰减）
        cultivationService.applyMonthlyDurationDecayAll(state)
        // 8) ★ 月度事件合并到同一事务（单原子提交 policy + 月变 + 重算 checkpoints）
        cultivationService.processMonthlyEventsOnState(state)
        return policyResult
    }
}
