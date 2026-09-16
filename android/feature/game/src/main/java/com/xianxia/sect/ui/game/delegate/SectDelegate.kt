package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.SectLevelClaimResult
import com.xianxia.sect.core.engine.SectLevelUpgradeResult
import com.xianxia.sect.core.engine.claimSectLevelReward
import com.xianxia.sect.core.engine.renameSect
import com.xianxia.sect.core.engine.upgradeSectLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 宗门等级/改名/奖励委托。
 *
 * 职责：宗门等级详情、改名、领取每周奖励、手动升级宗门等级。
 */
class SectDelegate(
    private val gameEngine: GameEngine,
    private val onShowSuccess: (String) -> Unit = {},
    private val onShowError: (String) -> Unit = {},
    private val onCapacityWarning: (String) -> Unit = {},
    private val onNavigateToDialog: (com.xianxia.sect.core.domain.dialog.DialogType) -> Unit = {},
    private val onDismissDialog: () -> Unit = {}
) {
    /** 打开宗门等级详情界面 */
    fun navigateToSectLevelDetail() {
        onNavigateToDialog(com.xianxia.sect.core.domain.dialog.DialogType.SectLevelDetail)
    }

    /** 修改宗门名称（写入迁入引擎层 GameEngine.renameSect——§2.79 通道关闭配套） */
    fun renameSect(newName: String) {
        gameEngine.launchOnEngine {
            gameEngine.renameSect(newName)
            withContext(Dispatchers.Main) {
                onDismissDialog()
                onShowSuccess("宗门已更名为「${newName}」")
            }
        }
    }

    /** 领取宗门等级每周奖励 */
    fun claimSectLevelReward(level: Int) {
        gameEngine.launchOnEngine {
            val result = gameEngine.claimSectLevelReward(level)
            withContext(Dispatchers.Main) {
                when (result) {
                    is SectLevelClaimResult.Success -> { /* 奖励已入队，由 RewardCardHost 播放 */ }
                    is SectLevelClaimResult.AlreadyClaimed ->
                        onShowError("本周已领取过该等级奖励")
                    is SectLevelClaimResult.CapacityInsufficient ->
                        onCapacityWarning(result.message)
                    is SectLevelClaimResult.Error ->
                        onShowError(result.message)
                }
            }
        }
    }

    /** 手动升级宗门等级 */
    fun upgradeSectLevel() {
        gameEngine.launchOnEngine {
            val result = gameEngine.upgradeSectLevel()
            withContext(Dispatchers.Main) {
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
}
