package com.xianxia.sect.ui.game.components.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.ui.game.components.OverlayCallbacks
import com.xianxia.sect.ui.game.components.OverlayViewModels
import com.xianxia.sect.ui.game.tabs.BuildingsTab
import com.xianxia.sect.ui.game.tabs.RedeemCodeDialog
import com.xianxia.sect.ui.game.tabs.SettingsTab

/**
 * 主 Tab 类对话框路由（E1 拆分：Disciples/Warehouse/Settings/Buildings）。
 * 分支体行为与拆分前逐字节一致。
 */
@Composable
internal fun DialogType.renderMainTabRoutes(
    vms: OverlayViewModels,
    callbacks: OverlayCallbacks,
    onDismiss: () -> Unit
) {
    val viewModel = vms.game
    when (this) {
        DialogType.Disciples -> {
            // C-3：脚手架统一（DialogTabScaffold 封装 setActiveTab/复位）
            DialogTabScaffold(tab = "DISCIPLES", viewModel = viewModel) {
                FullScreenOverlay(title = "弟子", onDismiss = onDismiss, scrimEnabled = false) {
                    DisciplesTabContent(viewModel = viewModel)
                }
            }
        }
        DialogType.Warehouse -> {
            DialogTabScaffold(tab = "WAREHOUSE", viewModel = viewModel) {
                FullScreenOverlayWarehouse(viewModel = viewModel, onDismiss = onDismiss)
            }
        }
        DialogType.Settings -> {
            // 兑换码弹窗走窗口级 overlay 槽位（非 SettingsTab 内容区内联渲染）：
            // 内容区被 32dp 左右 padding + header 约束，内联遮罩无法全屏覆盖
            val showRedeem by viewModel.showRedeemCodeDialog.collectAsStateWithLifecycle()
            DialogTabScaffold(tab = "SETTINGS", viewModel = viewModel) {
                FullScreenOverlay(
                    title = "设置", onDismiss = onDismiss, scrimEnabled = false, deferContent = false,
                    overlay = {
                        if (showRedeem) {
                            RedeemCodeDialog(
                                viewModel = viewModel,
                                onDismiss = { viewModel.closeRedeemCodeDialog() }
                            )
                        }
                    }
                ) {
                    SettingsTab(
                        viewModel = viewModel,
                        saveLoadViewModel = vms.saveLoad,
                        onLogout = callbacks.onLogout,
                        onDismiss = onDismiss
                    )
                }
            }
        }
        DialogType.Buildings -> {
            DialogTabScaffold(tab = "BUILDINGS", viewModel = viewModel) {
                FullScreenOverlay(
                    title = "建造", onDismiss = onDismiss, scrimEnabled = false, deferContent = false
                ) {
                    BuildingsTab(
                        viewModel = viewModel,
                        productionViewModel = vms.production,
                        alchemyViewModel = vms.alchemy,
                        forgeViewModel = vms.forge,
                        herbGardenViewModel = vms.herbGarden,
                        spiritMineViewModel = vms.spiritMine,
                        onDismiss = onDismiss
                    )
                }
            }
        }
        else -> Unit
    }
}
