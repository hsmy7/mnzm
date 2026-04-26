package com.xianxia.sect.ui.game.dialogs

import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEvent
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.theme.GameColors

@Composable
internal fun EventLogCard(
    events: List<GameEvent>,
    onViewAll: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = GameColors.PageBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "事件记录",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                GameButton(
                    text = "查看全部",
                    onClick = onViewAll
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无事件",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            } else {
                events.forEach { event ->
                    EventItem(event = event)
                    if (event != events.last()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun EventItem(event: GameEvent) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when (event.type.name) {
            "SUCCESS" -> "✓"
            "WARNING" -> "⚠"
            "ERROR" -> "✕"
            "BATTLE" -> "⚔"
            else -> "•"
        }

        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(GameColors.PageBackground),
            contentAlignment = Alignment.Center
        ) {
            Text(text = icon, fontSize = 12.sp, color = Color.Black)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.message,
                fontSize = 12.sp,
                color = Color.Black
            )
            Text(
                text = event.displayTime,
                fontSize = 12.sp,
                color = Color.Black
            )
        }
    }
}
// ==================== 游戏主界面三个板块组件 ====================

/**
 * 第一板块：宗门信息板块
 * 包含：宗门名称、弟子数量、灵石数量、游戏内时间
 */
@Composable
internal fun SectInfoPanel(
    gameData: GameData?,
    discipleCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.PageBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = gameData?.sectName ?: "青云宗",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            letterSpacing = 0.sp
        )

        Text(
            text = "${gameData?.gameYear ?: 1}年${gameData?.gameMonth ?: 1}月${gameData?.gameDay ?: 1}日",
            fontSize = 12.sp,
            color = Color(0xFF666666),
            fontWeight = FontWeight.Normal
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            InfoItem(
                label = "弟子",
                value = "$discipleCount"
            )

            InfoItem(
                label = "灵石",
                value = "${gameData?.spiritStones ?: 0}"
            )
        }
    }
}

/**
 * 信息项组件 - 纯文字显示
 */
@Composable
internal fun InfoItem(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.Black
        )
    }
}

/**
 * 第二板块：快捷操作板块
 * 包含：世界地图、招募弟子、云游商人、宗门外交、秘境探索
 */
@Composable
internal fun QuickActionPanel(
    viewModel: GameViewModel
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = GameColors.PageBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "快捷操作",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionButton(
                        text = "世界地图",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openWorldMapDialog() }
                    )
                    QuickActionButton(
                        text = "招募弟子",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openRecruitDialog() }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionButton(
                        text = "云游商人",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openMerchantDialog() }
                    )
                    QuickActionButton(
                        text = "宗门外交",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openDiplomacyDialog() }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionButton(
                        text = "秘境探索",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openSecretRealmDialog() }
                    )
                    QuickActionButton(
                        text = "战斗日志",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openBattleLogDialog() }
                    )
                }
            }
        }
    }
}

/**
 * 快捷操作按钮 - 纯文字按钮
 */
@Composable
internal fun QuickActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(GameColors.ButtonBackground)
            .border(1.dp, GameColors.ButtonBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = Color.Black,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 第三板块：宗门消息板块
 * 显示宗门发生的事件
 */
@Composable
internal fun SectMessagePanel(
    events: List<GameEvent>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(12.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "宗门消息",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无消息",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(events.take(20)) { event ->
                        EventMessageItem(event = event)
                        if (event != events.take(20).last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(top = 8.dp),
                                color = Color(0xFFEEEEEE),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 事件消息项 - 纯文字显示
 */
@Composable
internal fun EventMessageItem(event: GameEvent) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = event.message,
                fontSize = 12.sp,
                color = Color.Black,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = event.displayTime,
                fontSize = 12.sp,
                color = Color.Black
            )
        }
    }
}
