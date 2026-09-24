package com.xianxia.sect.ui.game.dialogs

import com.xianxia.sect.core.model.DiscipleAggregate
import androidx.compose.runtime.Composable
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.ProductionViewModel

@Composable
@Suppress("UnusedParameter") // 面板参数面保持稳定：仓库面板骨架按完整签名接收上下文，内容体当前不消费
fun WarehouseDialog(
    buildingInstanceId: String,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "仓库",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
    }
}
