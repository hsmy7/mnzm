package com.xianxia.sect.ui.game.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xianxia.sect.ui.components.GameButton
import kotlinx.coroutines.delay

/** 关注按钮防抖窗口：引擎往返期间忽略重复点击，避免快速连点状态翻转与意图相反 */
private const val WATCH_DEBOUNCE_MS = 400L

/**
 * 物品关注切换按钮：使用标准按钮组件 [GameButton]，未关注显示"关注"，已关注显示"已关注"。
 * [watchKey] 为 null（灵石/储物袋等不可关注物品）时不渲染。
 * 防抖期间忽略点击（不改变按钮透明度，避免闪烁）。
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
    GameButton(
        text = if (isWatched) "已关注" else "关注",
        onClick = {
            if (!debouncing) {
                debouncing = true
                onToggleWatch(watchKey)
            }
        }
    )
}
