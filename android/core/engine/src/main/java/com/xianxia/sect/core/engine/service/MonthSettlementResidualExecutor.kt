package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.MonthPurchaseLog
import com.xianxia.sect.core.engine.MonthSettlementEnvelope
import com.xianxia.sect.core.engine.system.MailSystem
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.building.AlchemySystem
import com.xianxia.sect.core.engine.system.building.ForgeSystem
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.annotation.GameService

/**
 * MonthSettlementResidualExecutor — 月变真相源切换后的 Kotlin 残留执行器
 * （批 M-1：nativeSettleMonth 之后的 Kotlin 侧未下沉扇出 + 平台效应草稿应用）。
 *
 * 与旬结算的 [PhaseSettlementExecutor.executeResidual] 同模式：C++ 侧
 * `runMonthSettlement` 执行八步编排中已下沉面（政策扣除/月效/AI 预计算/
 * 灵田/生育/关卡/伴侣/血炼/排班忠诚/月衰减 + 十六子事件已下沉 13 件），
 * 本执行器承接未下沉扇出（相对序保持原 Kotlin 月变编排）：
 * - 步骤 4a/4b：炼丹/锻造（[AlchemySystem] 同步完成结算 + 异步自动排班、
 *   [ForgeSystem] 异步自动锻造——Room 仓储域边界）
 * - 步骤 4g：邮件月度拉取（[MailSystem] 异步网络，事务内零状态效果）
 * - 子事件 5：任务完成（战斗已路由 C++，奖励/状态写入留 Kotlin）
 * - 子事件 6：洞天 AI 操作（修炼/等级同步——AI 独立 RNG + BATTLE 分区）
 * - 子事件 9：AI 兽战余量（战斗已路由 C++，后处理留 Kotlin）
 *
 * 平台效应草稿应用（nativeSettleMonth 信封回传）：
 * - S-17 秘境到期关闭草稿：C++ 状态段已清场，本处重建关闭邮件
 *   （[SecretRealmService.applyExpiryCloseDraft]——背包快照附件 + gate release）
 * - S-20 弟子购买日志草稿：写 [com.xianxia.sect.core.state.DiscipleTables.lifeEvents]
 *   瞬态列（"${age}岁：购买了${itemName}"，与原 Kotlin executePurchase 日志一致）
 *
 * RNG 契约（切换行为基线登记）：残留执行器在 C++ runMonthSettlement
 * **之后**执行——SYSTEM（生产结算）/MISSION（任务完成）消耗序与切换前
 * Kotlin 编排（生产结算在步骤 4a、任务完成在子事件 5）不同，属"月变编排
 * 整体入 C++"的行为基线变化，C++ 侧确定性由 GTest 黄金序列锁定，残留侧
 * 由委托式 NativeBackedRng 单一真相源保证；BATTLE（洞天 decideAttacks/
 * 战斗）与 AI 独立分区（AISectDiscipleManager）不污染主序列。
 *
 * 事务契约：必须在 [com.xianxia.sect.core.state.GameStateStore.update]
 * 事务内调用（与旬 executeResidual 一致）；异步扇出（自动排班/邮件）经
 * 各系统 scope launch 独立事务，不在本事务 buffer 内产生状态效果。
 */
@GameService("MonthSettlementResidualExecutor")
internal class MonthSettlementResidualExecutor(
    private val eventProcessor: CultivationEventProcessor,
    private val systemManager: SystemManager
) {

    /**
     * 执行月变残留扇出 + 平台效应草稿应用（C++ runMonthSettlement 之后、
     * 反向回导之前）。
     *
     * @param state 可变游戏状态（C++ 结算结果已镜像同步的事务内状态）
     * @param env nativeSettleMonth 信封（policyCosts/S-17/S-20 草稿）
     */
    fun execute(state: MutableGameState, env: MonthSettlementEnvelope) {
        val year = state.gameData.gameYear
        val month = state.gameData.gameMonth

        // 4a：炼丹（同步完成结算 SYSTEM roll + 异步自动排班 launch）
        systemManager.getSystem(AlchemySystem::class).onMonthlyEvent(state)
        // 4b：锻造（异步自动排班 launch——完成结算统一由 AlchemySystem 触发）
        systemManager.getSystem(ForgeSystem::class).onMonthlyEvent(state)
        // 4g：邮件月度拉取（异步网络——与原 MailSystem.onMonthlyEvent 一致）
        systemManager.getSystem(MailSystem::class).onMonthlyEvent(state)
        // 子事件 5：任务完成（战斗已路由 C++ BattleExecutionRouter——批 13-9；
        // 奖励收集 Phase 1 读已提交快照（镜像=C++ 结算后）→ 单事务写入）
        eventProcessor.processCompletedMissionsLazy(year, month)
        // 子事件 6：洞天 AI 操作（修炼/等级同步——与原月变调用点同一 state 版，
        // 不触发事务外 AIVsAI 战斗——与原月变编排行为一致）
        eventProcessor.caveExplorationProcessor.get()
            .processAISectOperations(year, month, state)
        // 子事件 9：AI 兽战余量（消费 aiSectBeastDirectTargets——批 13-1 预计算
        // 结果；战斗已路由 C++——批 13-8；后处理死亡/击败标记零 RNG）
        eventProcessor.aiSectBeastAttackProcessor.processRemainingTargets(state)

        // ── 平台效应草稿应用 ──────────────────────────────────────
        // S-20：弟子购买日志写 lifeEvents 瞬态列（协议外字段——Kotlin
        // DiscipleTables 类体属性；与原 executePurchase 日志逐条一致）
        env.purchaseLogs.forEach { log -> appendPurchaseLog(state, log) }
        // S-17：秘境到期关闭邮件 + gate release（sendDirectMail 异步落库，
        // 事务内安全——与 closeSecretRealmByExpiry 的邮件/gate 段一致）
        env.secretRealmClose?.let { close ->
            eventProcessor.secretRealmService.applyExpiryCloseDraft(
                slotId = state.gameData.currentSlot,
                backpack = close.backpack,
                memberIds = close.memberIds.toSet()
            )
        }
    }

    /** S-20：购买日志追加（"${age}岁：购买了${itemName}"，与 Kotlin 原路径一致）。 */
    private fun appendPurchaseLog(state: MutableGameState, log: MonthPurchaseLog) {
        val discipleId = log.discipleId.toIntOrNull() ?: return
        if (state.discipleTables.ids.contains(discipleId)) {
            val current = state.discipleTables.lifeEvents.getOrDefault(discipleId, emptyList())
            state.discipleTables.lifeEvents[discipleId] = current + "${log.age}岁：购买了${log.itemName}"
        }
    }
}
