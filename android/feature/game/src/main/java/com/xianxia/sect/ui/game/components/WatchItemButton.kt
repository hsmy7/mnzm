package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.theme.GameColors
import kotlinx.coroutines.delay

/** 关注按钮防抖窗口：引擎往返期间忽略重复点击，避免快速连点状态翻转与意图相反 */
private const val WATCH_DEBOUNCE_MS = 400L

/**
 * 物品关注切换按钮：未关注黑底"关注"，已关注金底"已关注"（金底黑字保证可读性）。
 * [watchKey] 为 null（灵石/储物袋等不可关注物品）时不渲染。
 */
@Composable
fun WatchItemButton(
    watchKey: String?,
    watchedKeys: Set<String>,
    onToggleWatch: (String) -> Unit
) {
    if (watchKey == null) return
    val isWatched = watchKey in watchedKeys
    var debouncing by remember { mutableStateOf(false) }
    LaunchedEffect(debouncing) {
        if (debouncing) {
            delay(WATCH_DEBOUNCE_MS)
            debouncing = false
        }
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isWatched) GameColors.Gold else Color.Black)
            .clickableWithSound(enabled = !debouncing) {
                debouncing = true
                onToggleWatch(watchKey)
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isWatched) "已关注" else "关注",
            fontSize = 10.sp,
            // 黑底白字（未关注）/ 金底黑字（已关注）双高对比度
            color = if (isWatched) Color.Black else Color.White
        )
    }
}
