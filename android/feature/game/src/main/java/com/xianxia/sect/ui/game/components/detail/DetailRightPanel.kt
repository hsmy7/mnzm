package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ResignGateResult
import com.xianxia.sect.core.model.evaluateResignGate
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.LocalDismissDropdown
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.theme.GameColors

/** 弟子类型编辑交互包（DetailRightPanel 参数分组）：当前编辑值 + 下拉开合与回调 */
data class DiscipleTypeEditInteraction(
    val localDiscipleType: String,
    val showDropdown: Boolean,
    val onDropdownChange: (Boolean) -> Unit,
    val onTypeChange: (String) -> Unit
)

/** 弟子详情右侧面板（竖屏布局：名称行/类型下拉/操作按钮行）。 */
@Composable
fun DetailRightPanel(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    typeEdit: DiscipleTypeEditInteraction,
    actions: DetailActionCallbacks,
    viewModel: GameViewModel?
) {
    val localDiscipleType = typeEdit.localDiscipleType
    val showDiscipleTypeDropdown = typeEdit.showDropdown
    val onDiscipleTypeDropdownChange = typeEdit.onDropdownChange
    val onLocalDiscipleTypeChange = typeEdit.onTypeChange
    val dismissDropdown = LocalDismissDropdown.current

    Column(
        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.4f).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DetailPortrait(disciple = disciple)
        // 提前计算翻页索引，用于名称两侧的翻页按钮
        val currentIndex = allDisciples.indexOfFirst { it.id == disciple.id }
        val hasPrev = currentIndex > 0
        val hasNext = currentIndex >= 0 && currentIndex < allDisciples.size - 1
        val navTo = actions.onNavigateToDisciple

        // 弟子名称行：翻页按钮在名称两侧
        DetailNameRow(
            disciple = disciple,
            hasPrev = hasPrev,
            hasNext = hasNext,
            onPrevClick = if (hasPrev && navTo != null) {
                { dismissDropdown(); navTo(allDisciples[currentIndex - 1]) }
            } else null,
            onNextClick = if (hasNext && navTo != null) {
                { dismissDropdown(); navTo(allDisciples[currentIndex + 1]) }
            } else null,
            onNameClick = actions.onRenameDisciple?.let { rename ->
                { dismissDropdown(); rename() }
            }
        )
        Text(disciple.realmName, fontSize = 14.sp, color = Color.Black)
        Text(disciple.spiritRootName, fontSize = 12.sp, color = Color(0xFF00695C))
        Spacer(modifier = Modifier.height(8.dp))
        // 六个操作按钮：FlowRow 根据屏幕宽度自动换行
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DetailTypeDropdown(
                localDiscipleType = localDiscipleType,
                showDropdown = showDiscipleTypeDropdown,
                onDropdownToggle = { onDiscipleTypeDropdownChange(!showDiscipleTypeDropdown) },
                onTypeSelected = { newType ->
                    onDiscipleTypeDropdownChange(false)
                    onLocalDiscipleTypeChange(newType)
                    viewModel?.disciple?.changeDiscipleType(disciple.id, newType)
                }
            )
            DetailActionButtonsRow(
                disciple = disciple,
                dismissDropdown = dismissDropdown,
                viewModel = viewModel,
                actions = actions
            )
        }
        Spacer(modifier = Modifier.weight(0.5f))
    }
}

/** 弟子头像区：头像 + 底部间距 */
@Composable
private fun ColumnScope.DetailPortrait(disciple: DiscipleAggregate) {
    val resId = PortraitPool.getResourceId(disciple.portraitRes)
        .takeIf { it != 0 }
        ?: (SpriteResRegistry.resolve("disciple_portrait") ?: 0)
    if (resId != 0) {
        Image(
            painter = painterResource(id = resId),
            contentDescription = null,
            modifier = Modifier.weight(2f).fillMaxWidth().padding(horizontal = 4.dp),
            contentScale = ContentScale.Fit
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
}

/** 弟子名称行：翻页按钮在名称两侧 */
@Composable
private fun DetailNameRow(
    disciple: DiscipleAggregate,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrevClick: (() -> Unit)?,
    onNextClick: (() -> Unit)?,
    onNameClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (hasPrev && onPrevClick != null) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape)
                    .background(Color(0x99000000))
                    .clickableWithSound { onPrevClick() },
                contentAlignment = Alignment.Center
            ) { Text("‹", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        }
        Text(
            disciple.name,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .clickableWithSound(enabled = onNameClick != null) {
                    onNameClick?.invoke()
                }
        )
        if (hasNext && onNextClick != null) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape)
                    .background(Color(0x99000000))
                    .clickableWithSound { onNextClick() },
                contentAlignment = Alignment.Center
            ) { Text("›", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        }
    }
}

