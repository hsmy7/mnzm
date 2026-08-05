package com.xianxia.sect.ui.game.components.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.OverlayViewModels
import com.xianxia.sect.ui.game.dialogs.AlchemyDialog
import com.xianxia.sect.ui.game.dialogs.BloodRefiningPoolDialog
import com.xianxia.sect.ui.game.dialogs.ForgeDialog
import com.xianxia.sect.ui.game.dialogs.HerbGardenDialog
import com.xianxia.sect.ui.game.dialogs.PatrolTowerDialog
import com.xianxia.sect.ui.game.dialogs.ResidenceDialog
import com.xianxia.sect.ui.game.dialogs.SpiritMineDialog
import com.xianxia.sect.ui.game.dialogs.WarehouseDialog

/**
 * 生产类建筑对话框路由（E1 拆分：SpiritMine/HerbGarden/Alchemy/Forge/PatrolTower/
 * BloodRefiningPool/Residence/WarehouseBuilding）。分支体行为与拆分前逐字节一致。
 */
@Composable
internal fun DialogType.renderProductionRoutes(
    vms: OverlayViewModels,
    gameData: GameData,
    onDismiss: () -> Unit
) {
    val viewModel = vms.game
    when (this) {
        is DialogType.SpiritMine -> renderSpiritMine(this, viewModel, vms, onDismiss)
        DialogType.HerbGarden -> renderHerbGarden(viewModel, gameData, vms, onDismiss)
        is DialogType.Alchemy -> renderAlchemy(this, viewModel, gameData, vms, onDismiss)
        is DialogType.Forge -> renderForge(this, viewModel, gameData, vms, onDismiss)
        is DialogType.PatrolTower -> renderPatrolTower(this, viewModel, gameData, vms, onDismiss)
        is DialogType.BloodRefiningPool -> renderBloodRefiningPool(this, viewModel, gameData, vms, onDismiss)
        is DialogType.Residence -> renderResidence(this, viewModel, gameData, onDismiss)
        is DialogType.WarehouseBuilding -> renderWarehouseBuilding(this, viewModel, gameData, vms, onDismiss)
        else -> Unit
    }
}

@Composable
private fun renderSpiritMine(
    type: DialogType.SpiritMine,
    viewModel: GameViewModel,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    SpiritMineDialog(
        buildingInstanceId = type.buildingInstanceId,
        viewModel = viewModel,
        productionViewModel = vms.production,
        spiritMineViewModel = vms.spiritMine,
        onDismiss = onDismiss
    )
}

@Composable
private fun renderHerbGarden(
    viewModel: GameViewModel,
    gameData: GameData,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
    DeferredContent {
        HerbGardenDialog(
            gameData = gameData,
            disciples = aliveDisciples,
            viewModel = viewModel,
            productionViewModel = vms.production,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun renderAlchemy(
    type: DialogType.Alchemy,
    viewModel: GameViewModel,
    gameData: GameData,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    val alchemySlots by viewModel.alchemySlots.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
    DeferredContent {
        AlchemyDialog(
            buildingInstanceId = type.buildingInstanceId,
            alchemySlots = alchemySlots,
            materials = materials,
            herbs = herbs,
            gameData = gameData,
            disciples = aliveDisciples,
            viewModel = viewModel,
            productionViewModel = vms.production,
            alchemyViewModel = vms.alchemy,
            colors = CachedColorScheme,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun renderForge(
    type: DialogType.Forge,
    viewModel: GameViewModel,
    gameData: GameData,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    val forgeSlots by viewModel.forgeSlots.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
    DeferredContent {
        ForgeDialog(
            buildingInstanceId = type.buildingInstanceId,
            forgeSlots = forgeSlots,
            materials = materials,
            gameData = gameData,
            disciples = aliveDisciples,
            viewModel = viewModel,
            productionViewModel = vms.production,
            forgeViewModel = vms.forge,
            colors = CachedColorScheme,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun renderPatrolTower(
    type: DialogType.PatrolTower,
    viewModel: GameViewModel,
    gameData: GameData,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
    PatrolTowerDialog(
        buildingInstanceId = type.buildingInstanceId,
        viewModel = viewModel,
        patrolTowerViewModel = vms.patrolTower,
        gameData = gameData,
        disciples = disciples,
        onDismiss = onDismiss
    )
}

@Composable
private fun renderBloodRefiningPool(
    type: DialogType.BloodRefiningPool,
    viewModel: GameViewModel,
    gameData: GameData,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    DeferredContent {
        BloodRefiningPoolDialog(
            buildingInstanceId = type.buildingInstanceId,
            viewModel = viewModel,
            bloodRefiningViewModel = vms.bloodRefining,
            gameData = gameData,
            disciples = disciples,
            materials = materials,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun renderResidence(
    type: DialogType.Residence,
    viewModel: GameViewModel,
    gameData: GameData,
    onDismiss: () -> Unit
) {
    if (type.buildingInstanceId.isNotEmpty()) {
        val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
        ResidenceDialog(
            buildingInstanceId = type.buildingInstanceId,
            viewModel = viewModel,
            disciples = disciples,
            gameData = gameData,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun renderWarehouseBuilding(
    type: DialogType.WarehouseBuilding,
    viewModel: GameViewModel,
    gameData: GameData,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    if (type.buildingInstanceId.isNotEmpty()) {
        val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
        WarehouseDialog(
            buildingInstanceId = type.buildingInstanceId,
            gameData = gameData,
            disciples = disciples,
            viewModel = viewModel,
            productionViewModel = vms.production,
            onDismiss = onDismiss
        )
    }
}
