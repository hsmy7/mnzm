package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.updateGameData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 自动分配/委派策略设置委托。
 *
 * 职责：弟子自动分配策略、自动装备/学习/丹药/道侣等设置。
 */
class AutoAssignDelegate(
    private val gameEngine: GameEngine,
    private val scope: CoroutineScope
) {
    /** 设置禁止结为道侣的灵根数集合。 */
    fun setDaoCompanionBannedRootCounts(counts: Set<Int>) {
        scope.launch {
            gameEngine.updateGameData { it.copy(daoCompanionBannedRootCounts = counts) }
        }
    }

    /** 设置道侣结成是否需要玩家同意。 */
    fun setDaoCompanionConsentRequired(required: Boolean) {
        scope.launch {
            gameEngine.updateGameData { it.copy(daoCompanionConsentRequired = required) }
        }
    }

    /** 批量设置所有自动分配策略（一次写入，原子更新）。 */
    fun setAutoAssignSettings(
        mineFocused: Boolean, mineRootCounts: List<Int>, mineThreshold: Int,
        alchemyFocused: Boolean, alchemyRootCounts: List<Int>, alchemyThreshold: Int,
        forgeFocused: Boolean, forgeRootCounts: List<Int>, forgeThreshold: Int,
        singleResidenceFocused: Boolean = false, singleResidenceRootCounts: List<Int> = emptyList(), singleResidenceThreshold: Int = 1,
        multiResidenceFocused: Boolean = false, multiResidenceRootCounts: List<Int> = emptyList(), multiResidenceThreshold: Int = 1,
        plantFocused: Boolean = false, plantRootCounts: List<Int> = emptyList(), plantThreshold: Int = 1
    ) {
        scope.launch {
            gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(
                autoMineFocused = mineFocused,
                autoMineRootCounts = mineRootCounts,
                autoMineThreshold = mineThreshold,
                autoAlchemyFocused = alchemyFocused,
                autoAlchemyRootCounts = alchemyRootCounts,
                autoAlchemyThreshold = alchemyThreshold,
                autoForgeFocused = forgeFocused,
                autoForgeRootCounts = forgeRootCounts,
                autoForgeThreshold = forgeThreshold,
                autoSingleResidenceFocused = singleResidenceFocused,
                autoSingleResidenceRootCounts = singleResidenceRootCounts,
                autoSingleResidenceThreshold = singleResidenceThreshold,
                autoMultiResidenceFocused = multiResidenceFocused,
                autoMultiResidenceRootCounts = multiResidenceRootCounts,
                autoMultiResidenceThreshold = multiResidenceThreshold,
                autoPlantFocused = plantFocused,
                autoPlantRootCounts = plantRootCounts,
                autoPlantThreshold = plantThreshold
            )) }
        }
    }

    /** 设置突破时自动使用丹药的策略。 */
    fun setBreakthroughAutoPillSettings(focused: Boolean, rootCounts: Set<Int>) {
        scope.launch {
            gameEngine.updateGameData {
                it.copy(breakthroughAutoPillFocused = focused, breakthroughAutoPillRootCounts = rootCounts)
            }
        }
    }

    /** 设置自动从仓库装备的策略。 */
    fun setAutoEquipSettings(focused: Boolean, rootCounts: Set<Int>) {
        scope.launch {
            gameEngine.updateGameData {
                it.copy(autoEquipFromWarehouseFocused = focused, autoEquipFromWarehouseRootCounts = rootCounts)
            }
        }
    }

    /** 设置自动从仓库学习的策略。 */
    fun setAutoLearnSettings(focused: Boolean, rootCounts: Set<Int>) {
        scope.launch {
            gameEngine.updateGameData {
                it.copy(autoLearnFromWarehouseFocused = focused, autoLearnFromWarehouseRootCounts = rootCounts)
            }
        }
    }

    /** 设置招募筛选条件。 */
    fun setAutoRecruitFilter(filter: Set<Int>) {
        // 由 DiscipleDelegate 处理，保留为兼容转发
    }
}
