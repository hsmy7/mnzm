package com.xianxia.sect.ui.game.dialogs.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.InlineStandardPromptDialog
import com.xianxia.sect.ui.components.rememberImeAwareAutoFocusRequester
import com.xianxia.sect.core.util.InputValidator

/** 改名弹窗配置（宗门/弟子共用，差异：标题/占位符/长度/校验器） */
data class RenameDialogConfig(
    val title: String,
    val placeholder: String,
    val maxLength: Int,
    val validate: (String) -> String?
)

/**
 * 共享改名弹窗：RenameSectDialog/RenameDiscipleDialog 同构合并。
 * 含 ColorOS/FuntouchOS 兼容的自动聚焦与键盘 Done 提交。
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
    // 自动聚焦 + 键盘弹出确认重试（荣耀X70根治：键盘首次弹出失败/被系统收起时有限重试）
    val focusRequester = rememberImeAwareAutoFocusRequester()
    // 确认逻辑供确认按钮与键盘 Done 键共用，杜绝两处逻辑漂移
    val confirm: () -> Unit = {
        val name = input.trim()
        if (name.isNotBlank() && error == null) {
            onConfirm(name)
        }
    }

    InlineStandardPromptDialog(
        onDismissRequest = onDismiss,
        title = config.title,
        confirmLabel = "确定",
        dismissLabel = "取消",
        scrimEnabled = scrimEnabled,
        onConfirm = confirm,
        onDismiss = onDismiss,
        // 含输入框：挂载期间冻结宿主窗口系统栏操作（键盘频闪根治，见 SystemBarFreezeScope）
        freezeSystemBars = true,
        content = {
            OutlinedTextField(
                value = input,
                onValueChange = { newValue ->
                    if (newValue.length <= config.maxLength) {
                        input = newValue
                        // 空输入不校验（留空时用户需自行点击取消）
                        error = newValue.takeIf { it.isNotBlank() }
                            ?.let { config.validate(it) }
                    }
                },
                placeholder = { Text(config.placeholder, color = Color(0xFF999999)) },
                singleLine = true,
                isError = error != null,
                textStyle = TextStyle(color = Color.Black, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { confirm() }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
            Text(
                text = error ?: "${input.length}/${config.maxLength}",
                fontSize = 11.sp,
                color = if (error != null) Color(0xFFEF5350) else Color.Black,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
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
