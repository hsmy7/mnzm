package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DailySignInReward
import com.xianxia.sect.core.model.MilestoneReward
import com.xianxia.sect.core.model.SignInDayState
import com.xianxia.sect.core.model.SignInState
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.getRarityColor
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

            // 固定常量
            val nameLabelHeight = 14.dp          // 名称栏高度
            val calendarHPadding = 16.dp         // 日历左右内边距 (8+8)
            val dividerWidth = 1.dp              // 分隔线宽度
            val panelHPadding = 12.dp            // 里程碑左右内边距 (6+6)
            val dayLabelWidth = 38.dp            // "28天" 标签宽度
            val labelToCardGap = 6.dp            // 标签与卡片间距

            // 动态计算：卡片大小 + 间距，填满屏幕宽度
            // 8 个卡片 + 6 个间距，间距 = 卡片宽 × 12%
            val spacingRatio = 0.12f
            val fixedWidthOverhead = calendarHPadding + dividerWidth +
                panelHPadding + dayLabelWidth + labelToCardGap
            val availableWidth = totalWidth - fixedWidthOverhead

            // 第一次：宽度优先计算
            var cardWidth = (availableWidth / (8 + 6 * spacingRatio))
                .coerceAtLeast(24.dp)
            var cardSpacing = (cardWidth * spacingRatio)
                .coerceIn(4.dp, 14.dp)

            if (cardSpacing != cardWidth * spacingRatio) {
                cardWidth = ((availableWidth - cardSpacing * 6) / 8)
                    .coerceAtLeast(24.dp)
            }

            // 高度约束：4 行里程碑必须完整显示
            // SpaceEvenly 至少需要 4*行高 + 少量间隙
            val panelRowPadding = 4.dp
            val minGapPerRow = 2.dp  // SpaceEvenly 最小间隙
            val maxCardHeight = (totalHeight - panelRowPadding * 4 - minGapPerRow * 5) / 4
            val maxCardWidthFromHeight = (maxCardHeight - nameLabelHeight)
                .coerceAtLeast(24.dp)
            if (cardWidth > maxCardWidthFromHeight) {
                cardWidth = maxCardWidthFromHeight
                cardSpacing = (cardWidth * spacingRatio).coerceIn(4.dp, 14.dp)
            }
            val cardHeight = cardWidth + nameLabelHeight

            // 各部分宽度
            val calendarContentWidth = cardWidth * 7 + cardSpacing * 6
            val calendarColumnWidth = calendarContentWidth + calendarHPadding
            val milestonePanelWidth = panelHPadding + dayLabelWidth +
                labelToCardGap + cardWidth

            // 居中显示
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
                            cellWidth = cardWidth,
                            cellHeight = cardHeight,
                            cellSpacing = cardSpacing,
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
                        cardSize = cardWidth,
                        cardHeight = cardHeight,
                        labelSpacing = labelToCardGap,
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
    val isRandomReward = reward.type in setOf("randomMaterial", "randomSeed", "randomPill", "randomHerb")
    val detailItem = buildDetailItem(reward.itemName, reward.quantity, reward.type, reward.rarity)

    val isTodayUnclaimed = state == SignInDayState.TODAY_UNCLAIMED
    val isClaimed = state == SignInDayState.TODAY_CLAIMED || state == SignInDayState.PAST_CLAIMED
    val isMissed = state == SignInDayState.MISSED
    val isOverlayState = isClaimed || isMissed

    val overlayTextColor = if (isOverlayState) Color.Black else Color.White
    // 卡片是正方形 (size = cellHeight)，精灵图区域 = 全高 - 名称栏
    val spriteWidth = cellHeight              // 卡片实际宽度
    val nameLabelHeight = 14.dp
    val spriteHeight = cellHeight - nameLabelHeight

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
        UnifiedItemCard(
            data = ItemCardData(
                name = if (isRandomReward) reward.itemName.removePrefix("随机") else reward.itemName,
                rarity = reward.rarity,
                quantity = reward.quantity,
                isSpiritStone = reward.type == "spiritStones",
                isBag = reward.type == "storageBag",
                isPill = reward.type == "pill",
                isMaterial = reward.type == "beastMaterial"
            ),
            size = cellHeight,
            isSelected = isTodayUnclaimed,
            selectedBorderColor = GameColors.Gold,
            showQuantity = true,
            showPlaceholderText = !isRandomReward
        )

        // 状态覆盖层（尺寸与卡片精灵图一致，随卡片缩放）
        if (isClaimed || isMissed || isRandomReward) {
            Box(
                modifier = Modifier
                    .width(spriteWidth)
                    .height(spriteHeight)
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .background(if (isOverlayState) Color(0xFFF5F5F5) else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                if (isOverlayState || isRandomReward) {
                    Text(
                        text = when {
                            isClaimed -> "已领"
                            isMissed -> "未领"
                            else -> "?"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isClaimed -> Color(0xFF4CAF50)
                            isMissed -> GameColors.Error
                            else -> Color.White
                        }
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
    val detailItem = buildDetailItem(
        milestone.itemName, milestone.quantity, milestone.type, milestone.rarity
    )
    val spriteWidth = cardHeight               // 卡片实际宽度（正方形）
    val nameLabelHeight = 14.dp
    val spriteHeight = cardHeight - nameLabelHeight

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
            UnifiedItemCard(
                data = ItemCardData(
                    name = milestone.itemName,
                    rarity = milestone.rarity,
                    quantity = milestone.quantity,
                    isSpiritStone = milestone.type == "spiritStones",
                    isBag = milestone.type == "storageBag"
                ),
                size = cardHeight,
                isSelected = isReached,
                selectedBorderColor = GameColors.Gold,
                showQuantity = true
            )

            // 已领覆盖层（仅覆盖精灵图区域，扣除边框）
            if (isClaimed) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(spriteHeight)
                        .align(Alignment.TopCenter)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(Color(0xFFF5F5F5)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "已领",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
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

