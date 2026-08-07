package com.xianxia.sect.ui.game.components.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.game.components.OverlayCallbacks
import com.xianxia.sect.ui.game.components.OverlayViewModels
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.dialogs.CloudSaveDialog
import com.xianxia.sect.ui.game.dialogs.JadeSymbolDialog
import com.xianxia.sect.ui.game.dialogs.SectLevelDetailDialog
import com.xianxia.sect.ui.game.dialogs.shared.RenameSectDialog
import com.xianxia.sect.ui.components.StandardPromptDialog

/**
 * 系统级对话框路由（E1 拆分：SectLevelDetail/RenameSect/GameOver/
 * BuildingSectLevelRequirement/CloudSave）。分支体行为与拆分前逐字节一致。
 */
@Composable
internal fun DialogType.renderSystemRoutes(
    vms: OverlayViewModels,
    callbacks: OverlayCallbacks,
    gameData: GameData,
    onDismiss: () -> Unit
) {
    val viewModel = vms.game
    when (this) {
        DialogType.SectLevelDetail -> {
            val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
            SectLevelDetailDialog(
                gameData = gameData,
                aliveDisciples = aliveDisciples,
                viewModel = viewModel,
                onDismiss = onDismiss
            )
        }
        DialogType.RenameSect -> {
            val onConfirm = remember(viewModel) {
                { newName: String -> viewModel.renameSect(newName) }
            }
            RenameSectDialog(
                currentName = gameData.sectName,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
                // 内联覆盖层渲染在 GameOverlayHost 层，单例遮罩已由 anyDialogVisible 绘制，
                // 不再自画遮罩避免双重遮罩变暗
                scrimEnabled = false
            )
        }
        DialogType.GameOver -> {
            GameOverDialog(
                onRestartGame = {
                    viewModel.dismissDialog()
                    callbacks.onRestartGame()
                },
                onReturnToMain = {
                    viewModel.dismissDialog()
                    callbacks.onLogout()
                }
            )
        }
        is DialogType.BuildingSectLevelRequirement -> {
            val requiredLevel = BuildingFeatureRegistry.findByDisplayName(this.buildingName)?.requiredSectLevel ?: 0
            val levelName = SectLevel.levelName(requiredLevel)
            StandardPromptDialog(
                onDismissRequest = onDismiss,
                title = "建造限制",
                text = "需升级至${levelName}方可建造",
                confirmLabel = "知道了",
                scrimEnabled = false,
                dismissOnClickOutside = true
            )
        }
        DialogType.CloudSave -> {
            CloudSaveDialog(
                saveLoadViewModel = vms.saveLoad,
                onDismiss = onDismiss
            )
        }
        DialogType.JadeSymbol -> JadeSymbolDialogRoute(viewModel, onDismiss)
        else -> Unit
    }
}

/** 玉符信息对话框路由（拆出保持 renderSystemRoutes ≤60 行）。 */
@Composable
private fun JadeSymbolDialogRoute(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    JadeSymbolDialog(
        viewModel = viewModel,
        onDismiss = onDismiss
    )
}
