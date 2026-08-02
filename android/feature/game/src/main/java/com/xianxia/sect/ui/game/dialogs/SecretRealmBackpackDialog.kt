package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedGameDialog

/**
 * 远古秘境探索背包弹窗——显示探索过程中获得的物品（结束时统一结算入宗门仓库）。
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
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "暂无所得", fontSize = 13.sp, color = Color.Black)
                }
                return@UnifiedGameDialog
            }

            BackpackSection("灵石", "${backpack.spiritStones}")
            BackpackSection("装备", "×${backpack.equipment.size}")
            BackpackSection("功法", "×${backpack.manuals.size}")
            BackpackSection("丹药", "×${backpack.pills.size}")
            BackpackSection("材料", "×${backpack.materials.size}")
            BackpackSection("草药", "×${backpack.herbs.size}")

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

@Composable
private fun BackpackSection(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            color = Color.Black
        )
    }
}
