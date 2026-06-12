package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.model.DailySignInReward
import com.xianxia.sect.core.model.MilestoneReward
import com.xianxia.sect.core.model.SignInDayState
import com.xianxia.sect.core.model.SignInState
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.LocalItemSpriteCache
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.getRarityColor
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.ui.components.spiritStoneSpriteRes
import com.xianxia.sect.ui.components.storageBagSpriteRes
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.tabs.SpiritStoneInfo
import com.xianxia.sect.ui.theme.GameColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DailySignInPanel(
    viewModel: GameViewModel
) {
    val signInState by viewModel.signInState.collectAsStateWithLifecycle()
    val canClaimToday by viewModel.canClaimToday.collectAsStateWithLifecycle()
    val capacityWarning by viewModel.signInCapacityWarning.collectAsStateWithLifecycle()
    val claimedDaysCount by viewModel.claimedDaysCount.collectAsStateWithLifecycle()
    val claimedMilestones by viewModel.claimedMilestones.collectAsStateWithLifecycle()

    val daysInMonth = remember(signInState.currentMonth, signInState.currentYear) {
        viewModel.getDaysInMonth()
    }

    var detailItem by remember { mutableStateOf<Any?>(null) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 日历 + 里程碑奖励（以高度为基准统一卡片尺寸，保证里程碑 4 行完整可见）
        BoxWithConstraints(
            modifier = Modifier.weight(1f)
        ) {
            val totalWidth = maxWidth
            val totalHeight = maxHeight
            val minCellSpacing = 4.dp
            val calendarHPadding = 16.dp       // 8dp * 2
            val dividerWidth = 1.dp
            val panelHPadding = 12.dp           // 6dp * 2
            val dayLabelWidth = 38.dp           // "28天" 12sp 金色文字宽度
            val cardGap = 4.dp                  // 标签与卡片间距
            val nameLabelHeight = 14.dp
            val panelVerticalPadding = 16.dp    // 8dp * 2
            val rowVerticalPadding = 4.dp       // 每行 2dp * 2

            // 基于高度计算：4 行里程碑均匀分布在可用高度中
            val usableHeight = totalHeight - panelVerticalPadding
            val cardSizeFromHeight = ((usableHeight - rowVerticalPadding * 4) / 4
                - nameLabelHeight).coerceAtLeast(24.dp)

            // 基于宽度计算（使用最小间距）：7 格日历 + 1 格里程碑
            val widthOverheadMin = minCellSpacing * 6 + calendarHPadding +
                dividerWidth + panelHPadding + dayLabelWidth + cardGap
            val cardSizeFromWidth = ((totalWidth - widthOverheadMin) / 8).coerceAtLeast(24.dp)

            // 取两者较小值，保证高度和宽度都能容纳
            val cellWidth = minOf(cardSizeFromWidth, cardSizeFromHeight)
            val cellHeight = cellWidth + nameLabelHeight

            // 间距常量（与 cellWidth 无关的部分）
            val fixedOverhead = calendarHPadding + dividerWidth +
                panelHPadding + dayLabelWidth + cardGap

            // 最小间距下的内容总宽度
            val minContentWidth = cellWidth * 8 + minCellSpacing * 6 + fixedOverhead

            // 左右空白最多占 20%（内容占 ≥80%），多余空间用于增大卡片间距
            val targetContentWidth =
                (totalWidth * 0.8f).coerceIn(minContentWidth, totalWidth)
            val adjustedCellSpacing = if (minContentWidth < targetContentWidth) {
                val extraWidth = targetContentWidth - minContentWidth
                minCellSpacing + extraWidth / 6
            } else {
                minCellSpacing
            }

            // 各部分宽度（使用调整后的间距）
            val calendarContentWidth = cellWidth * 7 + adjustedCellSpacing * 6
            val calendarColumnWidth = calendarContentWidth + calendarHPadding
            val milestonePanelWidth = dayLabelWidth + cardGap + cellWidth + panelHPadding

            // 居中显示，留出左右空白
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Row {
                    // 日历网格
                    Column(
                        modifier = Modifier
                            .width(calendarColumnWidth)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        SignInCalendarGrid(
                            signInState = signInState,
                            daysInMonth = daysInMonth,
                            getRewardForWeekday = viewModel::getRewardForWeekday,
                            getDayState = viewModel::getDayState,
                            getWeekdayForDay = viewModel::getWeekdayForDay,
                            cellWidth = cellWidth,
                            cellHeight = cellHeight,
                            cellSpacing = adjustedCellSpacing,
                            onLongPress = { item -> detailItem = item }
                        )
                    }

                    // 分隔线
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(dividerWidth)
                            .background(GameColors.Divider)
                    )

                    // 里程碑奖励面板
                    SignInMilestoneRewardsPanel(
                        milestones = viewModel.milestoneRewards,
                        claimedDaysCount = claimedDaysCount,
                        claimedMilestones = claimedMilestones,
                        cardSize = cellWidth,
                        cardHeight = cellHeight,
                        labelSpacing = cardGap,
                        dayLabelWidth = dayLabelWidth,
                        onLongPress = { item -> detailItem = item }
                    )
                }
            }
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

    // 物品详情弹窗（长按物品卡片触发）
    detailItem?.let { item ->
        ItemDetailDialog(
            item = item,
            onDismiss = { detailItem = null }
        )
    }
}

