package com.xianxia.sect.ui.game.delegate

import android.util.Log
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.ui.navigation.GameRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.xianxia.sect.core.engine.pause

class NavigationDelegate(
    /** internal：同包战斗域扩展（NavigationDelegateBattleOps）消费——TMF 收敛外移 */
    internal val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    private val onNavigate: (GameRoute) -> Unit
) {
    companion object {
        private const val TAG = "NavigationDelegate"
    }

    @Suppress("UnusedParameter") // mineIndex: 导航门面语义形参：路由当前不区分实例，保留调用点语义
    fun openSpiritMineDialog(mineIndex: Int = 0) {
        onNavigate(GameRoute.SpiritMine)
    }

    @Suppress("UnusedParameter") // buildingIndex: 导航门面语义形参：路由当前不区分实例，保留调用点语义
    fun openHerbGardenDialog() {
        onNavigate(GameRoute.HerbGarden)
    }

    @Suppress("UnusedParameter") // buildingIndex: 导航门面语义形参：路由当前不区分实例，保留调用点语义
    fun openAlchemyDialog(buildingIndex: Int = 0) {
        onNavigate(GameRoute.Alchemy)
    }

    @Suppress("UnusedParameter") // buildingIndex: 导航门面语义形参：路由当前不区分实例，保留调用点语义
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

    @Suppress("UnusedParameter") // buildingInstanceId: 导航门面语义形参：路由当前不区分实例，保留调用点语义
    fun openReflectionCliffDialog() {
        onNavigate(GameRoute.ReflectionCliff)
    }

    @Suppress("UnusedParameter") // buildingInstanceId: 导航门面语义形参：路由当前不区分实例，保留调用点语义
    fun openPatrolTowerDialog(buildingInstanceId: String = "") {
        onNavigate(GameRoute.PatrolTower)
    }

    @Suppress("UnusedParameter") // buildingInstanceId: 导航门面语义形参：路由当前不区分实例，保留调用点语义
    fun openBloodRefiningPoolDialog(buildingInstanceId: String = "") {
        onNavigate(GameRoute.BloodRefiningPool)
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

    fun openBattleLogDialog() {
        onNavigate(GameRoute.BattleLog)
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun openGameOverDialog() {
        gameEngine.launchOnEngine {
            try {
                gameEngineCore.pause()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pause game engine on game over", e)
            }
            withContext(Dispatchers.Main) {
                onNavigate(GameRoute.GameOver)
            }
        }
    }
}
