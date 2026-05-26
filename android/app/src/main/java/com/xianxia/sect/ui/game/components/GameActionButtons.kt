package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.xianxia.sect.R
import com.xianxia.sect.ui.navigation.GameRoute

/**
 * Floating action buttons overlay -- top-right corner of the main game screen.
 * Contains navigation buttons for all major game features (log, merchant, recruit,
 * build, warehouse, settings, disciples, world map, diplomacy, planting).
 *
 * Expected to be placed inside a [androidx.compose.foundation.layout.Box] -- the
 * caller should provide [Modifier.align] (e.g. [Alignment.TopEnd]) via [modifier].
 */
@Composable
fun GameActionButtons(
    dialogNavController: NavHostController,
    buildingBarExpanded: Boolean,
    onToggleBuildingBar: () -> Unit,
    onCancelPlacement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .displayCutoutPadding()
            .padding(top = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FloatingActionButton(
                text = "日志",
                drawableRes = R.drawable.ui_log_button
            ) { dialogNavController.navigate(GameRoute.BattleLog.route) }
            FloatingActionButton(
                text = "商人",
                drawableRes = R.drawable.ui_merchant_button
            ) { dialogNavController.navigate(GameRoute.Merchant.route) }
            FloatingActionButton(
                text = "招募",
                drawableRes = R.drawable.ui_recruit_button
            ) { dialogNavController.navigate(GameRoute.Recruit.route) }
            FloatingActionButton(
                text = "建造",
                drawableRes = R.drawable.ui_build_button
            ) { onToggleBuildingBar(); onCancelPlacement() }
            FloatingActionButton(
                text = "仓库",
                drawableRes = R.drawable.ui_warehouse_button
            ) { dialogNavController.navigate(GameRoute.Warehouse.route) }
            FloatingActionButton(
                text = "设置",
                drawableRes = R.drawable.ui_settings_button
            ) { dialogNavController.navigate(GameRoute.Settings.route) }
        }
        FloatingActionButton(
            text = "弟子",
            drawableRes = R.drawable.ui_team_button
        ) { dialogNavController.navigate(GameRoute.Disciples.route) }
        FloatingActionButton(
            text = "世界",
            drawableRes = R.drawable.ui_map_button
        ) { dialogNavController.navigate(GameRoute.WorldMap.route) }
        FloatingActionButton(
            text = "外交",
            drawableRes = R.drawable.ui_diplomacy_button
        ) { dialogNavController.navigate(GameRoute.Diplomacy.route) }
        FloatingActionButton(
            text = "种植",
            drawableRes = R.drawable.ui_planting_button
        ) { dialogNavController.navigate(GameRoute.Planting.route) }
    }
}

/**
 * Small circular icon button with a text label at the bottom.
 * Sized at 35dp with a circular clip and clickable overlay.
 */
@Composable
private fun FloatingActionButton(
    text: String,
    drawableRes: Int = R.drawable.ui_button,
    onClick: () -> Unit
) {
    val size = 35.dp
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
}