@Composable
private fun SignInCalendarGrid(
    signInState: SignInState,
    daysInMonth: Int,
    getRewardForWeekday: (Int) -> DailySignInReward,
    getDayState: (Int, SignInState) -> SignInDayState,
    getWeekdayForDay: (Int) -> Int,
    cellWidth: Dp,
    cellHeight: Dp,
    cellSpacing: Dp,
    onLongPress: (Any) -> Unit
) {
    val days = (1..daysInMonth).toList()
    val rows = days.chunked(7)

    Column(
        verticalArrangement = Arrangement.spacedBy(cellSpacing)
    ) {
        rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(cellSpacing)
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
                        cellHeight = cellHeight,
                        onLongPress = { item ->
                            onLongPress(item)
                        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SignInDayCard(
    dayOfMonth: Int,
    reward: DailySignInReward,
    state: SignInDayState,
    cellWidth: Dp,
    cellHeight: Dp,
    modifier: Modifier = Modifier,
    onLongPress: (Any) -> Unit = {}
) {
    val rarityColor = getRarityColor(reward.rarity)
    val spriteRes = when (reward.type) {
        "spiritStones" -> spiritStoneSpriteRes()
        "storageBag" -> storageBagSpriteRes(reward.rarity)
        "pill" -> com.xianxia.sect.ui.components.pillSpriteRes(reward.rarity)
        "beastMaterial" -> com.xianxia.sect.ui.components.materialSpriteRes(reward.itemName)
        else -> null  // randomMaterial / randomSeed / randomPill 使用 ? 文本代替精灵图
    }
    val isRandomReward = reward.type in setOf("randomMaterial", "randomSeed", "randomPill", "randomHerb")

    val detailItem = buildDetailItem(reward.itemName, reward.quantity, reward.type, reward.rarity)

    val nameLabelHeight = 14.dp

    val isTodayUnclaimed = state == SignInDayState.TODAY_UNCLAIMED
    val isClaimed = state == SignInDayState.TODAY_CLAIMED || state == SignInDayState.PAST_CLAIMED
    val isMissed = state == SignInDayState.MISSED
    val isOverlayState = isClaimed || isMissed

    val borderWidth = if (isTodayUnclaimed) 3.dp else 2.dp
    val borderColor = if (isTodayUnclaimed) GameColors.Gold else GameColors.Border
    val overlayTextColor = if (isOverlayState) Color.Black else Color.White

    Box(
        modifier = modifier
            .size(cellWidth, cellHeight)
            .then(
                if (detailItem != null) {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = { onLongPress(detailItem) }
                    )
                } else Modifier
            ),
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
                    // 显示精灵图（随机物品显示 ?）
                    if (isRandomReward) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "?",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    } else if (spriteRes != null) {
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
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 1.dp)
                )
            }
        }
    }
}

/** 签到里程碑奖励面板（日历右侧，占满高度均匀分布） */
@Composable
private fun SignInMilestoneRewardsPanel(
    milestones: List<MilestoneReward>,
    claimedDaysCount: Int,
    claimedMilestones: List<Int>,
    cardSize: Dp,
    cardHeight: Dp,
    labelSpacing: Dp,
    dayLabelWidth: Dp,
    onLongPress: (Any) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        milestones.forEach { milestone ->
            val isClaimed = milestone.day in claimedMilestones
            val isReached = claimedDaysCount >= milestone.day && !isClaimed
            MilestoneRewardRow(
                milestone = milestone,
                isClaimed = isClaimed,
                isReached = isReached,
                cardSize = cardSize,
                cardHeight = cardHeight,
                labelSpacing = labelSpacing,
                dayLabelWidth = dayLabelWidth,
                onLongPress = onLongPress
            )
        }
    }
}

