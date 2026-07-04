package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.util.InputValidator
import com.xianxia.sect.ui.components.InlineStandardPromptDialog

/**
 * 弟子改名对话框。
 * 复用 [InlineStandardPromptDialog] + [OutlinedTextField] 模式，
 * 与 [RenameSectDialog] 一致。
 *
 * @param currentName 当前弟子名称（预填到输入框）
 * @param onConfirm 用户确认改名时回调，传入新名称（已通过 [InputValidator.validateDiscipleName] 验证）
 * @param onDismiss 取消/关闭对话框回调
 */
@Composable
fun RenameDiscipleDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(currentName) }
    var error by remember { mutableStateOf<String?>(null) }

    InlineStandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "修改弟子名称",
        confirmLabel = "确定",
        dismissLabel = "取消",
        onConfirm = {
            val name = input.trim()
            if (name.isNotBlank() && error == null) {
                onConfirm(name)
            }
        },
        onDismiss = onDismiss,
        content = {
            OutlinedTextField(
                value = input,
                onValueChange = { newValue ->
                    if (newValue.length <= InputValidator.MAX_DISCIPLE_NAME_LENGTH) {
                        input = newValue
                        // 空输入不校验（留空时用户需自行点击取消）
                        error = newValue.takeIf { it.isNotBlank() }
                            ?.let { InputValidator.validateDiscipleName(it) }
                    }
                },
                placeholder = { Text("张三", color = Color(0xFF999999)) },
                singleLine = true,
                isError = error != null,
                textStyle = TextStyle(color = Color.Black, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = error ?: "${input.length}/${InputValidator.MAX_DISCIPLE_NAME_LENGTH}",
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
