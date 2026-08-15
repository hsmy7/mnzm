package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.util.WATCHABLE_ITEM_TYPES
import com.xianxia.sect.core.util.sortedByWatchedThenRarity
import com.xianxia.sect.core.util.watchKey
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.core.util.normalizeItemType
import com.xianxia.sect.ui.game.components.watchKeyOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.ui.components.BattleParticipantSlot
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.theme.GameColors

@Composable
internal fun BattleResultDialog(
    resultData: BattleResultUIData,
    battleLog: BattleLog?,
    onConfirm: () -> Unit,
    onViewDetail: (BattleLog) -> Unit,
    onDismiss: () -> Unit,
    viewModel: GameViewModel? = null,
    scrimEnabled: Boolean = true
) {
    val resultColor = if (resultData.victory) GameColors.Success else GameColors.Error
    val title = if (resultData.isBeastDefense) {
        if (resultData.victory) "防守胜利" else "防守失败"
    } else {
        if (resultData.victory) "战斗胜利" else "战斗失败"
    }

    val watchedKeys = viewModel?.watchedItemIds?.collectAsStateWithLifecycle()?.value
        ?: emptySet()
    val sortedRewards = remember(resultData.rewards, watchedKeys) {
        sortBattleRewards(items = resultData.rewards, watchedKeys = watchedKeys)
    }
    val sortedLooted = remember(resultData.lootedItems, watchedKeys) {
        sortBattleRewards(items = resultData.lootedItems, watchedKeys = watchedKeys)
    }

    // 阵亡弟子
    val deadMembers = resultData.teamMembers.filter { !it.isAlive }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = title,
        titleColor = resultColor,
        titleFontSize = 22.sp,
        mode = DialogMode.Half,
        scrollableContent = false,
        showCloseButton = false,
        dismissOnClickOutside = false,
        scrimEnabled = scrimEnabled
    ) {
        BattleResultDialogContent(
            resultData = resultData,
            battleLog = battleLog,
            sortedRewards = sortedRewards,
            sortedLooted = sortedLooted,
            deadMembers = deadMembers,
            watchedKeys = watchedKeys,
            viewModel = viewModel,
            onConfirm = onConfirm,
            onViewDetail = onViewDetail
        )
    }
}

/** 战利品排序（BattleResultDialog 拆分）：关注优先 + 稀有度降序 */
private fun sortBattleRewards(
    items: List<BattleRewardItem>,
    watchedKeys: Set<String>
): List<BattleRewardItem> = items.sortedByWatchedThenRarity(
    watchedKeys,
    keyOf = { reward ->
        val type = normalizeItemType(reward.type)
        if (type in WATCHABLE_ITEM_TYPES) watchKey(type, reward.name) else null
    },
    rarityOf = { it.rarity },
    nameOf = { it.name }
)

/** 对话框内容（BattleResultDialog 拆分）：滚动战报区 + 底部按钮 + 战利品详情弹窗 */
// 拆分聚合:平铺参数搬移自原公共函数
@Suppress("LongParameterList")
@Composable
private fun BattleResultDialogContent(
    resultData: BattleResultUIData,
    battleLog: BattleLog?,
    sortedRewards: List<BattleRewardItem>,
    sortedLooted: List<BattleRewardItem>,
    deadMembers: List<BattleLogMember>,
    watchedKeys: Set<String>,
    viewModel: GameViewModel?,
    onConfirm: () -> Unit,
    onViewDetail: (BattleLog) -> Unit
) {
    var showDetail by remember { mutableStateOf(false) }
    var detailReward by remember { mutableStateOf<BattleRewardItem?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 8.dp)
        ) {
            BattleResultLazyContent(
                teamMembers = resultData.teamMembers,
                lootedItems = resultData.lootedItems,
                deadMembers = deadMembers,
                rewards = resultData.rewards,
                sortedLooted = sortedLooted,
                sortedRewards = sortedRewards,
                watchedKeys = watchedKeys,
                onRewardLongPress = { reward ->
                    detailReward = reward
                    showDetail = true
                }
            )
        }

        // 底部按钮
        BattleResultBottomButtons(
            isBeastDefense = resultData.isBeastDefense,
            battleLog = battleLog,
            onConfirm = onConfirm,
            onViewDetail = onViewDetail
        )
    }

    if (showDetail && detailReward != null) {
        val reward = checkNotNull(detailReward)
        ItemDetailDialog(
            item = MerchantItem(
                id = reward.itemId,
                name = reward.name,
                type = normalizeItemType(reward.type),
                rarity = reward.rarity,
                quantity = reward.quantity,
                price = 0L
            ),
            onDismiss = {
                showDetail = false
                detailReward = null
            },
            viewModel = viewModel
        )
    }
}

