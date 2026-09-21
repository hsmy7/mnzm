package com.xianxia.sect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.data.cloud.CloudSaveEntry
import com.xianxia.sect.ui.model.SaveSelectMode
import java.text.SimpleDateFormat
import java.util.Date

// ── 云端槽位存档卡（SR-3）─────────────────────
// 自 SaveSelectScreen.kt 拆出（detekt 文件函数数预算 16>15 + LongMethod 78>60，
// batch-SR2 C7a 同口径）：纯搬移 + 容器拆分子 Composable，零逻辑变化。

/**
 * 云槽位卡可见性（internal 供守卫测试）：LOAD_SAVE 模式显示云端槽位存档；
 * NEW_GAME 模式隐藏（新游戏=建本地档，云端槽位与新游戏无关）。
 */
internal fun visibleCloudSlots(
    mode: SaveSelectMode,
    cloudSlots: List<CloudSaveEntry>
): List<CloudSaveEntry> = if (mode == SaveSelectMode.LOAD_SAVE) cloudSlots else emptyList()

/**
 * 云端槽位存档卡（SR-3）：slot_N 档的摘要渲染 + 点击下载。
 *
 * 摘要来源 = 云端 extra JSON（year/month/sect/disciples/stones/version）；
 * 摘要缺失（TapTap 元数据最终一致性延迟常态）时退化为"云端存档 N"占位文案——
 * 有档无摘要非错误，点击仍可下载。样式对齐 SaveSlotCard 云存档入口（蓝系）。
 */
@Composable
fun CloudSlotEntryCard(
    entry: CloudSaveEntry,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF0F7FF))
            .border(2.dp, Color(0xFF4A90E2), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CloudSlotEntryIcon()
                CloudSlotEntryText(entry = entry, dateFormat = dateFormat)
            }
            Text(
                text = "点击下载",
                fontSize = 12.sp,
                color = Color(0xFF4A90E2),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 云槽位图标块（蓝底"云"，对齐既有云存档入口样式） */
@Composable
private fun CloudSlotEntryIcon() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF4A90E2)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "云",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/** 云槽位摘要文本：宗门/年月/弟子灵石/云端保存时间（摘要缺失时退化为占位文案） */
@Composable
private fun CloudSlotEntryText(
    entry: CloudSaveEntry,
    dateFormat: SimpleDateFormat
) {
    val summary = entry.summary
    Column {
        Text(
            text = if (summary != null && summary.sectName.isNotBlank()) {
                summary.sectName
            } else {
                "云端存档 ${entry.slot}"
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color.Black
        )
        if (summary != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "第${summary.gameYear}年 ${summary.gameMonth}月",
                fontSize = 13.sp,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "弟子: ${summary.discipleCount}  灵石: ${summary.spiritStones}",
                fontSize = 12.sp,
                color = Color.Black
            )
        }
        if (entry.modifiedTimeMs > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "云端保存: ${dateFormat.format(Date(entry.modifiedTimeMs))}",
                fontSize = 11.sp,
                color = Color(0xFF999999)
            )
        }
    }
}
