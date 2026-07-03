package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.SectLevelClaimResult
import com.xianxia.sect.core.engine.SectLevelUpgradeResult
import com.xianxia.sect.core.engine.claimSectLevelReward
import com.xianxia.sect.core.engine.upgradeSectLevel
import com.xianxia.sect.core.engine.updateGameData
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldSect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 宗门等级/改名/奖励委托。
 *
 * 职责：宗门等级详情、改名、领取每周奖励、手动升级宗门等级。
 */
class SectDelegate(
    private val gameEngine: GameEngine,
    private val scope: CoroutineScope,
    private val onShowSuccess: (String) -> Unit = {},
    private val onShowError: (String) -> Unit = {},
    private val onNavigateToDialog: (com.xianxia.sect.ui.navigation.DialogRoute) -> Unit = {},
    private val onDismissDialog: () -> Unit = {}
) {
    /** 打开宗门等级详情界面 */
    fun navigateToSectLevelDetail() {
        onNavigateToDialog(com.xianxia.sect.ui.navigation.DialogRoute.SectLevelDetail)
    }

    /** 修改宗门名称 */
    fun renameSect(newName: String) {
        scope.launch {
            gameEngine.updateGameData { data: GameData ->
                data.copy(
                    sectName = newName,
                    worldMapSects = data.worldMapSects.map { ws: WorldSect ->
                        if (ws.isPlayerSect) ws.copy(name = newName) else ws
                    }
                )
            }
            onDismissDialog()
            onShowSuccess("宗门已更名为「${newName}」")
        }
    }

    /** 领取宗门等级每周奖励 */
    fun claimSectLevelReward(level: Int) {
        scope.launch {
            val result = gameEngine.claimSectLevelReward(level)
            when (result) {
                is SectLevelClaimResult.Success -> { /* 奖励已入队，由 RewardCardHost 播放 */ }
                is SectLevelClaimResult.AlreadyClaimed ->
                    onShowError("本周已领取过该等级奖励")
                is SectLevelClaimResult.Error ->
                    onShowError(result.message)
            }
        }
    }

    /** 手动升级宗门等级 */
    fun upgradeSectLevel() {
        scope.launch {
            val result = gameEngine.upgradeSectLevel()
            when (result) {
                is SectLevelUpgradeResult.Success ->
                    onShowSuccess("宗门晋升至${SectLevel.levelName(result.newLevel)}!")
                is SectLevelUpgradeResult.AlreadyMaxLevel ->
                    onShowSuccess("已达最高等级")
                is SectLevelUpgradeResult.ConditionsNotMet ->
                    onShowError("条件未满足: ${result.unmetConditions.joinToString("、")}")
                is SectLevelUpgradeResult.Error ->
                    onShowError(result.message)
            }
        }
    }
}
