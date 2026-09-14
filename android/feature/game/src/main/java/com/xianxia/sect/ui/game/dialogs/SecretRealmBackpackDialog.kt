package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.toItemCardData

/**
 * 远古秘境探索背包弹窗——以物品卡片展示探索过程中获得的物品（结束时统一结算入宗门仓库）。
 */
@Composable
internal fun SecretRealmBackpackDialog(
    backpack: SecretRealmBackpack,
    onDismiss: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "探索背包",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start
        ) {
            if (backpack.spiritStones == 0L && backpack.totalItemCount == 0) {
                SecretRealmBackpackEmptyHint()
                return@UnifiedGameDialog
            }

            // 物品卡片网格：灵石一张卡片 + 六类物品逐件一张卡片（品阶色边框 + 精灵图 + 数量）
            SecretRealmBackpackItemGrid(backpack = backpack)

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "探索结束时，背包中的物品将自动放入宗门仓库（仓库满则转为邮件发放）。",
                fontSize = 10.sp,
                color = Color(0xFF757575)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** 空背包提示 */
@Composable
private fun SecretRealmBackpackEmptyHint() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "暂无所得", fontSize = 13.sp, color = Color.Black)
    }
}

/** 背包物品网格：灵石 + 六类物品逐件卡片 */
@Composable
private fun SecretRealmBackpackItemGrid(backpack: SecretRealmBackpack) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        if (backpack.spiritStones > 0L) {
            BackpackRewardItemCard(
                itemName = "灵石", itemType = "spiritStones", rarity = 1,
                quantity = backpack.spiritStones.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        }
        backpack.equipment.forEach { item ->
            BackpackRewardItemCard(
                itemName = item.name, itemType = "equipment",
                rarity = item.rarity, quantity = item.quantity
            )
        }
        backpack.manuals.forEach { item ->
            BackpackRewardItemCard(
                itemName = item.name, itemType = "manual",
                rarity = item.rarity, quantity = item.quantity
            )
        }
        backpack.pills.forEach { item ->
            BackpackRewardItemCard(
                itemName = item.name, itemType = "pill",
                rarity = item.rarity, quantity = item.quantity
            )
        }
        backpack.materials.forEach { item ->
            BackpackRewardItemCard(
                itemName = item.name, itemType = "material",
                rarity = item.rarity, quantity = item.quantity
            )
        }
        backpack.herbs.forEach { item ->
            BackpackRewardItemCard(
                itemName = item.name, itemType = "herb",
                rarity = item.rarity, quantity = item.quantity
            )
        }
        backpack.seeds.forEach { item ->
            BackpackRewardItemCard(
                itemName = item.name, itemType = "seed",
                rarity = item.rarity, quantity = item.quantity
            )
        }
    }
}

/** 单张背包物品卡片 */
@Composable
private fun BackpackRewardItemCard(
    itemName: String,
    itemType: String,
    rarity: Int,
    quantity: Int
) {
    UnifiedItemCard(
        data = RewardCardItem(
            itemName = itemName,
            itemType = itemType,
            rarity = rarity,
            quantity = quantity
        ).toItemCardData()
    )
}
