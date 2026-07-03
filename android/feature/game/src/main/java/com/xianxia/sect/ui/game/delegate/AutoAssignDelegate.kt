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

    /** 批量设置各生产建筑的自动分配策略。 */
    fun setAutoAssignSettings(
        mineFocused: Boolean, mineRootCounts: List<Int>, mineThreshold: Int,
        plantFocused: Boolean, plantRootCounts: List<Int>, plantThreshold: Int,
        alchemyFocused: Boolean, alchemyRootCounts: List<Int>, alchemyThreshold: Int,
        forgeFocused: Boolean, forgeRootCounts: List<Int>, forgeThreshold: Int
    ) {
        scope.launch {
            gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(
                autoMineFocused = mineFocused,
                autoMineRootCounts = mineRootCounts,
                autoMineThreshold = mineThreshold,
                autoPlantFocused = plantFocused,
                autoPlantRootCounts = plantRootCounts,
                autoPlantThreshold = plantThreshold,
                autoAlchemyFocused = alchemyFocused,
                autoAlchemyRootCounts = alchemyRootCounts,
                autoAlchemyThreshold = alchemyThreshold,
                autoForgeFocused = forgeFocused,
                autoForgeRootCounts = forgeRootCounts,
                autoForgeThreshold = forgeThreshold
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
