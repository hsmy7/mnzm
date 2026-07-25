package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xianxia.sect.ui.components.DialogSystemBarGuard
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.game.SaveLoadViewModel
import com.xianxia.sect.ui.game.CloudSaveOperationState
import com.xianxia.sect.ui.theme.GameColors

/**
 * 云存档对话框。
 *
 * 显示当前云存档信息，提供"上传存档"和"下载存档"操作按钮。
 * 未登录时提示需要登录 TapTap。
 */
@Composable
fun CloudSaveDialog(
    saveLoadViewModel: SaveLoadViewModel,
    onDismiss: () -> Unit
) {
    val cloudSaveInfo by saveLoadViewModel.cloudSaveInfo.collectAsStateWithLifecycle()
    val operationStateValue by saveLoadViewModel.cloudSaveOperationState.collectAsStateWithLifecycle()
    val operationState = operationStateValue

    LaunchedEffect(Unit) {
        saveLoadViewModel.checkCloudSave()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        DialogSystemBarGuard()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(20.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 标题
                    Text(
                        text = "☁ 云存档",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    // 云存档信息
                    if (cloudSaveInfo.hasSaveData) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF0F7FF))
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "存档信息",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF4A90E2)
                                )
                                if (cloudSaveInfo.description.isNotBlank()) {
                                    Text(
                                        text = cloudSaveInfo.description,
                                        fontSize = 13.sp,
                                        color = Color.Black
                                    )
                                }
                                if (cloudSaveInfo.lastModifiedTime > 0) {
                                    Text(
                                        text = "上次保存: ${formatTime(cloudSaveInfo.lastModifiedTime)}",
                                        fontSize = 12.sp,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF5F5F5))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "暂无云存档数据",
                                fontSize = 13.sp,
                                color = Color(0xFF999999)
                            )
                        }
                    }

                    // 操作按钮
                    when (operationState) {
                        is CloudSaveOperationState.Idle -> {
                            if (!saveLoadViewModel.isCloudSaveAvailable()) {
                                Text(
                                    text = "使用云存档需要登录 TapTap",
                                    fontSize = 12.sp,
                                    color = Color(0xFFE53935)
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                GameButton(
                                    text = "上传存档",
                                    onClick = { saveLoadViewModel.uploadToCloudSave() },
                                    enabled = saveLoadViewModel.isCloudSaveAvailable()
                                )
                                GameButton(
                                    text = "下载存档",
                                    onClick = { saveLoadViewModel.downloadFromCloudSave() },
                                    enabled = saveLoadViewModel.isCloudSaveAvailable() && cloudSaveInfo.hasSaveData
                                )
                            }
                        }
                        is CloudSaveOperationState.Uploading -> {
                            Text(
                                text = "正在上传云存档...",
                                fontSize = 14.sp,
                                color = Color.Black
                            )
                        }
                        is CloudSaveOperationState.Downloading -> {
                            Text(
                                text = "正在下载云存档...",
                                fontSize = 14.sp,
                                color = Color.Black
                            )
                        }
                        is CloudSaveOperationState.Success -> {
                            Text(
                                text = operationState.message,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF4CAF50)
                            )
                        }
                        is CloudSaveOperationState.Error -> {
                            Text(
                                text = operationState.message,
                                fontSize = 14.sp,
                                color = Color(0xFFE53935)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            GameButton(
                                text = "关闭",
                                onClick = { saveLoadViewModel.resetCloudSaveOperationState() }
                            )
                        }
                    }

                    // 关闭按钮
                    if (operationState !is CloudSaveOperationState.Uploading &&
                        operationState !is CloudSaveOperationState.Downloading
                    ) {
                        GameButton(
                            text = "关闭",
                            onClick = onDismiss
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
    return sdf.format(java.util.Date(timestamp))
}