/** 滚动战报区内容（BattleResultDialog 拆分）：出战弟子 / 被掠夺 / 阵亡 / 战利品 */
// 拆分聚合:平铺参数搬移自原公共函数
// 拆分命名:与调用点语义一致
@Suppress("LongParameterList", "FunctionNaming")
private fun LazyListScope.BattleResultLazyContent(
    teamMembers: List<BattleLogMember>,
    lootedItems: List<BattleRewardItem>,
    deadMembers: List<BattleLogMember>,
    rewards: List<BattleRewardItem>,
    sortedLooted: List<BattleRewardItem>,
    sortedRewards: List<BattleRewardItem>,
    watchedKeys: Set<String>,
    onRewardLongPress: (BattleRewardItem) -> Unit
) {
    // 出战弟子
    item {
        Text(
            text = "出战弟子",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    item { BattleMemberGrid(members = teamMembers) }

    // 被掠夺物品（防守失败时可见）
    if (lootedItems.isNotEmpty()) {
        BattleResultSectionHeader(title = "被掠夺物品", color = GameColors.Error)
        item {
            BattleRewardCardRow(
                items = sortedLooted,
                watchedKeys = watchedKeys,
                keyPrefix = "looted",
                contentType = "looted_item",
                onLongPress = onRewardLongPress
            )
        }
    }

    // 阵亡弟子（防守场景正下方显示）
    if (deadMembers.isNotEmpty()) {
        BattleResultSectionHeader(title = "阵亡弟子", color = Color.Black)
        item {
            BattleMemberGrid(members = deadMembers, forceDead = true)
        }
    }

    // 战利品
    if (rewards.isNotEmpty()) {
        BattleResultSectionHeader(title = "战利品", color = Color.Black)
        item {
            BattleRewardCardRow(
                items = sortedRewards,
                watchedKeys = watchedKeys,
                keyPrefix = "reward",
                contentType = "reward",
                onLongPress = onRewardLongPress
            )
        }
    } else {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "无战利品",
                fontSize = 11.sp,
                color = Color.Black
            )
        }
    }
}

/** 分区标题项（BattleResultDialog 拆分）：上间距 16dp + 标题 + 下间距 8dp */
// 拆分命名:与调用点语义一致
@Suppress("FunctionNaming")
private fun LazyListScope.BattleResultSectionHeader(title: String, color: Color) {
    item {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** 参战弟子网格（BattleResultDialog 拆分）：每行 4 人，阵亡态强制 0 血/阵亡显示 */
@Composable
private fun BattleMemberGrid(
    members: List<BattleLogMember>,
    forceDead: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        members.chunked(4).forEach { rowMembers ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(
                    8.dp,
                    Alignment.CenterHorizontally
                )
            ) {
                rowMembers.forEach { member ->
                    BattleParticipantSlot(
                        name = member.name,
                        realmName = member.realmName,
                        hp = if (forceDead) 0 else member.hp,
                        maxHp = member.maxHp,
                        isAlive = if (forceDead) false else member.isAlive,
                        portraitRes = member.portraitRes
                    )
                }
                repeat(4 - rowMembers.size) {
                    Spacer(
                        Modifier.width(52.dp).height(88.dp)
                    )
                }
            }
        }
    }
}

/** 战利品卡片行（BattleResultDialog 拆分）：横向滚动物品卡，长按查看详情 */
@Composable
private fun BattleRewardCardRow(
    items: List<BattleRewardItem>,
    watchedKeys: Set<String>,
    keyPrefix: String,
    contentType: String,
    onLongPress: (BattleRewardItem) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(
            items,
            key = { index, item -> item.itemId.ifBlank { "${keyPrefix}_$index" } },
            contentType = { _, _ -> contentType }
        ) { _, item ->
            UnifiedItemCard(
                data = ItemCardData(
                    id = item.itemId,
                    name = item.name,
                    rarity = item.rarity,
                    quantity = item.quantity,
                    type = item.type,
                    isPill = item.type == "pill",
                    isManual = item.type == "manual",
                    isMaterial = item.type == "material",
                    spiritStoneGrade = if (item.type == "spiritStones") SpiritStoneGrade.LOW else null
                ),
                isFollowed = watchKeyOf(item)?.let { it in watchedKeys } ?: false,
                onLongPress = { onLongPress(item) }
            )
        }
    }
}

/** 底部按钮（BattleResultDialog 拆分）：防守=知道了；进攻=战斗详情 + 确定 */
@Composable
private fun BattleResultBottomButtons(
    isBeastDefense: Boolean,
    battleLog: BattleLog?,
    onConfirm: () -> Unit,
    onViewDetail: (BattleLog) -> Unit
) {
    HorizontalDivider(color = GameColors.SurfaceLightGray, thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBeastDefense) {
            GameButton(
                text = "知道了",
                onClick = onConfirm
            )
        } else {
            GameButton(
                text = "战斗详情",
                onClick = {
                    battleLog?.let { onViewDetail(it) }
                }
            )
            Spacer(modifier = Modifier.width(16.dp))
            GameButton(
                text = "确定",
                onClick = onConfirm
            )
        }
    }
}
