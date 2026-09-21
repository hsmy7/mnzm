package com.xianxia.sect.ui.game

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import com.xianxia.sect.R
import com.xianxia.sect.data.cloud.SaveConflictEvent
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog

/**
 * 云存档真冲突弹窗（SR-3，方案 §2：本地脏 × 云端有更新，禁止静默覆盖）。
 *
 * **选谁留档明确可见**：正文列出双方保存序号，两个选项各自写明"留谁/丢什么"——
 * - 下载侧（冷启动/游戏内云槽位下载撞上本机未上传进度）：
 *   保留本机 = 不下载不覆盖；保留云端 = 云档覆盖本机槽位，本机未上传进度丢失；
 * - 上传侧（队列上传前仲裁挂起）：
 *   保留本机 = 本机进度稍后上传覆盖云端；保留云端 = 丢弃本机未上传进度。
 *
 * 模态：禁用点外关闭与返回键关闭——二选一是必须的显式决策，误触关闭不得暗自
 * 替玩家弃选（任一选择都有明确后果文案）。
 */
@Composable
fun CloudConflictDialog(
    conflict: SaveConflictEvent,
    onKeepLocal: () -> Unit,
    onKeepCloud: () -> Unit
) {
    val cloudLabel = "第 ${conflict.cloudSaveId ?: conflict.lastConfirmedCloudId} 次保存"
    val body = if (conflict.source == "download") {
        "本机与云端都有新进度，需要选择保留哪一份：\n\n" +
            "本机进度：第 ${conflict.lastLocalSaveId} 次保存（未上传）\n" +
            "云端进度：$cloudLabel\n\n" +
            "「保留本机」：继续使用本机进度，不下载云端存档\n" +
            "「保留云端」：下载云端存档覆盖本机此槽位，本机未上传的进度将丢失"
    } else {
        "本机与云端都有新进度，需要选择保留哪一份：\n\n" +
            "本机进度：第 ${conflict.lastLocalSaveId} 次保存（未上传）\n" +
            "云端进度：$cloudLabel\n\n" +
            "「保留本机」：本机进度稍后自动上传，覆盖云端存档\n" +
            "「保留云端」：丢弃本机未上传的进度，采用云端存档"
    }
    StandardPromptDialog(
        onDismissRequest = { /* 模态：必须二选一，不做默认裁决 */ },
        title = "云存档冲突",
        text = body,
        customButtons = {
            GameButton(
                text = "保留本机",
                onClick = onKeepLocal,
                buttonBackgroundRes = R.drawable.ui_button
            )
            Spacer(modifier = Modifier.width(16.dp))
            GameButton(
                text = "保留云端",
                onClick = onKeepCloud,
                buttonBackgroundRes = R.drawable.ui_button
            )
        },
        dismissOnBackPress = false,
        dismissOnClickOutside = false
    )
}
