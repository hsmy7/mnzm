package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.domain.building.BuildingUpgradeDef
import com.xianxia.sect.core.engine.domain.building.BuildingUpgradeRegistry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.theme.ButtonSizes

/** 一键升级列表行间距（等距排布）。 */
private val UpgradeColumnGap = 8.dp

/** 表头与数据行之间的 1dp 灰色分隔线颜色。 */
private val UpgradeHeaderDividerColor = Color(0xFF9E9E9E)

/**
 * 一键升级半屏对话框：四列列表（建筑/数量/升级/一键升级）。
 *
 * 布局约定（2026-08-19 真机反馈）：标题行四列等距排布（建筑/数量等宽列 +
 * 升级/一键升级标准宽度列），数据行与标题行共用同一列模板——每列数据对准
 * 对应标题正下方；按钮保持 GameButton 标准宽度不变。
 *
 * 数据行由 [buildUpgradeRows] 派生（仅列出玩家已建造、有升级目标的源建筑），
 * 升级操作经 [GameViewModel] 走引擎门面；条件不满足时由统一错误提示框逐条告知。
 */
@Composable
fun BuildingUpgradeDialog(
    viewModel: GameViewModel,
    gameData: GameData,
    onDismiss: () -> Unit
) {
    val rows = buildUpgradeRows(gameData)
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "一键升级",
        mode = DialogMode.Half,
        scrollableContent = true
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp)) {
            // 表头行：建筑 / 数量 / 升级 / 一键升级（与数据行共用列模板，保证列对齐）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UpgradeColumnGap)
            ) {
                UpgradeHeaderCell(text = "建筑", modifier = Modifier.weight(1f))
                UpgradeHeaderCell(text = "数量", modifier = Modifier.weight(1f))
                UpgradeHeaderCell(text = "升级", modifier = Modifier.width(ButtonSizes.StandardWidth))
                UpgradeHeaderCell(text = "一键升级", modifier = Modifier.width(ButtonSizes.StandardWidth))
            }
            // 1dp 灰色横线分隔表头与数据行
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(UpgradeHeaderDividerColor)
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (rows.isEmpty()) {
                Text(
                    text = "暂无可以升级的建筑",
                    fontSize = 13.sp,
                    color = Color.Black,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                rows.forEach { row ->
                    BuildingUpgradeRowItem(
                        row = row,
                        onUpgradeOne = { viewModel.upgradeBuildingOne(row.def.sourceKey) },
                        onUpgradeAll = { viewModel.upgradeBuildingsOfType(row.def.sourceKey) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

/** 表头单元格（BuildingUpgradeDialog 拆分）：居中标题文本。 */
@Composable
private fun UpgradeHeaderCell(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 单行升级数据（BuildingUpgradeDialog 拆分）：名称/数量/两个按钮，列模板与表头一致。 */
@Composable
private fun BuildingUpgradeRowItem(
    row: BuildingUpgradeRow,
    onUpgradeOne: () -> Unit,
    onUpgradeAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(UpgradeColumnGap)
    ) {
        Text(
            text = row.displayName,
            fontSize = 12.sp,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically)
        )
        Text(
            text = "${row.count}",
            fontSize = 12.sp,
            color = Color.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically)
        )
        GameButton(
            text = "升级",
            onClick = onUpgradeOne,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
        GameButton(
            text = "一键升级",
            onClick = onUpgradeAll,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
    }
}

/** 一键升级列表行数据。 */
@Immutable
data class BuildingUpgradeRow(
    val def: BuildingUpgradeDef,
    val displayName: String,
    /** 该宗门内可升级的源建筑实例数 */
    val count: Int
)

/**
 * 派生一键升级列表（纯函数，可单测）：
 * 遍历升级注册表，仅保留玩家在当前作用域宗门内已建造（数量 > 0）的源建筑。
 *
 * @param gameData 当前游戏数据
 * @return 按注册表顺序的可升级行列表
 */
internal fun buildUpgradeRows(gameData: GameData): List<BuildingUpgradeRow> {
    val activeSectId = gameData.activeSectId
    return BuildingUpgradeRegistry.all.mapNotNull { def ->
        val source = BuildingFeatureRegistry.findByKey(def.sourceKey) ?: return@mapNotNull null
        val count = gameData.placedBuildings.count {
            it.sectId == activeSectId && it.buildingId == def.sourceKey
        }
        if (count <= 0) return@mapNotNull null
        BuildingUpgradeRow(
            def = def,
            displayName = source.displayName,
            count = count
        )
    }
}
