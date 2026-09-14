package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.domain.diplomacy.truncateToLimit
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 宗门运营 / 弟子招募与老化处理器（W4-B/B4 收敛后）。
 *
 * 🔴 洞府探索生命周期链已删除（W4-B/B4 死链清理，双证）：
 * 入口 [CultivationService.processCaveLifecycle] 生产零调用 +
 * 主源 `CaveExplorationTeam(` 构造 0 处（仅测试构造）——洞府探索在现版本
 * 无法推进，链路独占面（completion loop/战斗/战利品/战报）一并移除，
 * 其扩展文件 CaveExplorationRewardOps.kt 同批删除。
 *
 * 🔴 同族方法均活，保留（回退臂可达，禁删）：
 * - [processAISectOperations] / [currentAiThermalBatchSize]：
 *   CultivationEventMonthlyOps:62/:95 与 GameEngineCoreMonthOps:77
 * - [processSectDisciplesYearlyRecruitment]：CultivationEventMonthlyOps:180
 * - [processSectDisciplesAging]：CultivationEventMonthlyOps:176（年变 T2 #4）
 */
@Singleton
@GameService("CaveExplorationProcessor")
class CaveExplorationProcessor @Inject constructor(
    internal val stateStore: GameStateStore,
    internal val inventorySystem: InventorySystem,
    internal val spiritStoneWallet: SpiritStoneWallet,
    private val aiSectBattleProcessor: AISectBattleProcessor
) {
    // W4-B/B4 死链清理后构造面收敛：battleSystem/eventProcessor/analyticsTracker/
    // deathHandler 仅被已删除的洞府探索链消费，随链移除（Hilt 注入面同步收窄）。


    /**
     * AI 宗门月度运营（3 参版）——AUTHORITATIVE 回退链子事件 6 的 Kotlin 面
     * （仓库清场 + 热控分批修炼 + 宗门等级同步，纯运营无战斗）。
     * 战斗编排面已入 C++ 月结子事件 6b/6c（P2-18）；2 参版委托随
     * 征伐环下沉删除（全仓唯一调用方为已删的休眠链）。
     */
    fun processAISectOperations(year: Int, month: Int, state: MutableGameState) =
        aiSectBattleProcessor.processAISectOperations(year, month, state)

    /** 当前热档批量上界（AUTHORITATIVE 月结推送 C++ 用） */
    @Suppress("UnusedParameter") // year: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
    internal fun currentAiThermalBatchSize(): Int =
        aiSectBattleProcessor.currentAiThermalBatchSize()

    /**
     * AI 宗门弟子周期性招募结算：为每个非玩家宗门生成一批新弟子并按占领路由分发。
     * 在年变单事务内评估，实际由 runSectRecruitmentIfDue 差值判据每 3 年触发一次
     * （非招募年不调用本函数）；批次数量见 AISectDiscipleManager.generateYearlyRecruits。
     */
    @Suppress("UnusedParameter") // year: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
    fun processSectDisciplesYearlyRecruitment(year: Int, state: MutableGameState) {
        val data = state.gameData
        var updatedAiDisciples = data.aiSectDisciples.toMutableMap()
        var updatedRecruitList = data.recruitList

        for ((sectId, disciples) in data.aiSectDisciples) {
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) continue

            val newRecruits = AISectDiscipleManager.generateYearlyRecruits(
                sect.name, disciples, sect.level
            )
            when {
                sect.isPlayerOccupied -> {
                    updatedRecruitList = updatedRecruitList + newRecruits
                }
                sect.occupierSectId.isNotEmpty() -> {
                    val occupierDisciples = updatedAiDisciples[sect.occupierSectId] ?: emptyList()
                    updatedAiDisciples[sect.occupierSectId] =
                        AISectDiscipleManager.truncateToLimit(occupierDisciples + newRecruits)
                }
                else -> {
                    updatedAiDisciples[sectId] =
                        AISectDiscipleManager.truncateToLimit(disciples + newRecruits)
                }
            }
        }
        // 直接基于事务 buffer 写回：年变单事务内前序事件（如 refreshRecruitList）
        // 对 buffer 的修改必须保留，禁止读已提交快照覆盖（招募列表不刷新 MNG 修复）。
        state.gameData = state.gameData.copy(
            aiSectDisciples = updatedAiDisciples,
            recruitList = updatedRecruitList
        )
        // 被占领AI宗门产生新弟子后立即执行自动招募检查 + 重置惰性（同步 C++ 惰性门）
        RecruitService.resetAutoRecruitIdle()
        RecruitService.RecruitLazyState.autoRejectIdle = false
        RecruitService.processAutoRecruit(state)
    }

    @Suppress("UnusedParameter") // year: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
    fun processSectDisciplesAging(year: Int, state: MutableGameState) {
        val data = state.gameData
        val updatedAiDisciples = data.aiSectDisciples.mapValues { (sectId, disciples) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) return@mapValues disciples
            AISectDiscipleManager.processAging(disciples)
        }
        // 年度老化仅修改年龄，不改变境界，无需同步宗门等级。
        // 基于事务 buffer 写回，保留同事务前序事件对 aiSectDisciples 的修改
        // （禁止读已提交快照覆盖，招募列表不刷新 MNG 修复）。
        state.gameData = state.gameData.copy(aiSectDisciples = updatedAiDisciples)
    }
}
