package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.batchUpdateAutoAssignAndGuide
import com.xianxia.sect.core.engine.incrementGuideCounter
import com.xianxia.sect.core.engine.updateGameData

/**
 * 自动分配/委派策略设置委托。
 *
 * 职责：弟子自动分配策略、自动装备/学习/丹药/道侣等设置。
 */
class AutoAssignDelegate(
    private val gameEngine: GameEngine
) {
    /** 设置禁止结为道侣的灵根数集合。 */
    fun setDaoCompanionBannedRootCounts(counts: Set<Int>) {
        gameEngine.launchOnEngine {
            gameEngine.updateGameData { it.copy(daoCompanionBannedRootCounts = counts) }
        }
    }

    /** 设置道侣结成是否需要玩家同意。 */
    fun setDaoCompanionConsentRequired(required: Boolean) {
        gameEngine.launchOnEngine {
            gameEngine.updateGameData { it.copy(daoCompanionConsentRequired = required) }
            if (!required) {
                // 关闭同意模式时清理所有待处理提议，防止旧提议被断章取义地批准
                gameEngine.clearPendingMarriageProposals()
            }
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
        gameEngine.launchOnEngine {
            val gd = gameEngine.gameData.value
            val mineAct = mineFocused && !gd.sectPolicies.autoMineFocused
            val plantAct = plantFocused && !gd.sectPolicies.autoPlantFocused
            val prodOn = alchemyFocused || forgeFocused
            val prodWas = gd.sectPolicies.autoAlchemyFocused || gd.sectPolicies.autoForgeFocused
            val prodAct = prodOn && !prodWas
            val newPolicies = gd.sectPolicies.copy(
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
            )
            gameEngine.batchUpdateAutoAssignAndGuide(
                oldPolicies = gd.sectPolicies,
                newPolicies = newPolicies,
                mineActivated = mineAct,
                plantActivated = plantAct,
                productionActivated = prodAct
            )
        }
    }

    /** 设置突破时自动使用丹药的策略。 */
    fun setBreakthroughAutoPillSettings(focused: Boolean, rootCounts: Set<Int>) {
        gameEngine.launchOnEngine {
            gameEngine.updateGameData {
                it.copy(breakthroughAutoPillFocused = focused, breakthroughAutoPillRootCounts = rootCounts)
            }
        }
    }

    /** 设置自动从仓库装备的策略。 */
    fun setAutoEquipSettings(focused: Boolean, rootCounts: Set<Int>) {
        gameEngine.launchOnEngine {
            gameEngine.updateGameData {
                it.copy(autoEquipFromWarehouseFocused = focused, autoEquipFromWarehouseRootCounts = rootCounts)
            }
        }
    }

    /** 设置自动从仓库学习的策略。 */
    fun setAutoLearnSettings(focused: Boolean, rootCounts: Set<Int>) {
        gameEngine.launchOnEngine {
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
