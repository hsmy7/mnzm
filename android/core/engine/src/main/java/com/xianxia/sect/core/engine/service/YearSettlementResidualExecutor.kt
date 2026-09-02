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
 * - **T1-④ 招募列表刷新**（[RecruitService.refreshRecruitList]——SYSTEM 生成链，
 *   差值判据内部）
 * - **T2-② AI 宗门周期性招募**（差值判据 + [CaveExplorationProcessor.
 *   processSectDisciplesYearlyRecruitment]——AI 独立分区 RNG）
 * - ~~T2-③ 商人收购刷新~~（✅ 批 Y-4b 下沉 C++——SYSTEM 稀有度曲线，
 *   本执行器不再调用）
 * - ~~T2-④ 宗门交易列表刷新~~（✅ 批 Y-4a 下沉 C++——局部种子 RNG
 *   sectId.hashCode()+year，本执行器不再调用）
 *
 * RNG 契约（切换行为基线登记）：C++ 已下沉年变面零 SYSTEM 消耗——残留执行器
 * （T1-④ SYSTEM / T2-③ SYSTEM）消耗序与 Kotlin 原编排基本一致；唯一差异：
 * T1-⑨（C++ 条件性 SYSTEM 偷盗钩子）先于 T1-④ 执行（C++ 主真相源先行），
 * 属年变编排整体入 C++ 的必然行为基线。
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
        val year = state.gameData.gameYear

        // ── T1-③ 死亡链平台效应（事务内：物化/丧亲/死亡档案） ──
        env.agedDeaths.forEach { death -> applyAgedDeathInTransaction(state, death) }
        env.bereavements.forEach { bereavement -> appendBereavementEvent(state, bereavement) }

        // T1-④ 招募刷新已下沉 C++（processRefreshRecruitList——本残留执行器
        // 不再调用 Kotlin refreshRecruitList，防双份生成）
        // T2-② AI 宗门周期性招募（AI 独立分区 RNG；差值判据每 3 年）
        state.runSectRecruitmentIfDue(year) {
            eventProcessor.caveExplorationProcessor.get()
                .processSectDisciplesYearlyRecruitment(year, state)
        }
        // T2-③ 商人收购刷新已下沉 C++（refreshMerchantAcquisition——批 Y-4b：
        // SYSTEM 稀有度曲线，本残留执行器不再调用 Kotlin refreshMerchantAcquisition，
        // 防双份生成）
        // T2-④ 宗门交易列表刷新已下沉 C++（refreshAllSectTrades——批 Y-4a：
        // 局部种子 RNG sectId.hashCode()+year，本残留执行器不再调用 Kotlin
        // refreshAllSectTrades，防双份生成）
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
