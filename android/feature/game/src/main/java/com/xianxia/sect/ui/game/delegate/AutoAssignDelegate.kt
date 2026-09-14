package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.batchUpdateAutoAssignAndGuide
import com.xianxia.sect.core.engine.setAutoEquipSettings
import com.xianxia.sect.core.engine.setAutoLearnSettings
import com.xianxia.sect.core.engine.setBreakthroughAutoPillSettings
import com.xianxia.sect.core.engine.setDaoCompanionBannedRootCounts
import com.xianxia.sect.core.engine.setDaoCompanionConsentRequired
import com.xianxia.sect.core.engine.setPrisonerSpiritRootFilter

/**
 * 自动分配/委派策略设置委托。
 *
 * 职责：弟子自动分配策略、自动装备/学习/丹药/道侣等设置。
 *
 * 设置项字段（自动装备/学习/突破丹药/道侣/俘虏过滤）经
 * `GameEngineSettingsOps` 域入口写入——AUTHORITATIVE 稳态写者为 C++
 * `settings_patch` 事务，Kotlin 原路径为降级回退臂（batch-23 残余域下沉）。
 * 自动分配策略族（sectPolicies）走独立入口 `batchUpdateAutoAssignAndGuide`
 * （batch-18 已下沉 boundary_tx.h），不属设置项字段面。
 */
class AutoAssignDelegate(
    private val gameEngine: GameEngine
) {
    /** 设置禁止结为道侣的灵根数集合。 */
    fun setDaoCompanionBannedRootCounts(counts: Set<Int>) {
        gameEngine.launchOnEngine { gameEngine.setDaoCompanionBannedRootCounts(counts) }
    }

    /** 设置道侣结成是否需要玩家同意。 */
    fun setDaoCompanionConsentRequired(required: Boolean) {
        gameEngine.launchOnEngine { gameEngine.setDaoCompanionConsentRequired(required) }
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
            gameEngine.setBreakthroughAutoPillSettings(focused, rootCounts)
        }
    }

    /** 设置自动从仓库装备的策略。 */
    fun setAutoEquipSettings(focused: Boolean, rootCounts: Set<Int>) {
        gameEngine.launchOnEngine { gameEngine.setAutoEquipSettings(focused, rootCounts) }
    }

    /** 设置自动从仓库学习的策略。 */
    fun setAutoLearnSettings(focused: Boolean, rootCounts: Set<Int>) {
        gameEngine.launchOnEngine { gameEngine.setAutoLearnSettings(focused, rootCounts) }
    }

    /** 设置俘虏灵根过滤（勾选/取消即保存）。 */
    fun setPrisonerSpiritRootFilter(filter: Set<Int>) {
        gameEngine.launchOnEngine { gameEngine.setPrisonerSpiritRootFilter(filter) }
    }

    /** 切换单个灵根数过滤状态（勾选即保存）。 */
    fun togglePrisonerFilter(rootCount: Int) {
        gameEngine.launchOnEngine {
            val current = gameEngine.gameData.value.prisonerSpiritRootFilter
            val updated = if (rootCount in current) current - rootCount else current + rootCount
            gameEngine.setPrisonerSpiritRootFilter(updated)
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

