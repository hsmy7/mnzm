@file:Suppress("MatchingDeclarationName") // 同一文件含 BuildingEntrySpec 数据类与 BuildingEntryButton 组件（内聚组件文件）

package com.xianxia.sect.ui.game.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.theme.GameColors

/**
 * 选中建筑正下方"进入"按钮的属性：精灵图名 + 图标下方白色文本。
 */
internal data class BuildingEntrySpec(
    val spriteName: String,
    val label: String
)

/**
 * 建筑显示名 → "进入"按钮的图标/文本映射（纯函数，可单测）。
 * 灵田/炼丹炉/锻造坊使用专属图标（种植ui/炼丹ui/锻造ui）与文本（种植/炼丹/锻造），
 * 其余建筑使用通用"进入ui/进入"。
 */
internal fun buildingEntrySpec(displayName: String): BuildingEntrySpec {
    return when (BuildingFeatureRegistry.findByDisplayName(displayName)?.key) {
        "spirit_field" -> BuildingEntrySpec("ui_planting", "种植")
        "alchemy" -> BuildingEntrySpec("ui_alchemy", "炼丹")
        "forge" -> BuildingEntrySpec("ui_forge", "锻造")
        else -> BuildingEntrySpec("ui_enter", "进入")
    }
}

/**
 * "进入"按钮正方形背景框的底色（参考部落冲突的暖棕木色面板）。
 * 深色底保证下方白色文本清晰可读。
 */
internal val BuildingEntryFrameColor = Color(0xFF5D4A2F)

/**
 * 选中建筑正下方的"进入"按钮 — 正方形背景框 + 框内图标 + 图标下方白色文本（CoC 风格）。
 * 点击 [onClick] 打开该建筑详情对话框。
 */
@Composable
internal fun BuildingEntryButton(
    displayName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spec = buildingEntrySpec(displayName)
    Column(
        modifier = modifier
            .size(BuildingEntryFrameSize)
            .background(BuildingEntryFrameColor, RoundedCornerShape(BuildingEntryFrameCornerRadius))
            .border(2.dp, GameColors.ButtonBorder, RoundedCornerShape(BuildingEntryFrameCornerRadius))
            .clickableWithSound { onClick() }
            .padding(
                start = BuildingEntryFramePadding,
                end = BuildingEntryFramePadding,
                top = BuildingEntryFramePadding,
                bottom = BuildingEntryTextBottomGap
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 图标占据文字上方的剩余空间并居中
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            SpriteImage(
                name = spec.spriteName,
                contentDescription = spec.label,
                modifier = Modifier.size(BuildingEntryIconSize),
                contentScale = ContentScale.FillBounds
            )
        }
        // 设计需求：文字紧贴背景框下边框（CoC 深色底上白字，用户需求优先于统一黑色文本约定）
        Text(
            text = spec.label,
            color = Color.White,
            fontSize = BuildingEntryTextSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** "进入"按钮正方形框边长（dp） */
internal val BuildingEntryFrameSize = 76.dp
/** "进入"按钮图标尺寸（dp） */
internal val BuildingEntryIconSize = 46.dp
/** "进入"按钮框角半径（dp） */
internal val BuildingEntryFrameCornerRadius = 8.dp
/** "进入"按钮框内边距（dp：左右与顶部） */
internal val BuildingEntryFramePadding = 6.dp
/** "进入"按钮文字距下边框的间距（dp，贴边但不出框） */
internal val BuildingEntryTextBottomGap = 2.dp
/** "进入"按钮文本字号（sp） */
internal val BuildingEntryTextSize = 12.sp
