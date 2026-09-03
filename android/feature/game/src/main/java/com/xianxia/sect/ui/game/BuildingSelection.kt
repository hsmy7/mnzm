package com.xianxia.sect.ui.game

import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.util.DomainLog

/**
 * 建筑选中/详情入口逻辑（自 MainGameScreenGestures.kt 拆分，收敛"建筑选中行为"单一职责）：
 * 详情对话框映射（[buildingDialogType]）、选中判定（[canSelectBuilding]）、
 * 及"进入"/点击共用分发（[openBuildingDetailFor]）。
 */

/** 是否允许点击建筑进入"选中"态（放置/移动/建造栏展开时不允许，避免覆盖层重叠）。 */
internal fun canSelectBuilding(state: MainGameScreenState): Boolean =
    !state.isPlacingBuilding && state.movingBuilding == null && !state.buildingBarExpanded

/**
 * 建筑显示名 → 详情对话框类型（纯函数，可单测）。
 * 返回 null 表示无专用 DialogType，回退到通用建筑点击回调（详见 [openBuildingDetailFor]）。
 */
internal fun buildingDialogType(displayName: String, instanceId: String): DialogType? {
    val key = BuildingFeatureRegistry.findByDisplayName(displayName)?.key ?: return null
    return when (key) {
        "spirit_mine" -> DialogType.SpiritMine(instanceId)
        "alchemy" -> DialogType.Alchemy(instanceId)
        "forge" -> DialogType.Forge(instanceId)
        "patrol_tower" -> DialogType.PatrolTower(instanceId)
        "blood_refining_pool" -> DialogType.BloodRefiningPool(instanceId)
        "warehouse" -> DialogType.WarehouseBuilding(instanceId)
        "single_residence", "single_residence_upgraded",
        "multi_residence", "multi_residence_upgraded" -> DialogType.Residence(instanceId)
        else -> simpleBuildingDialogType(key)
    }
}

/** 无参功能建筑的 DialogType（buildingDialogType 拆分，降低圈复杂度）。 */
private fun simpleBuildingDialogType(key: String): DialogType? = when (key) {
    "herb_garden" -> DialogType.HerbGarden
    "spirit_field" -> DialogType.Planting
    "library" -> DialogType.Library
    "wen_dao_peak" -> DialogType.WenDaoPeak
    "qingyun_peak" -> DialogType.QingyunPeak
    "tianshu_hall" -> DialogType.TianshuHall
    "law_enforcement_hall" -> DialogType.LawEnforcementHall
    "mission_hall" -> DialogType.MissionHall
    "reflection_cliff" -> DialogType.ReflectionCliff
    else -> null
}

/**
 * 打开指定建筑的详情（选中态"进入"按钮共用分发）：优先专用 DialogType，否则回退通用回调。
 * 与旧版 `handleMainGameScreenTap` 的详情分发逻辑等价（R1/B1 诊断保留）。
 */
internal fun openBuildingDetailFor(
    clicked: GridBuildingData,
    def: com.xianxia.sect.core.engine.domain.building.BuildingFeature?,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    viewModel: GameViewModel
) {
    val dialogType = buildingDialogType(clicked.displayName, clicked.instanceId)
    if (dialogType != null) {
        viewModel.navigateToDialog(dialogType)
    } else {
        handleGenericBuildingTap(clicked, def, derived, mapData)
    }
}

/**
 * 无专用 DialogType 的建筑点击兜底：显示名回调分发 + 未注册诊断（R1/B1）。
 */
private fun handleGenericBuildingTap(
    clicked: GridBuildingData,
    def: com.xianxia.sect.core.engine.domain.building.BuildingFeature?,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData
) {
    // R1 诊断（B1）：displayName 未注册 / 无回调 → 点击被静默吞掉。
    if (def == null) {
        DomainLog.w(
            BUILDING_TAP_TAG,
            "点击建筑 displayName 未注册: name=${clicked.displayName} " +
                "sectId=${clicked.sectId} instanceId=${clicked.instanceId} " +
                "grid=(${clicked.gridX},${clicked.gridY}) " +
                "activeSectId=${derived.gameData.activeSectId} " +
                "sectBuildings=${derived.activeSectBuildings.size}"
        )
    }
    val b = mapData.buildingList.find { it.first == clicked.displayName }
    if (b != null) {
        b.second?.invoke(clicked)
    } else {
        DomainLog.w(
            BUILDING_TAP_TAG,
            "点击建筑无回调处理: name=${clicked.displayName} " +
                "sectId=${clicked.sectId} instanceId=${clicked.instanceId} " +
                "grid=(${clicked.gridX},${clicked.gridY}) " +
                "activeSectId=${derived.gameData.activeSectId} " +
                "sectBuildings=${derived.activeSectBuildings.size}"
        )
    }
}
