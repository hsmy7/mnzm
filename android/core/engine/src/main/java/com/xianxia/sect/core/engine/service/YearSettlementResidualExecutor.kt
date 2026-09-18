package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.AgedDeathDraft
import com.xianxia.sect.core.engine.YearSettlementEnvelope
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.materializeBagItemsToWarehouse

/**
 * YearSettlementResidualExecutor — 年变真相源切换后的 Kotlin 残留执行器
 * （nativeSettleYear 之后的 Kotlin 侧未下沉扇出 + 平台效应）。
 *
 * **R2.4 退化契约**：纯平台效应适配器（袋物品物化回仓库 / lifeEvents 丧亲
 * 事件 / Room DAO 清理 / DeathEvent 分发）——输入信封的生产来源 = proto
 * eventFeed 的 typed 事件（buildYearEnvelopeFromEvents，零 JSON 解析）；
 * 执行器源零 JSON 解析由静态守卫固化（ResidualExecutorPurityGuardTest）。
 *
 * 与月变残留执行器同模式：C++ `runYearSettlement` 执行年变已下沉面
 * （T1 全面子面 + 年报快照 + 年俸 + T2 部分子面），
 * 本执行器承接：
 * - **死亡链平台效应**（C++ 状态面已完成——11 槽镜像/哀悼/解绑/血炼/
 *   装备清/死亡记录/事件/计数；本处补 Kotlin 侧：袋物品物化回仓库（含溢出
 *   邮件）/lifeEvents 丧亲事件/死亡记录档案——事务内 + DAO 清理/DeathEvent——
 *   事务外）
 * - **招募列表刷新**（✅ 已下沉 C++——本执行器不再调用）
 * - ~~AI 宗门周期性招募~~（✅ 已下沉 C++——AI 独立分区 RNG +
 *   占领路由，本执行器不再调用）
 * - ~~商人收购刷新~~（✅ 已下沉 C++——SYSTEM 稀有度曲线，
 *   本执行器不再调用）
 * - ~~宗门交易列表刷新~~（✅ 已下沉 C++——局部种子 RNG
 *   sectId.hashCode()+year，本执行器不再调用）
 *
 * RNG 契约：年变已下沉面全部入 C++——残留执行器
 * 不再消费任何分区 RNG（招募生成 SYSTEM / 收购 SYSTEM 均已
 * 下沉 C++ 侧执行）；本执行器仅剩死亡链平台效应（纯 Kotlin 平台
 * 侧：物化/丧亲/死亡档案——零 RNG）。
 *
 * 事务契约：[execute] 必须在 [com.xianxia.sect.core.state.GameStateStore.update]
 * 事务内调用（物化/丧亲/死亡档案）；[applyPlatformEffects] 在事务外调用
 * （Room DAO 清理 + DeathEvent——与 Kotlin processDiscipleAging 事务外段一致）。
 */
@GameService("YearSettlementResidualExecutor")
internal class YearSettlementResidualExecutor(
    private val eventProcessor: CultivationEventProcessor
) {

    /**
     * 执行年变残留扇出 + 事务内平台效应（C++ runYearSettlement 之后、反向回导之前）。
     *
     * @param state 可变游戏状态（C++ 结算结果已镜像同步的事务内状态）
     * @param env nativeSettleYear 信封（死亡链平台效应草稿）
     */
    fun execute(state: MutableGameState, env: YearSettlementEnvelope) {
        // ── 死亡链平台效应（事务内：物化/丧亲/死亡档案） ──
        env.agedDeaths.forEach { death -> applyAgedDeathInTransaction(death) }
        env.bereavements.forEach { bereavement -> appendBereavementEvent(state, bereavement) }

        // 年变编排扇出已全部下沉 C++（招募刷新 / 交易刷新
        // / 商人收购 / AI 宗门招募）——
        // 本执行器不再调用 Kotlin 对应服务，防双份生成
    }

    /**
     * 年变事务外平台效应（Room 生产槽 DAO 清理 + DeathEvent 分发）。
     * 与 Kotlin processDiscipleAging 事务外段语义一致（DAO 批量清理毫秒级、
     * DeathEvent 无消费方，实害为零）。
     *
     * @param env nativeSettleYear 信封（死亡弟子草稿）
     */
    fun applyPlatformEffects(env: YearSettlementEnvelope) {
        eventProcessor.discipleLifecycleProcessor
            .applyAgedDeathPlatformEffects(env.agedDeaths)
    }

    /** 死亡链事务内平台效应：袋物品物化回仓库（withTrackingSource）+ 死亡记录档案。 */
    private fun applyAgedDeathInTransaction(death: AgedDeathDraft) {
        // 袋物品物化回仓库（玩家保留，溢出自动转邮件——与 Kotlin
        // applyAgedDeath 的 withTrackingSource("disciple_death") 一致）
        if (death.storageBagItems.isNotEmpty()) {
            eventProcessor.inventorySystem.withTrackingSource("disciple_death") {
                eventProcessor.inventorySystem.materializeBagItemsToWarehouse(
                    death.storageBagItems
                )
            }
        }
        // 审计 P2-4：DeathRecord 档案已删除（零消费者纯开销，死亡信息由
        // C++ AUTHORITATIVE 列承载）——死亡事件经上方向镜像即可
    }

    /** 丧亲事件（lifeEvents 瞬态列——与 Kotlin buildBereavementEvent 一致）。 */
    private fun appendBereavementEvent(
        state: MutableGameState,
        bereavement: com.xianxia.sect.core.engine.BereavementDraft
    ) {
        if (!state.discipleTables.ids.contains(bereavement.grievingId)) return
        val event = "${bereavement.grievingAge}岁：因${bereavement.relationship}" +
            "${bereavement.deceasedName}离世陷入悲痛，修炼速度降低50%"
        /** 当前设备的电源管理配置 */
        val current = state.discipleTables.lifeEvents.getOrDefault(
            bereavement.grievingId, emptyList()
        )
        state.discipleTables.lifeEvents[bereavement.grievingId] = current + event
    }
}
