package com.xianxia.sect.ui.game.components.dialog

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.OverlayViewModels
import com.xianxia.sect.ui.game.tabs.DisciplesTab
import com.xianxia.sect.ui.game.tabs.WarehouseTab
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.XianxiaColorScheme

/** 对话框路由共享配色（E1 拆分：OverlayDialogRouter 移至 dialog 组） */
internal val CachedColorScheme = XianxiaColorScheme()

/**
 * C-3：全屏 Tab 对话框脚手架（进入时设置 activeTab，退出时复位 OVERVIEW）。
 * 原 4 处逐字相同的 DisposableEffect 块统一封装。
 */
@Composable
internal fun DialogTabScaffold(
    tab: String,
    viewModel: GameViewModel,
    content: @Composable () -> Unit
) {
    DisposableEffect(Unit) {
        viewModel.setActiveTab(tab)
        onDispose { viewModel.setActiveTab("OVERVIEW") }
    }
    content()
}

@Composable
internal fun DeferredContent(content: @Composable () -> Unit) {
    var showContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showContent = true
    }
    if (showContent) {
        content()
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f - it * 0.15f)
                        .height(16.dp)
                        .background(Color(0x1A000000), RoundedCornerShape(4.dp))
                )
            }
        }
    }
}

@Composable
internal fun FullScreenOverlay(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable (() -> Unit)? = null,
    deferContent: Boolean = true,
    scrimEnabled: Boolean = true,
    /** 窗口级覆盖层槽位（如内联兑换码弹窗），透传给 UnifiedGameDialog */
    overlay: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = title,
        mode = DialogMode.Full,
        showCloseButton = true,
        dismissOnBackPress = true,
        dismissOnClickOutside = false,
        headerActions = actions,
        scrollableContent = false,
        scrimEnabled = scrimEnabled,
        overlay = overlay
    ) {
        if (deferContent) {
            DeferredContent { content() }
        } else {
            content()
        }
    }
}

@Composable
internal fun FullScreenOverlayWarehouse(
    viewModel: GameViewModel,
    onDismiss: () -> Unit,
    warehouseBaseCapacity: Int = GameConfig.Warehouse.BASE_CAPACITY,
    warehouseCapacityPerBuilding: Int = GameConfig.Warehouse.CAPACITY_PER_BUILDING
) {
    val gameData by viewModel.gameDataUi.collectAsStateWithLifecycle()
    val equipmentStacks by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    val manualStacks by viewModel.manualStacks.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()

    var showBulkSell by remember { mutableStateOf(false) }
    val warehouseCount = gameData.placedBuildings.count { it.displayName == "仓库" }
    val maxCap = warehouseBaseCapacity + warehouseCount * warehouseCapacityPerBuilding
    val totalItems = equipmentStacks.size + manualStacks.size + pills.size + materials.size + herbs.size + seeds.size
    val isFull = totalItems >= maxCap
    val titleText = buildString {
        append("仓库 ($totalItems/$maxCap)")
        if (isFull) append(" 仓库已满")
    }
    FullScreenOverlay(
        title = titleText,
        onDismiss = onDismiss,
        scrimEnabled = false,
        actions = {
            GameButton(
                text = "一键出售",
                onClick = { showBulkSell = true }
            )
        }
    ) {
        WarehouseTab(
            viewModel = viewModel,
            showBulkSellDialog = showBulkSell,
            onBulkSellDismiss = { showBulkSell = false },
            onDismiss = onDismiss
        )
    }
}

@Composable
internal fun DisciplesTabContent(viewModel: GameViewModel) {
    val gameData by viewModel.gameDataUi.collectAsStateWithLifecycle()
    val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
    val equipment by viewModel.equipmentInstances.collectAsStateWithLifecycle()
    val manuals by viewModel.manualInstances.collectAsStateWithLifecycle()
    val manualStacks by viewModel.manualStacks.collectAsStateWithLifecycle()
    val equipmentStacks by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    DisciplesTab(
        gameData = gameData,
        disciples = aliveDisciples,
        equipment = equipment,
        manuals = manuals,
        manualStacks = manualStacks,
        equipmentStacks = equipmentStacks,
        viewModel = viewModel
    )
}

@Composable
internal fun GameOverDialog(
    onRestartGame: () -> Unit,
    onReturnToMain: () -> Unit
) {
    BackHandler(enabled = true) { }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "宗门覆灭",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF4444)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "你宗所有领地已被攻占，弟子流离失散，\n宗门就此覆灭于修仙界之中...",
                    fontSize = 14.sp,
                    color = GameColors.DividerGray,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                GameButton(
                    text = "重开游戏",
                    onClick = onRestartGame,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                GameButton(
                    text = "回到主界面",
                    onClick = onReturnToMain,
                    fontSize = 14.sp
                )
            }
        }
    }
}

/** 路由分支通用参数解构（renderXxx 各文件复用） */
internal data class RouteArgs(
    val vms: OverlayViewModels,
    val gameData: com.xianxia.sect.core.model.GameData,
    val onDismiss: () -> Unit
)
