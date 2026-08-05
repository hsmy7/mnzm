package com.xianxia.sect.ui.game.components.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.OverlayViewModels
import com.xianxia.sect.ui.game.dialogs.LawEnforcementHallDialog
import com.xianxia.sect.ui.game.dialogs.LibraryDialog
import com.xianxia.sect.ui.game.dialogs.MissionHallDialog
import com.xianxia.sect.ui.game.dialogs.QingyunPeakDialog
import com.xianxia.sect.ui.game.dialogs.ReflectionCliffDialog
import com.xianxia.sect.ui.game.dialogs.TianshuHallDialog
import com.xianxia.sect.ui.game.dialogs.WenDaoPeakDialog

/**
 * 功能性建筑对话框路由（E1 拆分：Library/WenDaoPeak/QingyunPeak/TianshuHall/
 * LawEnforcementHall/MissionHall/ReflectionCliff）。分支体行为与拆分前逐字节一致。
 */
@Composable
internal fun DialogType.renderFunctionalBuildingRoutes(
    vms: OverlayViewModels,
    gameData: GameData,
    onDismiss: () -> Unit
) {
    val viewModel = vms.game
    when (this) {
        DialogType.Library -> renderLibrary(viewModel, gameData, vms, onDismiss)
        DialogType.WenDaoPeak -> renderWenDaoPeak(viewModel, gameData, vms, onDismiss)
        DialogType.QingyunPeak -> renderQingyunPeak(viewModel, gameData, vms, onDismiss)
        DialogType.TianshuHall -> renderTianshuHall(viewModel, gameData, vms, onDismiss)
        DialogType.LawEnforcementHall -> renderLawEnforcementHall(viewModel, gameData, vms, onDismiss)
        DialogType.MissionHall -> renderMissionHall(viewModel, gameData, onDismiss)
        DialogType.ReflectionCliff -> renderReflectionCliff(viewModel, gameData, onDismiss)
        else -> Unit
    }
}

@Composable
private fun renderLibrary(
    viewModel: GameViewModel,
    gameData: GameData,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    val manuals by viewModel.manualInstances.collectAsStateWithLifecycle()
    val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
    DeferredContent {
        LibraryDialog(
            manuals = manuals,
            disciples = aliveDisciples,
            gameData = gameData,
            viewModel = viewModel,
            productionViewModel = vms.production,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun renderWenDaoPeak(
    viewModel: GameViewModel,
    gameData: GameData,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
    DeferredContent {
        WenDaoPeakDialog(
            disciples = aliveDisciples,
            gameData = gameData,
            viewModel = viewModel,
            productionViewModel = vms.production,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun renderQingyunPeak(
    viewModel: GameViewModel,
    gameData: GameData,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
    DeferredContent {
        QingyunPeakDialog(
            disciples = aliveDisciples,
            gameData = gameData,
            viewModel = viewModel,
            productionViewModel = vms.production,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun renderTianshuHall(
    viewModel: GameViewModel,
    gameData: GameData,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
    DeferredContent {
        TianshuHallDialog(
            gameData = gameData,
            disciples = aliveDisciples,
            viewModel = viewModel,
            productionViewModel = vms.production,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun renderLawEnforcementHall(
    viewModel: GameViewModel,
    gameData: GameData,
    vms: OverlayViewModels,
    onDismiss: () -> Unit
) {
    val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
    DeferredContent {
        LawEnforcementHallDialog(
            disciples = aliveDisciples,
            gameData = gameData,
            viewModel = viewModel,
            productionViewModel = vms.production,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun renderMissionHall(
    viewModel: GameViewModel,
    gameData: GameData,
    onDismiss: () -> Unit
) {
    val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
    DeferredContent {
        MissionHallDialog(
            gameData = gameData,
            disciples = aliveDisciples,
            viewModel = viewModel,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun renderReflectionCliff(
    viewModel: GameViewModel,
    gameData: GameData,
    onDismiss: () -> Unit
) {
    val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
    DeferredContent {
        ReflectionCliffDialog(
            disciples = aliveDisciples,
            gameData = gameData,
            onDismiss = onDismiss,
            onExpelDisciple = { discipleId -> viewModel.expelDisciple(discipleId) },
            onReleaseDisciple = { discipleId -> viewModel.releaseReflectionDisciple(discipleId) }
        )
    }
}
