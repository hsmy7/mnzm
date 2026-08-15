package com.xianxia.sect.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.xianxia.sect.ui.components.DialogFocusGuard
import com.xianxia.sect.ui.components.DialogSystemBarGuard
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.canRenderDialogs

/** 当前组合是否允许渲染 Dialog（Activity 生命周期 ≥ STARTED，防销毁窗口期 BadToken） */
@Composable
private fun dialogRenderableInComposition(): Boolean {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return lifecycleState.canRenderDialogs()
}

/**
 * 防沉迷合规限制对话框（共享组件，D-42 进程级宿主统一使用）。
 *
 * MainActivity 与 GameActivity 共用：主界面与游戏内的时长限制/时间限制/年龄限制
 * 弹窗同一实现，包含三件套：
 * - 生命周期门控（销毁窗口期不渲染，防 BadToken）
 * - [DialogSystemBarGuard]（两 Activity 均隐藏系统栏，Dialog Window 不继承
 *   Activity 的 hideSystemBars，需独立隐藏状态栏）
 * - 空 onDismissRequest（合规限制不可绕过关闭）
 *
 * @param complianceDialogState 限制弹窗状态（非空时展示）
 * @param onLogout 退出游戏/切换账号（清会话 + 完整登出 + 回主界面）
 * @param onAgeFinish 适龄限制"退出游戏"（finish 当前 Activity）
 */
@Composable
fun ComplianceLimitDialogs(
    complianceDialogState: MutableState<ComplianceDialogState?>,
    onLogout: () -> Unit,
    onAgeFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!dialogRenderableInComposition()) return

    complianceDialogState.value?.let { state ->
        DialogSystemBarGuard()
        when (state) {
            is ComplianceDialogState.Restrict -> {
                AlertDialog(
                    modifier = modifier,
                    onDismissRequest = { },
                    title = { Text(state.title) },
                    text = { DialogFocusGuard(); Text(state.message) },
                    confirmButton = {
                        GameButton(
                            text = "退出游戏",
                            onClick = {
                                complianceDialogState.value = null
                                onLogout()
                            }
                        )
                    },
                    dismissButton = {
                        GameButton(
                            text = "切换账号",
                            onClick = {
                                complianceDialogState.value = null
                                onLogout()
                            }
                        )
                    }
                )
            }
            is ComplianceDialogState.AgeLimit -> {
                AlertDialog(
                    modifier = modifier,
                    onDismissRequest = { },
                    title = { Text("适龄限制") },
                    text = { DialogFocusGuard(); Text("根据游戏适龄提示，您当前年龄不符合本游戏的游玩要求。") },
                    confirmButton = {
                        GameButton(
                            text = "退出游戏",
                            onClick = {
                                complianceDialogState.value = null
                                onAgeFinish()
                            }
                        )
                    }
                )
            }
        }
    }
}
