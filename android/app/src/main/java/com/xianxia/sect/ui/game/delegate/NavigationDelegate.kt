package com.xianxia.sect.ui.game.delegate

import android.util.Log
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.ui.navigation.GameRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class NavigationDelegate(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    private val scope: CoroutineScope,
    private val onNavigate: (GameRoute) -> Unit,
    private val onPopBack: (String?) -> Unit
) {
    companion object {
        private const val TAG = "NavigationDelegate"
    }

    /**
     * 关闭当前对话框 — dialogs call this internally
     */
    fun closeCurrentDialog() {
        onPopBack(null)
    }

    fun closeAllDialogs() {
        onPopBack("empty")
    }

    fun openSpiritMineDialog(mineIndex: Int = 0) {
        onNavigate(GameRoute.SpiritMine)
    }

    fun openHerbGardenDialog() {
        onNavigate(GameRoute.HerbGarden)
    }

    fun openAlchemyDialog(buildingIndex: Int = 0) {
        onNavigate(GameRoute.Alchemy)
    }

    fun openForgeDialog(buildingIndex: Int = 0) {
        onNavigate(GameRoute.Forge)
    }

    fun openLibraryDialog() {
        onNavigate(GameRoute.Library)
    }

    fun openWenDaoPeakDialog() {
        onNavigate(GameRoute.WenDaoPeak)
    }

    fun openQingyunPeakDialog() {
        onNavigate(GameRoute.QingyunPeak)
    }

    fun openTianshuHallDialog() {
        onNavigate(GameRoute.TianshuHall)
    }

    fun openLawEnforcementHallDialog() {
        onNavigate(GameRoute.LawEnforcementHall)
    }

    fun openMissionHallDialog() {
        onNavigate(GameRoute.MissionHall)
    }

    fun openReflectionCliffDialog() {
        onNavigate(GameRoute.ReflectionCliff)
    }

    fun openPatrolTowerDialog() {
        onNavigate(GameRoute.PatrolTower)
    }

    fun openWorldMapDialog() {
        onNavigate(GameRoute.WorldMap)
    }

    fun openRecruitDialog() {
        onNavigate(GameRoute.Recruit)
    }

    fun openMerchantDialog() {
        onNavigate(GameRoute.Merchant)
    }

    fun openDiplomacyDialog() {
        onNavigate(GameRoute.Diplomacy)
    }

    fun attackWorldLevel(levelId: String, discipleIds: List<String?>) {
        scope.launch {
            gameEngine.attackWorldLevel(levelId, discipleIds)
        }
    }

    fun openBattleLogDialog() {
        onNavigate(GameRoute.BattleLog)
    }

    fun dismissBattleResult() {
        gameEngine.clearPendingBattleResult()
    }

    fun openSalaryConfigDialog() {
        onNavigate(GameRoute.SalaryConfig)
    }

    fun openGameOverDialog() {
        scope.launch {
            try {
                gameEngineCore.pause()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pause game engine on game over", e)
            }
            onNavigate(GameRoute.GameOver)
        }
    }
}
