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
import com.xianxia.sect.ui.game.saveload.MigrationSlotRow
import com.xianxia.sect.ui.game.saveload.MigrationPhase
import com.xianxia.sect.ui.game.saveload.MigrationUiState
import com.xianxia.sect.ui.game.saveload.SlotMigrationStatus
import com.xianxia.sect.ui.model.SaveSelectMode

// ── 存量迁移引导卡（SR-6，主菜单选档页）─────────────────────
// 独立成文件而非塞进 SaveSelectScreen.kt：后者函数数已顶到 detekt 文件预算
// （SR-3 `36169a76a` 拆 CloudSlotEntryCard 同口径）。

/**
 * 槽位状态 → 玩家可读文案（internal 纯函数，守卫测试锚定）。
 *
 * 每条都必须"看得懂下一步"：损坏槽要点明"未上云、未覆盖"（否则玩家以为已安全），
 * 上云中要点明"云端每分钟只接受一次写入"（否则 6 档排队会被当成卡死）。
 */
internal fun migrationRowText(status: SlotMigrationStatus): String = when (status) {
    SlotMigrationStatus.AWAITING_UPLOAD -> "待上云"
    SlotMigrationStatus.UPLOADING -> "上云中（云端每分钟仅接受一次写入，多档需排队数分钟）"
    SlotMigrationStatus.UPLOAD_FAILED -> "上云失败"
    SlotMigrationStatus.NEEDS_DECISION -> "本机与云端各有进度，请选择保留哪一份"
    SlotMigrationStatus.CLOUD_ONLY -> "云端有存档，本机没有"
    SlotMigrationStatus.BLOCKED_CORRUPT -> "本机存档损坏，已跳过（未上云、未覆盖）"
    SlotMigrationStatus.MIGRATED -> "已上云"
}

/** 阶段文案 */
internal fun migrationPhaseText(phase: MigrationPhase): String = when (phase) {
    MigrationPhase.IDLE -> ""
    MigrationPhase.READY -> "检测到尚未上云的存档"
    MigrationPhase.RUNNING -> "迁移进行中"
    MigrationPhase.DONE -> "本机存档均已上云"
    MigrationPhase.PARTIAL_FAILED -> "部分存档上云失败，可重试或稍后再看"
    MigrationPhase.SERVICE_UNAVAILABLE -> "云存档服务暂不可用"
}

/**
 * 迁移卡可见性（internal 纯函数，守卫测试锚定）：
 * 只在**读档模式**且确有可迁内容时出现——新游戏模式是"建本地档"，与存量上云无关
 * （与 [visibleCloudSlots] 同一纪律）。
 */
internal fun migrationCardVisible(
    mode: SaveSelectMode,
    migration: MigrationUiState
): Boolean = mode == SaveSelectMode.LOAD_SAVE && migration.visible

/**
 * 迁移卡的四个动作位（打包传参，避免 `SaveSelectScreen` 形参表再膨胀 4 行）。
 * 全部带默认值 ⇒ 既有调用点与守卫测试零改动。
 */
data class MigrationActions(
    val onStart: () -> Unit = {},
    val onDecision: (slot: Int, keepLocal: Boolean) -> Unit = { _, _ -> },
    val onLegacyDownload: (targetSlot: Int) -> Unit = {},
    val onEnableCloud: () -> Unit = {}
)

/**
 * 存量迁移引导卡：逐槽状态 + 开始/裁决/下载/启用四个动作位。
 *
 * 常驻而非弹窗（施工卡 §7 S3）：TapTap 创建/更新共享 1 次/分钟冷却，6 槽逐个上传
 * 至少 6 分钟——弹窗看不见进度，只会被当成卡死（SR-4 "消息栏常驻一行而非 snackbar" 同教训）。
 * 卡片不会自动发起任何云请求：[MigrationActions.onStart] 之前协调器只做本地扫描。
 */
@Composable
fun SaveMigrationCard(
    migration: MigrationUiState,
    emptyLocalSlots: List<Int>,
    actions: MigrationActions
) {
    val rows = migration.rows
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFFFF7E8))
            .border(2.dp, Color(0xFFC9822B), RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = "云存档迁移 · ${migrationPhaseText(migration.phase)}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(8.dp))
            rows.forEach { row ->
                MigrationRowLine(row = row, onDecision = actions.onDecision)
                Spacer(modifier = Modifier.height(6.dp))
            }
            if (migration.legacyArchivePresent) {
                LegacyArchiveLine(
                    emptyLocalSlots = emptyLocalSlots,
                    onLegacyDownload = actions.onLegacyDownload
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            migration.notice?.let {
                Text(text = it, fontSize = 12.sp, color = Color(0xFFB03A2E))
                Spacer(modifier = Modifier.height(6.dp))
            }
            MigrationActionBar(
                phase = migration.phase,
                pendingTotal = migration.pendingTotal,
                migratedTotal = migration.migratedTotal,
                canEnableCloudSave = migration.canEnableCloudSave,
                actions = actions
            )
        }
    }
}

/** 单槽一行：状态文案 +（待裁决时）二选一按钮 */
@Composable
private fun MigrationRowLine(
    row: MigrationSlotRow,
    onDecision: (slot: Int, keepLocal: Boolean) -> Unit
) {
    Column {
        Text(
            text = "槽 ${row.slot} · ${row.label} — ${migrationRowText(row.status)}",
            fontSize = 13.sp,
            color = Color.Black
        )
        row.detail?.let {
            Text(text = it, fontSize = 11.sp, color = Color(0xFF777777))
        }
        if (row.status == SlotMigrationStatus.NEEDS_DECISION) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MigrationActionText("保留本机并上云", onClick = { onDecision(row.slot, true) })
                MigrationActionText("改用云端进度", onClick = { onDecision(row.slot, false) })
            }
        }
    }
}

/** 存量单档一行：旧版 `mnzm_cloud_save` 落到哪个本机空槽由玩家点定 */
@Composable
private fun LegacyArchiveLine(
    emptyLocalSlots: List<Int>,
    onLegacyDownload: (targetSlot: Int) -> Unit
) {
    Column {
        Text(
            text = if (emptyLocalSlots.isEmpty()) {
                "旧版云存档（单档）：本机没有空槽位可放置，先删除一个存档再取回"
            } else {
                "旧版云存档（单档）：下载到本机槽位"
            },
            fontSize = 13.sp,
            color = Color.Black
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            emptyLocalSlots.forEach { slot ->
                MigrationActionText("槽 $slot", onClick = { onLegacyDownload(slot) })
            }
        }
    }
}

/** 卡底动作位：开始迁移 / 进度 / 启用云存档 */
@Composable
private fun MigrationActionBar(
    phase: MigrationPhase,
    pendingTotal: Int,
    migratedTotal: Int,
    canEnableCloudSave: Boolean,
    actions: MigrationActions
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "已上云 $migratedTotal 个 · 待处理 $pendingTotal 个",
            fontSize = 12.sp,
            color = Color(0xFF777777)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canEnableCloudSave) {
                MigrationActionText("启用云存档", onClick = actions.onEnableCloud)
            }
            if (pendingTotal > 0 && phase != MigrationPhase.RUNNING &&
                phase != MigrationPhase.SERVICE_UNAVAILABLE
            ) {
                MigrationActionText("开始迁移", onClick = actions.onStart)
            }
        }
    }
}

/** 卡内文本按钮（与云槽位卡"点击下载"同款轻量样式） */
@Composable
private fun MigrationActionText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFFC9822B),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
