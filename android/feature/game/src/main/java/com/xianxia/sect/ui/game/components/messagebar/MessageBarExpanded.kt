package com.xianxia.sect.ui.game.components.messagebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventRecord

/**
 * 消息栏展开态面板——占据屏幕左侧 50%，从左滑入。
 *
 * 布局：
 * - 背景色（米色 + 浅色覆盖层）覆盖整个面板区域，包括系统栏下方
 * - 内容区通过 systemBarsPadding 避让导航栏/状态栏
 *
 * ┌──────┬──────────────────────────┐
 * │ 标签区 │ 消息区（浅色叠加层）        │
 * │ (10%) │ (90%)                    │
 * │ 世界  │ 第X年X月 事件摘要          │
 * │ 宗门  │ ─── 1dp 灰线 ───         │
 * │(选中) │ 第X年X月 事件摘要          │
 * │      │                    ↓     │
 * └──────┴──────────────────────────┘
 */
@Composable
fun MessageBarExpanded(
    events: List<GameEventRecord>,
    selectedTab: GameEventCategory,
    onTabSelected: (GameEventCategory) -> Unit,
    onDismiss: () -> Unit,
    scrollToBottomTrigger: Int = 0
) {
    val beige = Color(0xFFF6EBD5)
    val dividerColor = Color(0xFFDCD6D0)
    val overlayColor = Color(0x0A000000)

    AnimatedVisibility(
        visible = true,
        enter = slideInHorizontally(animationSpec = tween(300)) { -it },
        exit = slideOutHorizontally(animationSpec = tween(250)) { -it },
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.5f)
    ) {
        // 外层背景全屏铺满（包括系统栏下方）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(beige)
        ) {
            // 内容区避让左侧安全区（系统栏 + 屏幕 cutout）
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Left)
                    )
            ) {
                // 左侧 10% — 标签区
                MessageBarTabRow(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    modifier = Modifier.fillMaxWidth(0.1f)
                )

                // 1dp 灰色竖向分隔线
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(dividerColor)
                )

                // 右侧 90% — 消息区 + 浅色覆盖层
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(overlayColor)
                ) {
                    MessageListContent(
                        events = events,
                        modifier = Modifier.fillMaxSize(),
                        scrollToBottomTrigger = scrollToBottomTrigger
                    )
                }
            }
        }
    }
}
