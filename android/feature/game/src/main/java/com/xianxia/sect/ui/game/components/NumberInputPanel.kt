package com.xianxia.sect.ui.game.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.theme.GameColors

/** 数字键盘布局（横屏游戏底部键盘 4 行 x 3 列；C=清空、⌫=退格） */
private val NUMBER_PAD_LAYOUT: List<List<String>> = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("C", "0", "⌫")
)

/** 数字键盘按钮间距 */
private val PAD_SPACING = 8.dp

/** 数字键盘按钮高度（横屏高度有限，紧凑布局） */
private val PAD_BUTTON_HEIGHT = 48.dp

/** 清空键文本（与数字区分） */
private const val KEY_CLEAR = "C"

/** 退格键文本 */
private const val KEY_BACKSPACE = "⌫"

/**
 * 自绘数字输入面板（数量输入场景绕开系统 IME，
 * 行业主流"自绘 UI + 事件流"范式，见 docs/ime-keyboard-industry-research.md §11.2.5）。
 *
 * 全屏半透明覆盖层 + 底部自绘数字键盘（0–9 / 清空 / 退格 / 确定）：
 * - **零系统 IME 参与**：无 BasicTextField、无 requestFocus、无 insets 依赖——
 *   数量输入场景（商人/交易/仓库出售/种植/巡逻塔/自动管理）彻底绕开
 *   "系统键盘 × 窗口 × 系统栏"交互面，天然免疫键盘振荡/闪屏
 * - 输入净化复用 [sanitizeQuantityInput]（实时钳制 [1, maxQuantity]，非法字符过滤）
 * - 确定 → [onConfirm]（已钳制值）+ 关闭；点空白 / 返回键 → [onDismiss] 取消
 *
 * @param initialValue 初始数量（进入面板时显示）
 * @param maxQuantity 上限（钳制目标；小于 1 时按 1 兜底）
 * @param onConfirm 确定回调（参数已钳制到 [1, maxQuantity]）
 * @param onDismiss 取消/关闭回调
 */
@Composable
internal fun NumberInputPanel(
    initialValue: Int,
    maxQuantity: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(initialValue.toString()) }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            // 点击空白取消；键盘面板自身阻止穿透（内部 Column 消费点击）
            .clickableWithSound(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        NumberInputPanelBody(
            input = input,
            maxQuantity = maxQuantity,
            onInputChange = { input = it },
            onConfirmClick = {
                val sanitized = sanitizeQuantityInput(input, maxQuantity)
                onConfirm(sanitized.quantity)
                onDismiss()
            }
        )
    }
}

/** 面板主体（NumberInputPanel 拆分防 LongMethod）：值显示 + 数字键盘 + 确定按钮 */
@Composable
private fun NumberInputPanelBody(
    input: String,
    maxQuantity: Int,
    onInputChange: (String) -> Unit,
    onConfirmClick: () -> Unit
) {
    val displayValue = input.ifEmpty { "0" }

    fun onKey(key: String) {
        when (key) {
            KEY_CLEAR -> onInputChange("")
            KEY_BACKSPACE -> onInputChange(input.dropLast(1))
            else -> {
                // 初始占位 "0"（显示兜底）不参与追加——避免 "0"+key 产生前导零
                val base = if (input == "0") "" else input
                onInputChange(sanitizeQuantityInput(base + key, maxQuantity).text)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F0E8), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .padding(16.dp)
            .clickableWithSound(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {} // 阻止点击穿透到外层 scrim（防误触取消）
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 当前输入值显示
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayValue,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.testTag("number_input_display")
            )
        }
        Spacer(Modifier.height(PAD_SPACING * 2))

        // 自绘数字键盘
        NUMBER_PAD_LAYOUT.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PAD_SPACING)
            ) {
                row.forEach { key ->
                    NumberPadKey(
                        text = key,
                        modifier = Modifier.weight(1f),
                        onClick = { onKey(key) }
                    )
                }
            }
            Spacer(Modifier.height(PAD_SPACING))
        }

        // 确定按钮
        GameButton(
            text = "确定",
            onClick = onConfirmClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        )
    }
}

/** 数字键盘按键（Box 样式，clickableWithSound；testTag 供组件测试定位） */
@Composable
private fun RowScope.NumberPadKey(
    text: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(PAD_BUTTON_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(GameColors.SurfaceLightGray)
            .clickableWithSound(onClick = onClick)
            .testTag("number_pad_key_$text"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}
