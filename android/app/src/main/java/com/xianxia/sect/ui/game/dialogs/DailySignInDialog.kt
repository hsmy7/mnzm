package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.R
import com.xianxia.sect.core.model.DailySignInReward
import com.xianxia.sect.core.model.SignInDayState
import com.xianxia.sect.core.model.SignInState
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.LocalItemSpriteCache
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.getRarityColor
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.theme.GameColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DailySignInPanel(
    viewModel: GameViewModel
) {
    val signInState by viewModel.signInState.collectAsStateWithLifecycle()
    val canClaimToday by viewModel.canClaimToday.collectAsStateWithLifecycle()
    val capacityWarning by viewModel.signInCapacityWarning.collectAsStateWithLifecycle()

    val daysInMonth = remember(signInState.currentMonth, signInState.currentYear) {
        viewModel.getDaysInMonth()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF6EBD5), RoundedCornerShape(4.dp))
    ) {
        // 日历网格
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            SignInCalendarGrid(
                signInState = signInState,
                daysInMonth = daysInMonth,
                getRewardForWeekday = viewModel::getRewardForWeekday,
                getDayState = viewModel::getDayState,
                getWeekdayForDay = viewModel::getWeekdayForDay
            )
        }

        // 签到按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            GameButton(
                text = "签到",
                onClick = { viewModel.claimDailySignIn() },
                enabled = canClaimToday
            )
        }
    }

    // 仓库容量不足提示框（每次点击签到且容量不足时弹出）
    if (capacityWarning != null) {
        StandardPromptDialog(
            onDismissRequest = { viewModel.dismissCapacityWarning() },
            title = "仓库容量不足",
            text = capacityWarning,
            confirmLabel = "知道了",
            onConfirm = { viewModel.dismissCapacityWarning() },
            dismissLabel = null
        )
    }
}

@Composable
private fun SignInCalendarGrid(
    signInState: SignInState,
    daysInMonth: Int,
    getRewardForWeekday: (Int) -> DailySignInReward,
    getDayState: (Int, SignInState) -> SignInDayState,
    getWeekdayForDay: (Int) -> Int
) {
    val spacing = 4.dp

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth()
    ) {
        val availableWidth = maxWidth
        val cellWidth = (availableWidth - spacing * 6) / 7
        val cellHeight = cellWidth + 14.dp

        val days = (1..daysInMonth).toList()
        val rows = days.chunked(7)

        Column(
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            rows.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    row.forEach { dayOfMonth ->
                        val weekday = getWeekdayForDay(dayOfMonth)
                        val reward = getRewardForWeekday(weekday)
                        val dayState = getDayState(dayOfMonth, signInState)

                        SignInDayCard(
                            dayOfMonth = dayOfMonth,
                            reward = reward,
                            state = dayState,
                            cellWidth = cellWidth,
                            cellHeight = cellHeight
                        )
                    }
                    // 末行不满 7 格：补空白占位，保证卡片尺寸与其他行一致
                    repeat(7 - row.size) {
                        Box(modifier = Modifier.size(cellWidth, cellHeight))
                    }
                }
            }
        }
    }
}

@Composable
private fun SignInDayCard(
    dayOfMonth: Int,
    reward: DailySignInReward,
    state: SignInDayState,
    cellWidth: Dp,
    cellHeight: Dp,
    modifier: Modifier = Modifier
) {
    val rarityColor = getRarityColor(reward.rarity)
    val spriteRes = when (reward.type) {
        "spiritStones" -> com.xianxia.sect.ui.components.spiritStoneSpriteRes()
        "storageBag" -> com.xianxia.sect.ui.components.storageBagSpriteRes(reward.rarity)
        "pill" -> com.xianxia.sect.ui.components.pillSpriteRes(reward.rarity)
        "beastMaterial" -> com.xianxia.sect.ui.components.materialSpriteRes(reward.itemName)
        else -> null
    }

    val nameLabelHeight = 14.dp

    val isTodayUnclaimed = state == SignInDayState.TODAY_UNCLAIMED
    val isClaimed = state == SignInDayState.TODAY_CLAIMED || state == SignInDayState.PAST_CLAIMED
    val isMissed = state == SignInDayState.MISSED
    val isOverlayState = isClaimed || isMissed

    val borderWidth = if (isTodayUnclaimed) 3.dp else 2.dp
    val borderColor = if (isTodayUnclaimed) GameColors.Gold else GameColors.Border
    val overlayTextColor = if (isOverlayState) Color.Black else Color.White

    Box(
        modifier = modifier.size(cellWidth, cellHeight),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
        ) {
            // 精灵图区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cellWidth)
                    .background(if (isClaimed || isMissed) Color(0xFFF5F5F5) else rarityColor),
                contentAlignment = Alignment.Center
            ) {
                if (isClaimed) {
                    Text(
                        text = "已领",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                } else if (isMissed) {
                    Text(
                        text = "未领",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = GameColors.Error
                    )
                } else {
                    // 显示精灵图
                    if (spriteRes != null) {
                        val cachedBitmap = LocalItemSpriteCache.current[spriteRes]
                        if (cachedBitmap != null) {
                            Image(
                                bitmap = cachedBitmap,
                                contentDescription = reward.itemName,
                                modifier = Modifier.fillMaxSize().padding(3.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Image(
                                painter = painterResource(id = spriteRes),
                                contentDescription = reward.itemName,
                                modifier = Modifier.fillMaxSize().padding(3.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }

                // 日期数字（左上角，始终显示；已领/未领时用黑色保证可读性）
                Text(
                    text = "$dayOfMonth",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = overlayTextColor,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 3.dp, top = 2.dp)
                )

                // 数量（右下角，始终显示）
                Text(
                    text = "${reward.quantity}",
                    fontSize = 8.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 3.dp, bottom = 2.dp)
                )
            }

            // 名称区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(nameLabelHeight)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = reward.itemName,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
    }
}
