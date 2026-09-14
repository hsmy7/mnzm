package com.xianxia.sect.ui.game.dialogs.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xianxia.sect.core.util.InputValidator
import com.xianxia.sect.ui.components.TextInputDialog

/**
 * 共享改名弹窗：RenameSectDialog/RenameDiscipleDialog 同构合并。
 * 统一为 [TextInputDialog]（独立平台 Dialog 窗口）——文本输入不再
 * 与游戏渲染 Surface 共窗（键盘窗口 resize/焦点抖动不再传导到游戏窗口），
 * 且获得输入会话状态机（open/close 幂等）与 per-API softInputMode 兜底。
 */
@Composable
fun RenameDialog(
    config: RenameDialogConfig,
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    scrimEnabled: Boolean = true
) {
    var input by remember { mutableStateOf(currentName) }
    var error by remember { mutableStateOf<String?>(null) }
    // 确认逻辑供确认按钮与键盘 Done 键共用，杜绝两处逻辑漂移
    val confirm: () -> Unit = {
        val name = input.trim()
        if (name.isNotBlank() && error == null) {
            onConfirm(name)
        }
    }

    TextInputDialog(
        onDismissRequest = onDismiss,
        title = config.title,
        value = input,
        onValueChange = { newValue ->
            input = newValue
            // 空输入不校验（留空时用户需自行点击取消）
            error = newValue.takeIf { it.isNotBlank() }
                ?.let { config.validate(it) }
        },
        placeholder = config.placeholder,
        maxLength = config.maxLength,
        isError = error != null,
        errorText = error,
        confirmLabel = "确定",
        dismissLabel = "取消",
        onConfirm = confirm,
        onDismiss = onDismiss,
        scrimEnabled = scrimEnabled
    )
}

/** 宗门改名弹窗（配置固定） */
@Composable
fun RenameSectDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    scrimEnabled: Boolean = true
) {
    RenameDialog(
        config = RenameDialogConfig(
            title = "修改宗门名称",
            placeholder = "青云宗",
            maxLength = InputValidator.MAX_SECT_NAME_LENGTH,
            validate = InputValidator::validateSectName
        ),
        currentName = currentName,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        scrimEnabled = scrimEnabled
    )
}

/** 弟子改名弹窗（配置固定） */
@Composable
fun RenameDiscipleDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    RenameDialog(
        config = RenameDialogConfig(
            title = "修改弟子名称",
            placeholder = "张三",
            maxLength = InputValidator.MAX_DISCIPLE_NAME_LENGTH,
            validate = InputValidator::validateDiscipleName
        ),
        currentName = currentName,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/** 改名弹窗配置（宗门/弟子共用，差异：标题/占位符/长度/校验器；声明置于 [RenameDialog] 之后） */
data class RenameDialogConfig(
    val title: String,
    val placeholder: String,
    val maxLength: Int,
    val validate: (String) -> String?
)
