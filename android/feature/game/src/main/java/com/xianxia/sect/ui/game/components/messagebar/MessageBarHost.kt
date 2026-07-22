package com.xianxia.sect.ui.game.components.messagebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventRecord
import kotlinx.coroutines.launch

/**
 * 消息栏宿主——管理展开/收起状态、标签选择、与 hideUi 联动。
 *
 * 布局分两层：
 * - 收起态：左下角半透明消息栏（不占用全屏空间）
 * - 展开态：全屏覆盖，使用 overlay 式布局不干扰其他 UI
 *
 * @param events 全部游戏事件
 * @param modifier Compose modifier（仅收起态使用）
 * @param isUiVisible 主界面 UI 可见性（与眼睛按钮联动）
 */
@Composable
fun MessageBarHost(
    events: List<GameEventRecord>,
    modifier: Modifier = Modifier,
    isUiVisible: Boolean = true
) {
    var isExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(GameEventCategory.SECT) }
    var scrollToBottomTrigger by remember { mutableIntStateOf(0) }

    val filteredEvents = remember(events, selectedTab) {
        events.filter {
            GameEventCategory.fromValue(it.category) == selectedTab
        }
    }

    val latestMessage = remember(filteredEvents) {
        filteredEvents.takeLast(3).joinToString("\n") {
            GameEventFormat.formatEventPreview(it.year, it.month, it.phase, it.summary)
        }
    }

    LaunchedEffect(isExpanded, selectedTab) {
        if (isExpanded) scrollToBottomTrigger++
    }

    // 展开态：全屏覆盖，独立于布局流
    if (isExpanded) {
        BackHandler { isExpanded = false }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x40000000))
                .clickable { isExpanded = false }
        )
        MessageBarExpanded(
            events = filteredEvents,
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            onDismiss = { isExpanded = false },
            scrollToBottomTrigger = scrollToBottomTrigger
        )
    }

    // 收起态：左下角（仅占自身位置，不干扰其他 UI）
    AnimatedVisibility(
        visible = !isExpanded && isUiVisible,
        enter = fadeIn(), exit = fadeOut(),
        modifier = modifier
    ) {
        MessageBarCollapsed(
            latestMessage = latestMessage,
            onClick = { isExpanded = true }
        )
    }
}
