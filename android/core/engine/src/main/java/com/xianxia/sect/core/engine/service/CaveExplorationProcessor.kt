package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 宗门运营 / 弟子老化处理器（W4-B/B4 收敛后）。
 *
 * 🔴 洞府探索生命周期链已删除（W4-B/B4 死链清理，双证）：
 * 入口 [CultivationService.processCaveLifecycle] 生产零调用 +
 * 主源 `CaveExplorationTeam(` 构造 0 处（仅测试构造）——洞府探索在现版本
 * 无法推进，链路独占面（completion loop/战斗/战利品/战报）一并移除，
 * 其扩展文件 CaveExplorationRewardOps.kt 同批删除。
 *
 * 🔴 同族方法保留：
 * - [processAISectOperations] / [currentAiThermalBatchSize]：
 *   CultivationEventMonthlyOps 与 GameEngineCoreMonthOps 月结调用
 * - [processSectDisciplesAging]：CultivationEventMonthlyOps 年变 T2 #4
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

    @Suppress("UnusedParameter") // year: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
    fun processSectDisciplesAging(year: Int, state: MutableGameState) {
        val data = state.gameData
        val updatedAiDisciples = data.aiSectDisciples.mapValues { (sectId, disciples) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) return@mapValues disciples
            AISectDiscipleManager.processAging(disciples)
        }
        // AI 弟子年度处理不改变境界，无需同步宗门等级。
        // 基于事务 buffer 写回，保留同事务前序事件对 aiSectDisciples 的修改
        // （禁止读已提交快照覆盖）。
        state.gameData = state.gameData.copy(aiSectDisciples = updatedAiDisciples)
    }
}
