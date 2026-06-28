package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.navigation.DialogRoute

@Composable
fun GameActionButtons(
    viewModel: GameViewModel,
    buildingBarExpanded: Boolean,
    onToggleBuildingBar: () -> Unit,
    onCancelPlacement: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mailUnreadCount by viewModel.mailUnreadCount.collectAsStateWithLifecycle()
    val activityBadge by viewModel.anyActivityClaimable.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .padding(top = 8.dp, end = 32.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 第一行：外交、商人、建造、仓库、活动（从右往左排列）
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FloatingActionButton(
                text = "外交",
                spriteName = "ui_diplomacy_button"
            ) { viewModel.navigateToDialog(DialogRoute.Diplomacy) }
            FloatingActionButton(
                text = "商人",
                spriteName = "ui_merchant_button"
            ) { viewModel.navigateToDialog(DialogRoute.Merchant) }
            FloatingActionButton(
                text = "建造",
                spriteName = "ui_build_button"
            ) { onToggleBuildingBar(); onCancelPlacement() }
            FloatingActionButton(
                text = "仓库",
                spriteName = "ui_warehouse_button"
            ) { viewModel.navigateToDialog(DialogRoute.Warehouse) }
            FloatingActionButton(
                text = "活动",
                spriteName = "ui_activity_button",
                badge = if (activityBadge) 1 else 0
            ) { viewModel.navigateToDialog(DialogRoute.Activity) }
        }
        FloatingActionButton(
            text = "弟子",
            spriteName = "ui_team_button"
        ) { viewModel.navigateToDialog(DialogRoute.Disciples) }
        FloatingActionButton(
            text = "世界",
            spriteName = "ui_map_button"
        ) { viewModel.navigateToDialog(DialogRoute.WorldMap) }
        FloatingActionButton(
            text = "种植",
            spriteName = "ui_planting_button"
        ) { viewModel.navigateToDialog(DialogRoute.Planting) }
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
        ) { viewModel.navigateToDialog(DialogRoute.Settings) }
        FloatingActionButton(
            text = "招募",
            spriteName = "ui_recruit_button"
        ) { viewModel.navigateToDialog(DialogRoute.Recruit) }
        FloatingActionButton(
            text = "邮件",
            spriteName = "ui_mail_button",
            badge = mailUnreadCount
        ) { viewModel.navigateToDialog(DialogRoute.Mail) }
        FloatingActionButton(
            text = "日志",
            spriteName = "ui_log_button"
        ) { viewModel.navigateToDialog(DialogRoute.BattleLog) }
    }
}

@Composable
private fun FloatingActionButton(
    text: String,
    spriteName: String = "ui_button",
    badge: Int = 0,
    onClick: () -> Unit
) {
    val size = 35.dp
    Box {
        // 按钮本体
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .clickable(onClick = onClick),
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
