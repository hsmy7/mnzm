package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.util.InputValidator
import com.xianxia.sect.ui.components.InlineStandardPromptDialog

/**
 * 宗门改名对话框。
 * 复用 [InlineStandardPromptDialog] + [OutlinedTextField] 模式，
 * 与新建宗门时 [com.xianxia.sect.ui.SaveSelectScreen] 中的输入框一致。
 *
 * @param currentName 当前宗门名称（预填到输入框）
 * @param onConfirm 用户确认改名时回调，传入新名称（已通过 [InputValidator.validateSectName] 验证）
 * @param onDismiss 取消/关闭对话框回调
 */
@Composable
fun RenameSectDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(currentName) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    InlineStandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "修改宗门名称",
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
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(100) // 等待 Dialog 入场动画完成，兼容 ColorOS/FuntouchOS
                focusRequester.requestFocus()
            }
            OutlinedTextField(
                value = input,
                onValueChange = { newValue ->
                    if (newValue.length <= InputValidator.MAX_SECT_NAME_LENGTH) {
                        input = newValue
                        // 空输入不校验（留空时用户需自行点击取消）
                        error = newValue.takeIf { it.isNotBlank() }
                            ?.let { InputValidator.validateSectName(it) }
                    }
                },
                placeholder = { Text("青云宗", color = Color(0xFF999999)) },
                singleLine = true,
                isError = error != null,
                textStyle = TextStyle(color = Color.Black, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
            Text(
                text = error ?: "${input.length}/${InputValidator.MAX_SECT_NAME_LENGTH}",
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
