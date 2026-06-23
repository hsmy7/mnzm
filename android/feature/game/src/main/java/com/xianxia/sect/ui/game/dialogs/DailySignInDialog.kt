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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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

            // ========== 布局常量与卡片尺寸计算 ==========
            val nameH = 14.dp
            val vPad = 8.dp
            val divW = 1.dp
            val spacingRatio = 0.12f
            val calWeight = 0.8f
            val milWeight = 0.2f
            val calHPad = 4.dp   // 日历水平内边距（每侧）
            val milPadH = 6.dp   // 里程碑水平内边距

            // 各区可用宽度
            val calSectionW = (totalWidth - divW) * calWeight
            val milSectionW = (totalWidth - divW) * milWeight

            // === 第1步：高度约束求最小卡片 ===
            // 4行卡片 + 3条间隙 + 垂直内边距 = totalHeight
            var cardW = (
                (totalHeight - nameH * 4f - vPad) / (4f + 3f * spacingRatio)
            ).coerceAtLeast(24.dp)
            var gap = (cardW * spacingRatio).coerceIn(4.dp, 14.dp)

            // === 第2步：尝试放大卡片撑满日历80%区域 ===
            // 若当前卡片太窄导致日历左右边距过大，在高度允许时放大
            val calGridW_target = cardW * 7f + gap * 6f
            if (calGridW_target + calHPad * 2f < calSectionW) {
                // 按宽度反算更大的卡片
                val widerW = (calSectionW - calHPad * 2f) / (7f + 6f * spacingRatio)
                val widerGap = (widerW * spacingRatio).coerceIn(4.dp, 14.dp)
                val widerTotalH = (widerW + nameH) * 4f + widerGap * 3f + vPad
                if (widerTotalH <= totalHeight && widerW > cardW) {
                    cardW = widerW
                    gap = widerGap
                }
            }

            // === 第3步：宽度约束——日历不能超出80%区域 ===
            val calGridW = cardW * 7f + gap * 6f
            if (calGridW + calHPad * 2f > calSectionW) {
                cardW = (
                    (calSectionW - calHPad * 2f - gap * 6f) / 7f
                ).coerceAtLeast(24.dp)
                gap = (cardW * spacingRatio).coerceIn(4.dp, 14.dp)
                if (gap != cardW * spacingRatio) {
                    cardW = (
                        (calSectionW - calHPad * 2f - gap * 6f) / 7f
                    ).coerceAtLeast(24.dp)
                }
            }

            // === 第4步：宽度约束——里程碑不能超出20%区域 ===
            val milContentW = 38.dp + 6.dp + cardW
            if (milContentW + milPadH * 2f > milSectionW) {
                cardW = (
                    milSectionW - milPadH * 2f - 38.dp - 6.dp
                ).coerceAtLeast(24.dp)
                gap = (cardW * spacingRatio).coerceIn(4.dp, 14.dp)
                val calGridW2 = cardW * 7f + gap * 6f
                if (calGridW2 + calHPad * 2f > calSectionW) {
                    cardW = (
                        (calSectionW - calHPad * 2f - gap * 6f) / 7f
                    ).coerceAtLeast(24.dp)
                }
            }

            val cardH = cardW + nameH
            val totalRowH = cardH * 4f + gap * 3f + vPad

            // ========== 名称字体大小（按5字最长名称动态计算） ==========
            val density = LocalDensity.current
            val nameFontSize = remember(cardW) {
                val avail = (cardW - 8.dp) / 5f
                with(density) { avail.coerceIn(7.dp, 12.dp).toSp() }
            }

            // 日历网格内容宽度（不含外层padding）
            val calGridContentW = cardW * 7f + gap * 6f

            // ========== 布局：日历80% + 分隔线 + 里程碑20% ==========
            // 两部分各自独立居中，各有自己的左右等距
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.height(totalRowH)
                ) {
                // 日历区域（80%宽度，内容独立居中 → 左右等距）
                Box(
                    modifier = Modifier
                        .weight(calWeight)
                        .height(totalRowH),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .width(calGridContentW + calHPad * 2f)
                            .height(totalRowH)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = calHPad, vertical = vPad / 2f)
                    ) {
                        SignInCalendarGrid(
                            signInState = signInState,
                            daysInMonth = daysInMonth,
                            getRewardForWeekday = viewModel::getRewardForWeekday,
                            getDayState = viewModel::getDayState,
                            getWeekdayForDay = viewModel::getWeekdayForDay,
                            cellWidth = cardW,
                            cellHeight = cardH,
                            cellSpacing = gap,
                            nameFontSize = nameFontSize,
                            onLongPress = { item -> detailItem = item }
                        )
                    }
                }

                // 分隔线
                Box(
                    modifier = Modifier
                        .height(totalRowH)
                        .width(divW)
                        .background(GameColors.Divider)
                )

                // 里程碑区域（20%宽度，内容独立居中 → 左右等距）
                Box(
                    modifier = Modifier
                        .weight(milWeight)
                        .height(totalRowH),
                    contentAlignment = Alignment.Center
                ) {
                    SignInMilestoneRewardsPanel(
                        milestones = viewModel.milestoneRewards,
                        claimedDaysCount = claimedDaysCount,
                        claimedMilestones = claimedMilestones,
                        cardSize = cardW,
                        cardHeight = cardH,
                        cardSpacing = gap,
                        labelSpacing = 6.dp,
                        dayLabelWidth = 38.dp,
                        nameFontSize = nameFontSize,
                        onLongPress = { item -> detailItem = item }
                    )
                }
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
    nameFontSize: TextUnit,
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
                        nameFontSize = nameFontSize,
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
    nameFontSize: TextUnit,
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
            showPlaceholderText = !isRandomReward,
            nameFontSize = nameFontSize
        )

        // 状态覆盖层（仅覆盖精灵图，不覆盖边框）
        if (isClaimed || isMissed || isRandomReward) {
            val borderW = if (isTodayUnclaimed) 3.dp else 2.dp
            Box(
                modifier = Modifier
                    .width(spriteWidth - borderW * 2f)
                    .height(spriteHeight - borderW)
                    .align(Alignment.TopCenter)
                    .offset(y = borderW)
                    .clip(RoundedCornerShape(
                        topStart = (6.dp - borderW).coerceAtLeast(0.dp),
                        topEnd = (6.dp - borderW).coerceAtLeast(0.dp)
                    ))
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

/** 签到里程碑奖励面板（日历右侧，4行1列，间距一致） */
@Composable
private fun SignInMilestoneRewardsPanel(
    milestones: List<MilestoneReward>,
    claimedDaysCount: Int,
    claimedMilestones: List<Int>,
    cardSize: Dp,
    cardHeight: Dp,
    cardSpacing: Dp,
    labelSpacing: Dp,
    dayLabelWidth: Dp,
    nameFontSize: TextUnit,
    onLongPress: (Any) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(cardSpacing)
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
                    nameFontSize = nameFontSize,
                    onLongPress = onLongPress
                )
            }
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
    nameFontSize: TextUnit,
    onLongPress: (Any) -> Unit
) {
    val detailItem = buildDetailItem(
        milestone.itemName, milestone.quantity, milestone.type, milestone.rarity
    )
    val spriteWidth = cardHeight               // 卡片实际宽度（正方形）
    val nameLabelHeight = 14.dp
    val spriteHeight = cardHeight - nameLabelHeight

    Row(
        verticalAlignment = Alignment.CenterVertically
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
                showQuantity = true,
                nameFontSize = nameFontSize
            )

            // 已领覆盖层（仅覆盖精灵图，不覆盖边框）
            if (isClaimed) {
                val borderW = if (isReached) 3.dp else 2.dp
                Box(
                    modifier = Modifier
                        .width(spriteWidth - borderW * 2f)
                        .height(spriteHeight - borderW)
                        .align(Alignment.TopCenter)
                        .offset(y = borderW)
                        .clip(RoundedCornerShape(
                            topStart = (6.dp - borderW).coerceAtLeast(0.dp),
                            topEnd = (6.dp - borderW).coerceAtLeast(0.dp)
                        ))
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