/** 弟子类型切换按钮 + 下拉 */
@Composable
private fun DetailTypeDropdown(
    localDiscipleType: String,
    showDropdown: Boolean,
    onDropdownToggle: () -> Unit,
    onTypeSelected: (String) -> Unit
) {
    val btnColor = if (localDiscipleType == "inner") Color(0xFF9C27B0) else Color(0xFF7B1FA2)
    val btnShape = if (showDropdown)
        RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
    else
        RoundedCornerShape(4.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .clip(btnShape)
                .background(btnColor)
                .clickableWithSound { onDropdownToggle() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                if (localDiscipleType == "inner") "内门弟子" else "外门弟子",
                fontSize = 10.sp,
                color = Color.White
            )
        }
        if (showDropdown) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                    .background(Color.White)
                    .border(1.dp, btnColor)
                    .clickableWithSound {
                        onTypeSelected(if (localDiscipleType == "outer") "inner" else "outer")
                    }
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (localDiscipleType == "outer") "内门弟子" else "外门弟子",
                    fontSize = 10.sp,
                    color = Color.Black
                )
            }
        }
    }
}

/** 弟子操作按钮区：关系/储物袋/关注/驱逐/交谈/日志/拜师/卸任 */
@Composable
private fun DetailActionButtonsRow(
    disciple: DiscipleAggregate,
    dismissDropdown: () -> Unit,
    viewModel: GameViewModel?,
    actions: DetailActionCallbacks
) {
    DetailActionButton(
        text = "关系",
        color = GameColors.Success,
        onClick = { dismissDropdown(); actions.onShowRelations() }
    )
    DetailActionButton(
        text = "储物袋",
        color = GameColors.Info,
        onClick = { dismissDropdown(); actions.onShowStorageBag() }
    )
    DetailActionButton(
        text = if (disciple.isFollowed) "已关注" else "关注",
        color = if (disciple.isFollowed) GameColors.Gold else Color.Black,
        onClick = { dismissDropdown(); viewModel?.disciple?.toggleFollowDisciple(disciple.id) }
    )
    DetailActionButton(
        text = "驱逐",
        color = Color(0xFFE74C3C),
        onClick = { dismissDropdown(); actions.onShowExpelConfirm() }
    )
    DetailActionButton(
        text = "交谈",
        color = GameColors.Warning,
        onClick = { dismissDropdown(); actions.onShowChat() }
    )
    DetailActionButton(
        text = "日志",
        color = Color(0xFF00BCD4),
        onClick = { dismissDropdown(); actions.onShowLifeLog() }
    )
    // 拜师按钮：已有师父时灰色禁用显示"已拜师"；师徒关系永久，仅一方死亡解绑
    val hasMaster = disciple.masterId != null
    DetailActionButton(
        text = if (hasMaster) "已拜师" else "拜师",
        color = if (hasMaster) Color(0xFF9E9E9E) else Color(0xFF8D6E63),
        enabled = !hasMaster,
        onClick = { dismissDropdown(); actions.onShowApprentice() }
    )
    // 卸任按钮：空闲/死亡置灰；其余状态点击后由 DiscipleDetailScreen 按状态分流
    val resignDisabled = evaluateResignGate(disciple.status, disciple.isAlive) is ResignGateResult.Disabled
    DetailActionButton(
        text = "卸任",
        color = if (resignDisabled) Color(0xFF9E9E9E) else Color(0xFF607D8B),
        enabled = !resignDisabled,
        onClick = { dismissDropdown(); actions.onShowResignConfirm() }
    )
}

/** 通用操作按钮 */
@Composable
private fun DetailActionButton(
    text: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .clickableWithSound(enabled = enabled) { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) { Text(text, fontSize = 10.sp, color = Color.White) }
}

/**
 * 弟子详情右侧面板的操作按钮回调集合。
 * 将回调分组为数据类，控制 Composable 参数数量在规范上限内。
 */
data class DetailActionCallbacks(
    val onShowRelations: () -> Unit,
    val onShowStorageBag: () -> Unit,
    val onShowExpelConfirm: () -> Unit,
    val onShowLifeLog: () -> Unit,
    val onShowApprentice: () -> Unit,
    val onRenameDisciple: (() -> Unit)? = null,
    val onNavigateToDisciple: ((DiscipleAggregate) -> Unit)?,
    val onShowChat: () -> Unit = {},  // 交谈
    val onShowResignConfirm: () -> Unit = {},  // 卸任（分流逻辑在 DiscipleDetailScreen）
)
