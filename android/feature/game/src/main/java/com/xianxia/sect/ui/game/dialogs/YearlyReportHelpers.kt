package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.theme.GameColors
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*


@Composable
internal fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun DataText(text: String) {
    Text(text, fontSize = 11.sp, color = Color(0xFF333333))
}

@Composable
internal fun EmptyDataText(text: String) {
    Text("  $text", fontSize = 11.sp, color = Color(0xFF888888))
}

@Composable
internal fun ReportDivider() {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = GameColors.SurfaceLightGray, thickness = 1.dp)
    Spacer(Modifier.height(8.dp))
}

/**
 * 汇总区单个指标卡片
 */
@Composable
internal fun SummaryCard(label: String, value: String, valueColor: Color = Color.Black) {
    Column(
        modifier = Modifier
            .width(80.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFF5F5F5))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 9.sp, color = Color(0xFF666666))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

// ══════════════════════════════════════════════════════════════
// 工具函数
// ══════════════════════════════════════════════════════════════

/**
 * 归并 Sell(*) 条目为统一的"售卖"条目。
 */
internal fun mergeSellEntries(map: Map<String, Long>): Map<String, Long> {
    val result = mutableMapOf<String, Long>()
    map.forEach { (key, value) ->
        if (key.startsWith("Sell")) {
            result["Sell"] = (result["Sell"] ?: 0L) + value
        } else {
            result[key] = value
        }
    }
    return result
}

/** SpiritStoneSource.key → 中文显示名 */
internal fun sourceDisplayName(key: String): String = SPIRIT_STONE_SOURCE_NAMES[key] ?: key

/** SpiritStoneReason.key → 中文显示名 */
internal fun reasonDisplayName(key: String): String = SPIRIT_STONE_REASON_NAMES[key] ?: key

/** 装备来源名（exploration/sect_level 保留映射：历史存档可能残留旧键） */
internal fun equipSourceName(key: String): String = EQUIP_SOURCE_NAMES[key] ?: key

/** 丹药来源名（exploration/sect_level 保留映射：历史存档可能残留旧键） */
internal fun pillSourceName(key: String): String = PILL_SOURCE_NAMES[key] ?: key

/** 草药来源名（exploration 保留映射：历史存档可能残留旧键） */
internal fun herbSourceName(key: String): String = HERB_SOURCE_NAMES[key] ?: key

/** 带符号格式化：正数加 "+"，负数保留 "-" */
internal fun formatSigned(value: Long): String = if (value > 0) "+$value" else "$value"
internal fun formatSigned(value: Int): String = if (value > 0) "+$value" else "$value"
