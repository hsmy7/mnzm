package com.xianxia.sect.ui.game.components

import androidx.compose.runtime.Composable

import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.game.components.dialog.renderFeatureRoutes
import com.xianxia.sect.ui.game.components.dialog.renderFunctionalBuildingRoutes
import com.xianxia.sect.ui.game.components.dialog.renderMainTabRoutes
import com.xianxia.sect.ui.game.components.dialog.renderProductionRoutes
import com.xianxia.sect.ui.game.components.dialog.renderSystemRoutes

/**
 * DialogType 34 分支路由（E1 拆分：分支体按域提取至 components/dialog/ 5 个组文件，
 * 本文件保留单处穷尽分派，when 无 else 分支）。
 *
 * 仅在 Dialog 可见时由 GameOverlayHost 调用（key(currentDialogType) 外包裹）。
 */
@Composable
internal fun OverlayDialogRoute(
    type: DialogType,
    vms: OverlayViewModels,
    callbacks: OverlayCallbacks,
    gameData: GameData,
    onDismiss: () -> Unit
) {
    when (type) {
        is DialogType.None -> Unit
        // 主 Tab（Disciples/Warehouse/Settings/Buildings）
        is DialogType.Disciples, is DialogType.Warehouse,
        is DialogType.Settings, is DialogType.Buildings -> {
            type.renderMainTabRoutes(vms, callbacks, onDismiss)
        }
        // 玩法功能（Recruit/Guide/Diplomacy/Planting/Merchant/WorldMap/BattleLog/Mail/Lizhan/Leaderboard）
        is DialogType.Recruit, is DialogType.Guide, is DialogType.Diplomacy,
        is DialogType.Planting, is DialogType.Merchant, is DialogType.WorldMap,
        is DialogType.BattleLog, is DialogType.Mail,
        is DialogType.Lizhan, is DialogType.Leaderboard -> {
            type.renderFeatureRoutes(vms, gameData, onDismiss)
        }
        // 生产建筑（SpiritMine/HerbGarden/Alchemy/Forge/PatrolTower/BloodRefiningPool/Residence/WarehouseBuilding）
        is DialogType.SpiritMine, is DialogType.HerbGarden, is DialogType.Alchemy,
        is DialogType.Forge, is DialogType.PatrolTower, is DialogType.BloodRefiningPool,
        is DialogType.Residence, is DialogType.WarehouseBuilding -> {
            type.renderProductionRoutes(vms, gameData, onDismiss)
        }
        // 功能性建筑（Library/WenDaoPeak/QingyunPeak/TianshuHall/LawEnforcementHall/MissionHall/ReflectionCliff）
        is DialogType.Library, is DialogType.WenDaoPeak, is DialogType.QingyunPeak,
        is DialogType.TianshuHall, is DialogType.LawEnforcementHall,
        is DialogType.MissionHall, is DialogType.ReflectionCliff -> {
            type.renderFunctionalBuildingRoutes(vms, gameData, onDismiss)
        }
        // 系统级（SectLevelDetail/RenameSect/GameOver/BuildingSectLevelRequirement/CloudSave/JadeSymbol/JadeSymbolAd）
        is DialogType.SectLevelDetail, is DialogType.RenameSect, is DialogType.GameOver,
        is DialogType.BuildingSectLevelRequirement, is DialogType.CloudSave,
        is DialogType.JadeSymbol, is DialogType.JadeSymbolAd -> {
            type.renderSystemRoutes(vms, callbacks, gameData, onDismiss)
        }
    }
}
