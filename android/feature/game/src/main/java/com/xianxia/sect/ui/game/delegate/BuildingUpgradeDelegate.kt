package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.currentActiveSectId
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.domain.building.UpgradeResult
import com.xianxia.sect.core.engine.upgradeBuilding
import com.xianxia.sect.core.engine.upgradeBuildings

/**
 * 建筑升级委托（从 [BuildingDelegate] 拆分——类函数数收敛到 TooManyFunctions 阈值内）。
 *
 * 职责：单座住所升级 / 一键升级对话框的行内升级与一键升级。
 */
class BuildingUpgradeDelegate(
    private val gameEngine: GameEngine,
    private val onUpgradeSuccess: (String) -> Unit = {},
    private val onUpgradeError: (String) -> Unit = {}
) {

    /**
     * 单座住所升级（住所弹窗升级按钮）。
     * 成功后提示升级目标名称；失败时逐条列出未满足条件（灵石/宗门等级/空间）。
     */
    fun upgradeResidence(instanceId: String) {
        gameEngine.launchOnEngine {
            when (val result = gameEngine.upgradeBuilding(instanceId)) {
                is UpgradeResult.Failure -> onUpgradeError(result.reasons.joinToString("；"))
                is UpgradeResult.Success -> {
                    val targetName = gameEngine.gameDataSnapshot
                        .placedBuildings.find { it.instanceId == instanceId }?.displayName ?: "中级住所"
                    onUpgradeSuccess("已升级为$targetName")
                }
            }
        }
    }

    /** 一键升级对话框行内「升级」按钮：升级对应建筑 1 座（取空间允许的第一座实例）。 */
    fun upgradeBuildingOne(sourceKey: String) {
        gameEngine.launchOnEngine {
            handleUpgradeResult(gameEngine.upgradeBuildings(gameEngine.currentActiveSectId(), sourceKey, 1), sourceKey)
        }
    }

    /** 一键升级对话框行内「一键升级」按钮：升级对应建筑全部（灵石不足时按可负担数升级）。 */
    fun upgradeBuildingsOfType(sourceKey: String) {
        gameEngine.launchOnEngine {
            handleUpgradeResult(
                gameEngine.upgradeBuildings(gameEngine.currentActiveSectId(), sourceKey, Int.MAX_VALUE),
                sourceKey
            )
        }
    }

    /** 升级结果 → 成功/失败消息。 */
    private fun handleUpgradeResult(result: UpgradeResult, sourceKey: String) {
        val sourceName = BuildingFeatureRegistry.findByKey(sourceKey)?.displayName ?: sourceKey
        when (result) {
            is UpgradeResult.Failure -> onUpgradeError(result.reasons.joinToString("；"))
            is UpgradeResult.Success -> when {
                // 先升级有空间且满足条件的建筑，再弹提示框告知剩余空间不足的建筑
                result.upgradedCount > 0 && result.spaceBlockedCount > 0 -> onUpgradeError(
                    "已升级${result.upgradedCount}座$sourceName；" +
                        "剩余${result.spaceBlockedCount}座因升级后占地扩大、空间不足，未能升级"
                )
                result.upgradedCount > 0 -> onUpgradeSuccess("已升级${result.upgradedCount}座$sourceName")
                result.spaceBlockedCount > 0 ->
                    onUpgradeError("${sourceName}升级后占地扩大，空间不足，无法升级")
                else -> onUpgradeError("升级失败")
            }
        }
    }
}
