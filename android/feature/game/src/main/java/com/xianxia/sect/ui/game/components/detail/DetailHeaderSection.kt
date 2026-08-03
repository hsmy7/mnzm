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
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.LocalDismissDropdown
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.theme.GameColors

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
)

@Composable
fun DetailRightPanel(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    localDiscipleType: String,
    showDiscipleTypeDropdown: Boolean,
    onDiscipleTypeDropdownChange: (Boolean) -> Unit,
    onLocalDiscipleTypeChange: (String) -> Unit,
    actions: DetailActionCallbacks,
    viewModel: GameViewModel?
) {
    val dismissDropdown = LocalDismissDropdown.current

    Column(
        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.4f).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
        // 提前计算翻页索引，用于名称两侧的翻页按钮
        val currentIndex = allDisciples.indexOfFirst { it.id == disciple.id }
        val hasPrev = currentIndex > 0
        val hasNext = currentIndex >= 0 && currentIndex < allDisciples.size - 1
        val navTo = actions.onNavigateToDisciple

        // 弟子名称行：翻页按钮在名称两侧
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (hasPrev && navTo != null) {
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape)
                        .background(Color(0x99000000))
                        .clickableWithSound { dismissDropdown(); navTo(allDisciples[currentIndex - 1]) },
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
                    .clickableWithSound(enabled = actions.onRenameDisciple != null) {
                        dismissDropdown()
                        actions.onRenameDisciple?.invoke()
                    }
            )
            if (hasNext && navTo != null) {
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape)
                        .background(Color(0x99000000))
                        .clickableWithSound { dismissDropdown(); navTo(allDisciples[currentIndex + 1]) },
                    contentAlignment = Alignment.Center
                ) { Text("›", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            }
        }
        Text(disciple.realmName, fontSize = 14.sp, color = Color.Black)
        Text(disciple.spiritRootName, fontSize = 12.sp, color = Color(0xFF00695C))
        Spacer(modifier = Modifier.height(8.dp))
        // 六个操作按钮：FlowRow 根据屏幕宽度自动换行
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val btnColor = if (localDiscipleType == "inner") Color(0xFF9C27B0) else Color(0xFF7B1FA2)
            val btnShape = if (showDiscipleTypeDropdown)
                RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
            else
                RoundedCornerShape(4.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .clip(btnShape)
                        .background(btnColor)
                        .clickableWithSound { onDiscipleTypeDropdownChange(!showDiscipleTypeDropdown) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (localDiscipleType == "inner") "内门弟子" else "外门弟子",
                        fontSize = 10.sp,
                        color = Color.White
                    )
                }
                if (showDiscipleTypeDropdown) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                            .background(Color.White)
                            .border(1.dp, btnColor)
                            .clickableWithSound {
                                onDiscipleTypeDropdownChange(false)
                                val newType = if (localDiscipleType == "outer") "inner" else "outer"
                                onLocalDiscipleTypeChange(newType)
                                viewModel?.changeDiscipleType(disciple.id, newType)
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
            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(GameColors.Success)
                    .clickableWithSound { dismissDropdown(); actions.onShowRelations() }.padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("关系", fontSize = 10.sp, color = Color.White) }
            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(GameColors.Info)
                    .clickableWithSound { dismissDropdown(); actions.onShowStorageBag() }.padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("储物袋", fontSize = 10.sp, color = Color.White) }
            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (disciple.isFollowed) GameColors.Gold else Color.Black)
                    .clickableWithSound { dismissDropdown(); viewModel?.toggleFollowDisciple(disciple.id) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text(if (disciple.isFollowed) "已关注" else "关注", fontSize = 10.sp, color = Color.White) }
            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFE74C3C))
                    .clickableWithSound { dismissDropdown(); actions.onShowExpelConfirm() }.padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("驱逐", fontSize = 10.sp, color = Color.White) }
            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(GameColors.Warning)
                    .clickableWithSound { dismissDropdown(); actions.onShowChat() }.padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("交谈", fontSize = 10.sp, color = Color.White) }
            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF00BCD4))
                    .clickableWithSound { dismissDropdown(); actions.onShowLifeLog() }.padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("日志", fontSize = 10.sp, color = Color.White) }
            // 拜师按钮：已有师父时灰色禁用显示"已拜师"；师徒关系永久，仅一方死亡解绑
            val hasMaster = disciple.masterId != null
            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (hasMaster) Color(0xFF9E9E9E) else Color(0xFF8D6E63))
                    .clickableWithSound(enabled = !hasMaster) { dismissDropdown(); actions.onShowApprentice() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text(if (hasMaster) "已拜师" else "拜师", fontSize = 10.sp, color = Color.White) }
        }
        Spacer(modifier = Modifier.weight(0.5f))
    }
}
