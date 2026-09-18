package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.MonthPurchaseLog
import com.xianxia.sect.core.engine.MonthSettlementEnvelope
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.annotation.GameService

/**
 * MonthSettlementResidualExecutor — 月变真相源切换后的 Kotlin 残留执行器
 * （nativeSettleMonth 之后的 Kotlin 侧未下沉扇出 + 平台效应草稿应用）。
 *
 * **R2.4 退化契约**：本执行器是**纯平台效应适配器**（写 lifeEvents 瞬态列 /
 * 发邮件 / gate release）——输入信封的生产来源 = proto eventFeed 的 typed
 * 事件（[com.xianxia.sect.core.engine.buildMonthEnvelopeFromEvents]，零
 * JSON 解析）；执行器源零 JSON 解析由静态守卫固化（ResidualExecutorPurityGuardTest）。
 *
 * 与旬结算残留执行器同模式：
 * C++ 侧 `runMonthSettlement` 执行八步编排中已下沉面（政策扣除/月效/AI 预计算/
 * 炼丹锻造完成结算+自动排班/任务完成/灵田/生育/关卡/
 * 伴侣/血炼/排班忠诚/月衰减 + 十六子事件已下沉 14 件），本执行器承接未下沉
 * 扇出（相对序保持原 Kotlin 月变编排）：
 * （子事件 5 任务完成、子事件 6 洞天 AI、子事件 9 AI 兽战余量均在 C++ 侧执行——
 *   AI 独立 RNG 突破/补全与兽战组装入 C++，
 *   热控批量上界为平台效应经 nativeSetAiThermalBatchSize 推送）
 *
 * 平台效应草稿应用（nativeSettleMonth 信封回传）：
 * - 秘境到期关闭草稿：C++ 状态段已清场，本处重建关闭邮件
 *   （[SecretRealmService.applyExpiryCloseDraft]——背包快照附件 + gate release）
 * - 弟子购买日志草稿：写 [com.xianxia.sect.core.state.DiscipleTables.lifeEvents]
 *   瞬态列（"${age}岁：购买了${itemName}"，与原 Kotlin executePurchase 日志一致）
 *
 * （4a/4b 炼丹/锻造与子事件 5 任务完成均在 C++ 侧执行——Kotlin 残留行不存在。
 * 任务完成消费 MISSION/BATTLE/ENEMY_GEN 分区，抽取序
 * 见 C++ mission_completion.h 文件头核对表。）
 *
 * RNG 契约（切换行为基线登记）：残留执行器在 C++ runMonthSettlement
 * **之后**执行——洞天 AI（子事件 6）的 AI 独立分区与 BATTLE（兽战余量）
 * 不污染主序列。S4 后炼丹/锻造 SYSTEM 抽取（完成结算 roll）已入 C++
 * 步骤 4a/4b（灵田收获 roll 之前）；任务完成 MISSION 抽取已入 C++
 * 子事件 5 位——同为登记过的编排基线。
 *
 * 事务契约：必须在 [com.xianxia.sect.core.state.GameStateStore.update]
 * 事务内调用（与下沉前的旬结算残留执行器一致）；平台效应草稿应用只写
 * 本事务 buffer 内状态（原邮件异步扇出步骤已随在线邮件通道下线移除）。
 */
@GameService("MonthSettlementResidualExecutor")
internal class MonthSettlementResidualExecutor(
    private val eventProcessor: CultivationEventProcessor
) {

    /**
     * 执行月变残留扇出 + 平台效应草稿应用（C++ runMonthSettlement 之后、
     * 反向回导之前）。
     *
     * @param state 可变游戏状态（C++ 结算结果已镜像同步的事务内状态）
     * @param env nativeSettleMonth 信封（policyCosts/秘境/购买日志草稿）
     */
    fun execute(state: MutableGameState, env: MonthSettlementEnvelope) {
        // （子事件 6 洞天 AI / 子事件 9 AI 兽战余量在 C++ 侧执行
        //   ——月变残留扇出缩至 2 项平台/UI 效应：秘境关闭 + 购买日志；
        //   原 4g 邮件月度拉取已随在线邮件通道下线移除）

        // ── 平台效应草稿应用 ──────────────────────────────────────
        // 弟子购买日志写 lifeEvents 瞬态列（协议外字段——Kotlin
        // DiscipleTables 类体属性；与原 executePurchase 日志逐条一致）
        env.purchaseLogs.forEach { log -> appendPurchaseLog(state, log) }
        // 秘境到期关闭邮件 + gate release（sendDirectMail 异步落库，
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
