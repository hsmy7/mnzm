package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.AtlasSpriteImage
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.getRarityColor
import com.xianxia.sect.ui.theme.GameColors

/** 更换界面回调集合（ReplaceSelectionScreen 拆分，规避类构造参数上限） */
internal data class ReplaceSelectionActions(
    val onSelect: (String) -> Unit,
    val onConfirm: (String) -> Unit,
    val onDismiss: () -> Unit
)

/** 更换界面配置 */
internal data class ReplaceSelectionConfig(
    val title: String,
    val emptyText: String,
    val items: List<ReplaceSelectionItem>,
    val selectedId: String?,
    val confirmLabel: String,
    val actions: ReplaceSelectionActions
)

/**
 * 全屏更换界面（功法/装备共用）：左 7 右 3 双栏 + 1dp 灰竖线。
 *
 * 进入界面默认选中列表第一个物品（[selectedId] 为空时取首项为有效选中），
 * 右侧详情面板常驻显示选中项四区域内容；点击其他项切换选中并原地更新详情
 * （重复点击已选中项不变化）。左侧列表可滚动、关注优先→品阶降序、
 * 单选高亮、禁用心法置底置灰。
 */
@Composable
internal fun ReplaceSelectionScreen(config: ReplaceSelectionConfig) {
    UnifiedGameDialog(
        onDismissRequest = config.actions.onDismiss,
        title = config.title,
        mode = DialogMode.Full,
        dismissOnClickOutside = false
    ) {
        // 默认选中第一个物品：无外部选中时以列表首项作为有效选中（调用方无需初始化状态）
        val effectiveSelectedId = config.selectedId ?: config.items.firstOrNull()?.id
        val selectedItem = config.items.find { it.id == effectiveSelectedId }
        Row(modifier = Modifier.fillMaxSize()) {
            ReplaceSelectionList(
                items = config.items,
                selectedId = effectiveSelectedId,
                emptyText = config.emptyText,
                onSelect = config.actions.onSelect,
                modifier = Modifier.weight(7f).fillMaxHeight()
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(GameColors.DividerGray)
            )
            if (selectedItem != null) {
                Box(modifier = Modifier.weight(3f).fillMaxHeight()) {
                    ReplaceDetailPanelContent(
                        detail = selectedItem.detail,
                        confirmLabel = config.confirmLabel,
                        onConfirm = { config.actions.onConfirm(selectedItem.id) }
                    )
                }
            } else {
                Box(
                    modifier = Modifier.weight(3f).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = config.emptyText,
                        fontSize = 12.sp,
                        color = Color.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

/** 左侧仓库列表：空态提示 + 可滚动网格 */
@Composable
private fun ReplaceSelectionList(
    items: List<ReplaceSelectionItem>,
    selectedId: String?,
    emptyText: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) {
        Text(
            text = emptyText,
            fontSize = 12.sp,
            color = Color.Black,
            textAlign = TextAlign.Center,
            modifier = modifier.padding(horizontal = 16.dp, vertical = 32.dp)
        )
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(68.dp),
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(12.dp)
        ) {
            items(items, key = { it.id }, contentType = { "replace_selection_item" }) { item ->
                ReplaceSelectionCard(
                    item = item,
                    isSelected = item.id == selectedId,
                    onSelect = onSelect
                )
            }
        }
    }
}

/** 左侧列表项：禁用项整体置灰 + "已学心法"标记 + 不可点击 */
@Composable
private fun ReplaceSelectionCard(
    item: ReplaceSelectionItem,
    isSelected: Boolean,
    onSelect: (String) -> Unit
) {
    Box(
        modifier = Modifier.alpha(if (item.isDisabled) 0.4f else 1f),
        contentAlignment = Alignment.Center
    ) {
        UnifiedItemCard(
            data = ItemCardData(
                id = item.id,
                name = item.name,
                rarity = item.rarity,
                quantity = item.quantity,
                isLocked = item.isLocked,
                isManual = item.isManual
            ),
            isSelected = isSelected,
            selectedBorderColor = GameColors.Gold,
            isFollowed = item.isFollowed,
            onClick = { if (!item.isDisabled) onSelect(item.id) },
            size = 68.dp
        )
        if (item.isDisabled) {
            Text(
                text = "已学心法",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * 详情面板四区域：
 * 1 精灵图+名称（品阶色） / 2 属性加成 / 3 技能描述（装备为装备描述） / 4 底部"更换"按钮。
 * 内容区可滚动，按钮固定最底部。
 */
@Composable
private fun ReplaceDetailPanelContent(
    detail: ReplaceDetailData,
    confirmLabel: String,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ReplaceDetailSpriteAndName(detail = detail)
            ReplaceDetailSection(title = "属性加成", lines = detail.attributeLines)
            if (detail.skillLines.isNotEmpty()) {
                ReplaceDetailSection(title = detail.skillTitle, lines = detail.skillLines)
            }
        }
        // 区域4：更换按钮（固定最底部，标准 GameButton 尺寸）
        Spacer(modifier = Modifier.height(8.dp))
        GameButton(
            text = confirmLabel,
            onClick = onConfirm
        )
    }
}

/** 区域1：精灵图 + 名称（颜色随品阶） */
@Composable
private fun ReplaceDetailSpriteAndName(detail: ReplaceDetailData) {
    Box(
        modifier = Modifier
            .size(110.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(getRarityColor(detail.rarity)),
        contentAlignment = Alignment.Center
    ) {
        if (detail.spriteName != null) {
            AtlasSpriteImage(
                name = detail.spriteName,
                contentDescription = detail.name,
                modifier = Modifier.fillMaxSize().padding(4.dp)
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = detail.name,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = getRarityColor(detail.rarity),
        textAlign = TextAlign.Center
    )
    if (detail.subtitle.isNotEmpty()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = detail.subtitle,
            fontSize = 10.sp,
            color = Color.Gray
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = GameColors.DividerGray, thickness = 1.dp)
    Spacer(modifier = Modifier.height(8.dp))
}

/** 区域2/3：分区标题 + 内容行 + 尾部 1dp 灰分隔线 */
@Composable
private fun ReplaceDetailSection(title: String, lines: List<String>) {
    ReplaceDetailSectionTitle(title)
    lines.forEach { line ->
        Text(
            text = line,
            fontSize = 11.sp,
            color = Color.Black,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (lines.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = GameColors.DividerGray, thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** 详情分区标题 */
@Composable
private fun ReplaceDetailSectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black,
        modifier = Modifier.fillMaxWidth()
    )
}
