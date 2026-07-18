package com.xianxia.sect.ui.game.components.messagebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.GameEventCategory

/**
 * 消息栏标签区——占据左侧 10% 宽度。
 * 选中标签的背景色与消息区叠加层完全一致，视觉上连成一体。
 */
@Composable
fun MessageBarTabRow(
    selectedTab: GameEventCategory,
    onTabSelected: (GameEventCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    // 与消息区叠加层完全一致的颜色
    val activeBg = Color(0x0A000000)
    val defaultTextColor = Color(0xFF666666)
    val selectedTextColor = Color.Black

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GameEventCategory.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(if (isSelected) activeBg else Color.Transparent)
                    .clickable { onTabSelected(tab) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab.label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) selectedTextColor else defaultTextColor
                )
            }
        }
    }
}