/** 单行里程碑奖励：金色天数标签（左） + 物品卡片（右） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MilestoneRewardRow(
    milestone: MilestoneReward,
    isClaimed: Boolean,
    isReached: Boolean,
    cardSize: Dp,
    cardHeight: Dp,
    labelSpacing: Dp,
    dayLabelWidth: Dp,
    onLongPress: (Any) -> Unit
) {
    val rarityColor = getRarityColor(milestone.rarity)
    val spriteRes = when (milestone.type) {
        "spiritStones" -> spiritStoneSpriteRes()
        "storageBag" -> storageBagSpriteRes(milestone.rarity)
        else -> null
    }

    val detailItem = buildDetailItem(
        milestone.itemName, milestone.quantity, milestone.type, milestone.rarity
    )

    val borderColor = if (isReached) GameColors.Gold else GameColors.Border
    val borderWidth = if (isReached) 3.dp else 2.dp
    val nameLabelHeight = 14.dp

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        // 金色天数标签（固定宽度保证卡片对齐）
        Box(
            modifier = Modifier.width(dayLabelWidth),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${milestone.day}天",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = GameColors.Error,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.width(labelSpacing))

        // 物品卡片（尺寸与日历卡片一致）
        Box(
            modifier = Modifier
                .size(cardSize, cardHeight)
                .then(
                    if (detailItem != null) {
                        Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            onLongClick = { onLongPress(detailItem) }
                        )
                    } else Modifier
                ),
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
                        .height(cardSize)
                        .background(if (isClaimed) Color(0xFFF5F5F5) else rarityColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (isClaimed) {
                        Text(
                            text = "已领",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                    } else if (spriteRes != null) {
                        val cachedBitmap = LocalItemSpriteCache.current[spriteRes]
                        if (cachedBitmap != null) {
                            Image(
                                bitmap = cachedBitmap,
                                contentDescription = milestone.itemName,
                                modifier = Modifier.fillMaxSize().padding(3.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Image(
                                painter = painterResource(id = spriteRes),
                                contentDescription = milestone.itemName,
                                modifier = Modifier.fillMaxSize().padding(3.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    // 数量（右下角）
                    Text(
                        text = formatRewardQuantity(milestone.quantity),
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
                        text = milestone.itemName,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 1.dp)
                    )
                }
            }
        }
    }
}

/** 大数值奖励格式化（如 50000 → "5万"） */
private fun formatRewardQuantity(quantity: Int): String {
    return when {
        quantity >= 10000 && quantity % 10000 == 0 -> "${quantity / 10000}万"
        quantity >= 1000 && quantity % 1000 == 0 -> "${quantity / 1000}千"
        else -> "$quantity"
    }
}

/** 将签到奖励转换为 ItemDetailDialog 可显示的物品类型 */
private fun buildDetailItem(
    itemName: String,
    quantity: Int,
    type: String,
    rarity: Int
): Any? {
    return when (type) {
        "spiritStones" -> SpiritStoneInfo(quantity.toLong())
        "storageBag" -> StorageBag(
            id = "",
            name = itemName,
            rarity = rarity,
            quantity = quantity
        )
        "pill" -> {
            val template = ItemDatabase.getPillByName(itemName)
            if (template != null) {
                ItemDatabase.createPillFromTemplate(template, quantity)
            } else {
                null
            }
        }
        "randomMaterial" -> Pill(
            name = itemName,
            rarity = rarity,
            description = "可随机获得${itemName}",
            quantity = quantity
        )
        "randomSeed" -> Pill(
            name = itemName,
            rarity = rarity,
            description = "可随机获得${itemName}",
            quantity = quantity
        )
        "randomHerb" -> Pill(
            name = itemName,
            rarity = rarity,
            description = "可随机获得${itemName}",
            quantity = quantity
        )
        "randomPill" -> Pill(
            name = itemName,
            rarity = rarity,
            description = "可随机获得${itemName}",
            quantity = quantity
        )
        "beastMaterial" -> Pill(
            name = itemName,
            rarity = rarity,
            description = "妖兽材料：${itemName}",
            quantity = quantity
        )
        else -> null
    }
}

