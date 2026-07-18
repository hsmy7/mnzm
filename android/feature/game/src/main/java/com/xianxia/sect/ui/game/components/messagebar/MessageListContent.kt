package com.xianxia.sect.ui.game.components.messagebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.GameEventRecord
import com.xianxia.sect.ui.game.components.messagebar.GameEventFormat
import kotlinx.coroutines.launch

/**
 * 消息列表——支持智能滚动。
 *
 * 核心行为：
 * - 新消息到达时，仅在用户已在最底层时自动滚动到底部
 * - 用户滚动到历史消息时不打断阅读位置
 * - 不在底部时右下角显示"↓"跳转按钮
 */
@Composable
fun MessageListContent(
    events: List<GameEventRecord>,
    modifier: Modifier = Modifier,
    scrollToBottomTrigger: Int = 0
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 检测是否在底部（最后一项可见）
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= layoutInfo.totalItemsCount - 2
        }
    }

    // 新消息到达时自动滚动到底部（仅当已在底部时）
    LaunchedEffect(events.size) {
        if (events.isNotEmpty() && isAtBottom) {
            listState.animateScrollToItem(events.size - 1)
        }
    }

    // 展开面板时强制滚动到底部（显示最新消息）
    LaunchedEffect(scrollToBottomTrigger) {
        if (scrollToBottomTrigger > 0 && events.isNotEmpty()) {
            listState.animateScrollToItem(events.size - 1)
        }
    }

    Box(modifier = modifier) {
        // 空列表占位提示
        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无消息",
                    fontSize = 13.sp,
                    color = Color(0xFF888888)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            items(
                items = events,
                key = { "${it.timestamp}_${it.eventType}_${it.summary}_${it.relatedEntityId}" }
            ) { event ->
                MessageRow(event = event)
            }
        }

        // "↓"跳转到底部按钮
        AnimatedVisibility(
            visible = !isAtBottom && events.isNotEmpty(),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(events.size - 1)
                    }
                },
                modifier = Modifier.size(32.dp),
                containerColor = Color(0xCCFFFFFF)
            ) {
                Text(text = "↓", fontSize = 16.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 单条消息行——显示游戏时间和事件摘要。
 */
@Composable
private fun MessageRow(event: GameEventRecord) {
    val phaseName = GameEventFormat.phaseName(event.phase)
    val timeStr = "第${event.year}年${event.month}月${phaseName}"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Text(
            text = timeStr,
            fontSize = 11.sp,
            color = Color(0xFF888888)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = event.summary,
            fontSize = 13.sp,
            color = Color(0xFF333333)
        )
    }
}


