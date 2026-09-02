package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.AgedDeathDraft
import com.xianxia.sect.core.engine.YearSettlementEnvelope
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.state.DeathRecord
import com.xianxia.sect.core.state.MutableGameState

/**
 * YearSettlementResidualExecutor — 年变真相源切换后的 Kotlin 残留执行器
 * （批 Y-switch + Y-3：nativeSettleYear 之后的 Kotlin 侧未下沉扇出 + 平台效应）。
 *
 * 与月变残留执行器同模式：C++ `runYearSettlement` 执行年变已下沉面
 * （T1-①/②/③/④/⑤/⑥/⑦/⑧/⑨/⑩/⑪ + 年报快照 + 年俸 + T2-①/⑥/⑦/⑨/⑩/⑪），
 * 本执行器承接：
 * - **T1-③ 死亡链平台效应**（C++ 状态面已完成——11 槽镜像/哀悼/解绑/血炼/
 *   装备清/死亡记录/事件/计数；本处补 Kotlin 侧：袋物品物化回仓库（含溢出
 *   邮件）/lifeEvents 丧亲事件/死亡记录档案——事务内 + DAO 清理/DeathEvent——
 *   事务外）
 * - **T1-④ 招募列表刷新**（✅ 批 Y-3 下沉 C++——本执行器不再调用）
 * - ~~T2-② AI 宗门周期性招募~~（✅ 批 Y-4c 下沉 C++——AI 独立分区 RNG +
 *   占领路由，本执行器不再调用）
 * - ~~T2-③ 商人收购刷新~~（✅ 批 Y-4b 下沉 C++——SYSTEM 稀有度曲线，
 *   本执行器不再调用）
 * - ~~T2-④ 宗门交易列表刷新~~（✅ 批 Y-4a 下沉 C++——局部种子 RNG
 *   sectId.hashCode()+year，本执行器不再调用）
 *
 * RNG 契约（批 Y-4 收口后更新）：年变已下沉面全部入 C++——残留执行器
 * 不再消费任何分区 RNG（T1-④ 招募生成 SYSTEM / T2-③ 收购 SYSTEM 均已
 * 下沉 C++ 侧执行）；本执行器仅剩 T1-③ 死亡链平台效应（纯 Kotlin 平台
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
     * @param env nativeSettleYear 信封（T1-③ 死亡链平台效应草稿）
     */
    fun execute(state: MutableGameState, env: YearSettlementEnvelope) {
        // ── T1-③ 死亡链平台效应（事务内：物化/丧亲/死亡档案） ──
        env.agedDeaths.forEach { death -> applyAgedDeathInTransaction(state, death) }
        env.bereavements.forEach { bereavement -> appendBereavementEvent(state, bereavement) }

        // 年变编排扇出已全部下沉 C++（T1-④ 招募刷新批 Y-3 / T2-④ 交易刷新
        // 批 Y-4a / T2-③ 商人收购批 Y-4b / T2-② AI 宗门招募批 Y-4c）——
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

    /** T1-③ 事务内平台效应：袋物品物化回仓库（withTrackingSource）+ 死亡记录档案。 */
    private fun applyAgedDeathInTransaction(state: MutableGameState, death: AgedDeathDraft) {
        // D-03：袋物品物化回仓库（玩家保留，溢出自动转邮件——与 Kotlin
        // applyAgedDeath 的 withTrackingSource("disciple_death") 一致）
        if (death.storageBagItems.isNotEmpty()) {
            eventProcessor.inventorySystem.withTrackingSource("disciple_death") {
                eventProcessor.inventorySystem.materializeBagItemsToWarehouse(
                    death.storageBagItems
                )
            }
        }
        // 死亡记录档案（Kotlin _deathRecords 内存列表——弟子已从表移除，
        // 档案保留 id→deathYear 供 UI/存档）
        val id = death.discipleId.toIntOrNull() ?: return
        state.discipleTables.addDeathRecord(
            DeathRecord(
                id = id,
                name = death.name,
                surname = death.surname,
                realm = death.realm,
                realmLayer = death.realmLayer,
                deathAge = death.age,
                deathYear = death.deathYear,
                cause = death.cause
            )
        )
    }

    /** T1-③ 丧亲事件（lifeEvents 瞬态列——与 Kotlin buildBereavementEvent 一致）。 */
    private fun appendBereavementEvent(
        state: MutableGameState,
        bereavement: com.xianxia.sect.core.engine.BereavementDraft
    ) {
        if (!state.discipleTables.ids.contains(bereavement.grievingId)) return
        val event = "${bereavement.grievingAge}岁：因${bereavement.relationship}" +
            "${bereavement.deceasedName}离世陷入悲痛，修炼速度降低50%"
        val current = state.discipleTables.lifeEvents.getOrDefault(
            bereavement.grievingId, emptyList()
        )
        state.discipleTables.lifeEvents[bereavement.grievingId] = current + event
    }
}
