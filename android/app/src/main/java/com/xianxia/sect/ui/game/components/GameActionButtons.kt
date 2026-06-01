package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.R
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
    val mailUnreadCount by viewModel.mailUnreadCount.collectAsState()

    Column(
        modifier = modifier
            .padding(top = 8.dp, end = 32.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FloatingActionButton(
                text = "邮件",
                drawableRes = R.drawable.ui_mail_button,
                badge = mailUnreadCount
            ) { viewModel.navigateToDialog(DialogRoute.Mail) }
            FloatingActionButton(
                text = "日志",
                drawableRes = R.drawable.ui_log_button
            ) { viewModel.navigateToDialog(DialogRoute.BattleLog) }
            FloatingActionButton(
                text = "商人",
                drawableRes = R.drawable.ui_merchant_button
            ) { viewModel.navigateToDialog(DialogRoute.Merchant) }
            FloatingActionButton(
                text = "招募",
                drawableRes = R.drawable.ui_recruit_button
            ) { viewModel.navigateToDialog(DialogRoute.Recruit) }
            FloatingActionButton(
                text = "建造",
                drawableRes = R.drawable.ui_build_button
            ) { onToggleBuildingBar(); onCancelPlacement() }
            FloatingActionButton(
                text = "仓库",
                drawableRes = R.drawable.ui_warehouse_button
            ) { viewModel.navigateToDialog(DialogRoute.Warehouse) }
            FloatingActionButton(
                text = "设置",
                drawableRes = R.drawable.ui_settings_button
            ) { viewModel.navigateToDialog(DialogRoute.Settings) }
        }
        FloatingActionButton(
            text = "弟子",
            drawableRes = R.drawable.ui_team_button
        ) { viewModel.navigateToDialog(DialogRoute.Disciples) }
        FloatingActionButton(
            text = "世界",
            drawableRes = R.drawable.ui_map_button
        ) { viewModel.navigateToDialog(DialogRoute.WorldMap) }
        FloatingActionButton(
            text = "外交",
            drawableRes = R.drawable.ui_diplomacy_button
        ) { viewModel.navigateToDialog(DialogRoute.Diplomacy) }
        FloatingActionButton(
            text = "种植",
            drawableRes = R.drawable.ui_planting_button
        ) { viewModel.navigateToDialog(DialogRoute.Planting) }
    }
}

@Composable
private fun FloatingActionButton(
    text: String,
    drawableRes: Int = R.drawable.ui_button,
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
            Image(
                painter = painterResource(id = drawableRes),
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
