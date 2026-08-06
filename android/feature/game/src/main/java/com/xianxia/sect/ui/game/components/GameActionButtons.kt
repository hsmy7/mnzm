package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.components.clickableWithSound

@Composable
fun GameActionButtons(
    viewModel: GameViewModel,
    buildingBarExpanded: Boolean,
    onToggleBuildingBar: () -> Unit,
    onCancelPlacement: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mailUnreadCount by viewModel.mailUnreadCount.collectAsStateWithLifecycle()
    // 活动界面仅剩每日签到，红点只跟签到可领取状态
    val activityBadge by viewModel.canClaimToday.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .padding(top = 8.dp, end = 32.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 第一行：引导、外交、商人、建造、仓库、活动（从右往左排列）
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FloatingActionButton(
                text = "引导",
                spriteName = "ui_guide_button"
            ) { viewModel.navigateToDialog(DialogType.Guide) }
            FloatingActionButton(
                text = "外交",
                spriteName = "ui_diplomacy_button"
            ) { viewModel.navigateToDialog(DialogType.Diplomacy) }
            FloatingActionButton(
                text = "商人",
                spriteName = "ui_merchant_button"
            ) { viewModel.navigateToDialog(DialogType.Merchant) }
            FloatingActionButton(
                text = "建造",
                spriteName = "ui_build_button"
            ) { onToggleBuildingBar(); onCancelPlacement() }
            FloatingActionButton(
                text = "仓库",
                spriteName = "ui_warehouse_button"
            ) { viewModel.navigateToDialog(DialogType.Warehouse) }
            FloatingActionButton(
                text = "活动",
                spriteName = "ui_activity_button",
                badge = if (activityBadge) 1 else 0
            ) { viewModel.navigateToDialog(DialogType.Activity) }
            FloatingActionButton(
                text = "历战",
                spriteName = "ui_lizhan_button"
            ) { viewModel.navigateToDialog(DialogType.Lizhan) }
        }
        FloatingActionButton(
            text = "弟子",
            spriteName = "ui_team_button"
        ) { viewModel.navigateToDialog(DialogType.Disciples) }
        FloatingActionButton(
            text = "世界",
            spriteName = "ui_map_button"
        ) { viewModel.navigateToDialog(DialogType.WorldMap) }
        FloatingActionButton(
            text = "种植",
            spriteName = "ui_planting_button"
        ) { viewModel.navigateToDialog(DialogType.Planting) }
        // 排行榜入口（放第二行末尾避免第一行 7 按钮在 320dp 老屏溢出）
        FloatingActionButton(
            text = "排行",
            spriteName = "ui_leaderboard_button"
        ) { viewModel.navigateToDialog(DialogType.Leaderboard) }
    }
}

@Composable
fun LeftSideButtons(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val mailUnreadCount by viewModel.mailUnreadCount.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .padding(start = 32.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FloatingActionButton(
            text = "设置",
            spriteName = "ui_settings_button"
        ) { viewModel.navigateToDialog(DialogType.Settings) }
        FloatingActionButton(
            text = "招募",
            spriteName = "ui_recruit_button"
        ) { viewModel.navigateToDialog(DialogType.Recruit) }
        FloatingActionButton(
            text = "邮件",
            spriteName = "ui_mail_button",
            badge = mailUnreadCount
        ) { viewModel.navigateToDialog(DialogType.Mail) }
        FloatingActionButton(
            text = "日志",
            spriteName = "ui_log_button"
        ) { viewModel.navigateToDialog(DialogType.BattleLog) }
    }
}

@Composable
internal fun FloatingActionButton(
    text: String,
    spriteName: String = "ui_button",
    badge: Int = 0,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val size = 35.dp
    Box(modifier = modifier) {
        // 按钮本体
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .clickableWithSound(onClick = onClick),
            contentAlignment = Alignment.BottomCenter
        ) {
            SpriteImage(
                name = spriteName,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds
            )
            Text(
                text = text,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
        // 红点角标（按钮外部右上方，不接触屏幕顶部）
        if (badge > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-4).dp)
                    .size(7.dp)
                    .background(Color.Red, CircleShape)
            )
        }
    }
}
