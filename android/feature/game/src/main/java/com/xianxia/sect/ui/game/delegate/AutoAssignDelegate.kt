package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.batchUpdateAutoAssignAndGuide
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
        mine: AutoAssignSpec,
        alchemy: AutoAssignSpec,
        forge: AutoAssignSpec,
        singleResidence: AutoAssignSpec = AutoAssignSpec(),
        multiResidence: AutoAssignSpec = AutoAssignSpec(),
        plant: AutoAssignSpec = AutoAssignSpec()
    ) {
        gameEngine.launchOnEngine {
            val gd = gameEngine.gameData.value
            val mineAct = mine.focused && !gd.sectPolicies.autoMineFocused
            val plantAct = plant.focused && !gd.sectPolicies.autoPlantFocused
            val prodOn = alchemy.focused || forge.focused
            val prodWas = gd.sectPolicies.autoAlchemyFocused || gd.sectPolicies.autoForgeFocused
            val prodAct = prodOn && !prodWas
            val newPolicies = gd.sectPolicies.copy(
                autoMineFocused = mine.focused,
                autoMineRootCounts = mine.rootCounts,
                autoMineThreshold = mine.threshold,
                autoAlchemyFocused = alchemy.focused,
                autoAlchemyRootCounts = alchemy.rootCounts,
                autoAlchemyThreshold = alchemy.threshold,
                autoForgeFocused = forge.focused,
                autoForgeRootCounts = forge.rootCounts,
                autoForgeThreshold = forge.threshold,
                autoSingleResidenceFocused = singleResidence.focused,
                autoSingleResidenceRootCounts = singleResidence.rootCounts,
                autoSingleResidenceThreshold = singleResidence.threshold,
                autoMultiResidenceFocused = multiResidence.focused,
                autoMultiResidenceRootCounts = multiResidence.rootCounts,
                autoMultiResidenceThreshold = multiResidence.threshold,
                autoPlantFocused = plant.focused,
                autoPlantRootCounts = plant.rootCounts,
                autoPlantThreshold = plant.threshold
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

    /** 设置俘虏灵根过滤（勾选/取消即保存）。 */
    fun setPrisonerSpiritRootFilter(filter: Set<Int>) {
        gameEngine.launchOnEngine {
            gameEngine.updateGameData { it.copy(prisonerSpiritRootFilter = filter) }
        }
    }

    /** 切换单个灵根数过滤状态（勾选即保存）。 */
    fun togglePrisonerFilter(rootCount: Int) {
        gameEngine.launchOnEngine {
            val current = gameEngine.gameData.value.prisonerSpiritRootFilter
            val updated = if (rootCount in current) current - rootCount else current + rootCount
            gameEngine.updateGameData { it.copy(prisonerSpiritRootFilter = updated) }
        }
    }
}


/** 自动分配策略规格（一组"关注开关 + 灵根数白名单 + 悟性阈值"）：
 * 灵矿/炼丹/炼器/单人住所/多人住所/种植六域共用，替代 18 参透传。 */
data class AutoAssignSpec(
    val focused: Boolean = false,
    val rootCounts: List<Int> = emptyList(),
    val threshold: Int = 1
)
