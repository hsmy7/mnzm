package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.theme.GameColors

/** -10/+10 步进按钮的步进值（命名常量防魔法数字） */
private const val QUANTITY_STEP_SIZE = 10

/** 步进按钮与输入框之间的间距 */
private val STEP_SPACING = 8.dp

/** 数量选择器视觉尺寸配置（三处调用点按钮/数字区尺寸不同，交互形态统一） */
@Immutable
internal data class QuantitySelectorSizes(
    val buttonSize: Dp = 36.dp,
    val numberBoxWidth: Dp = 72.dp,
    val numberBoxHeight: Dp = 48.dp,
    val buttonCornerRadius: Dp = 6.dp,
    val buttonFontSize: TextUnit = 18.sp,
)

/**
 * 统一数量选择器：点击数字弹键盘输入（超上限自动截断）+ [-10][−][数字][+][+10] 四向步进。
 *
 * 键盘防频闪约束（rules/dialog-soft-input-guard.md）：
 * - 本组件不创建任何平台 Dialog 窗口
 * - 不叠加 imePadding——键盘避让由外层容器统一负责
 *   （InlineStandardPromptDialog 双上下文自动检测 / UnifiedGameDialog 内置 ADJUST_PAN）
 * - 输入框**常驻**（BasicTextField），点击即聚焦、焦点与 IME 由平台原子管理
 *   （对齐 AutoManagementDialog 已验证模式）——不使用"点击 Box 后条件渲染输入框 +
 *   编程式 requestFocus"（该模式在国产 ROM 平台 Dialog 窗口内与 IME 入场竞争，
 *   键盘弹出即被系统误报收起，随后失焦回调销毁输入框）
 *
 * 编辑态（输入框聚焦）仅保留 [−][输入框][+]：键盘弹出空间有限，
 * 且避免"步进作用于未提交文本"的语义混乱；-10/+10 步进仅在非编辑态生效。
 *
 * @param quantity 当前数量（调用方持有状态）
 * @param maxQuantity 上限（应 ≥ [QUANTITY_MIN]；小于 1 时按 1 兜底，产出恒为 1）
 * @param onQuantityChange 数量变更回调（组件保证值已钳制到 [QUANTITY_MIN, maxQuantity]，
 *   maxQuantity 小于 1 时退化产出 1）
 * @param modifier 修饰符
 * @param sizes 视觉尺寸配置
 */
@Composable
internal fun QuantitySelector(
    quantity: Int,
    maxQuantity: Int,
    onQuantityChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    sizes: QuantitySelectorSizes = QuantitySelectorSizes(),
) {
    var isEditing by remember { mutableStateOf(false) }
    var quantityInput by remember { mutableStateOf(quantity.toString()) }
    val focusManager = LocalFocusManager.current

    // 步进统一入口：计算新值 → 回调 + 同步输入串（防再进编辑态时内容漂移）
    fun step(step: Int) {
        val next = applyStep(quantity, step, QUANTITY_MIN, maxQuantity)
        onQuantityChange(next)
        quantityInput = next.toString()
    }

    // 外部数量变化同步输入串（非编辑态；编辑态跳过，避免覆盖用户输入）。
    // 与防御性钳制合并为单一 effect：否则 LaunchedEffect(quantity) 首次执行会
    // 把钳制写入的输入串覆盖回超限原值（初始超限时输入框显示 15 而非 10）
    LaunchedEffect(quantity, maxQuantity) {
        val effectiveQuantity = if (quantity > maxQuantity) {
            val clamped = applyStep(quantity, 0, QUANTITY_MIN, maxQuantity)
            onQuantityChange(clamped)
            clamped
        } else {
            quantity
        }
        if (!isEditing) quantityInput = effectiveQuantity.toString()
    }

    // 统一提交：净化输入 → 写回数量与输入串 → 退出编辑态。
    // isEditing 守卫吞掉 onFocusChanged 的 attach 初始回调与 Done 后 clearFocus 的二次回调
    fun commit() {
        if (!isEditing) return
        val sanitized = sanitizeQuantityInput(quantityInput, maxQuantity)
        quantityInput = sanitized.text
        onQuantityChange(sanitized.quantity)
        isEditing = false
        quantityInput = quantity.toString()
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DecrementButtons(
            isEditing = isEditing,
            isEnabled = quantity > QUANTITY_MIN,
            sizes = sizes,
            onStep = { step(it) }
        )
        Spacer(modifier = Modifier.width(STEP_SPACING))
        QuantityInputField(
            value = quantityInput,
            isEditing = isEditing,
            maxQuantity = maxQuantity,
            onSanitized = { sanitized ->
                quantityInput = sanitized.text
                onQuantityChange(sanitized.quantity)
            },
            onFocused = { focused ->
                if (focused) {
                    isEditing = true
                    // 进入编辑态同步显示（防外部数量变化后输入串漂移）
                    quantityInput = quantity.toString()
                } else {
                    commit()
                }
            },
            onDone = {
                commit()
                // 常驻输入框不随编辑态退出销毁，必须显式清除焦点键盘才会收起
                focusManager.clearFocus()
            },
            sizes = sizes,
        )
        IncrementButtons(
            isEditing = isEditing,
            isEnabled = quantity < maxQuantity,
            sizes = sizes,
            onStep = { step(it) }
        )
    }
}

/** 左侧步进按钮组：[−10]（非编辑态）/ [−]；编辑态隐藏 −10 保留 [−] */
@Composable
private fun DecrementButtons(
    isEditing: Boolean,
    isEnabled: Boolean,
    sizes: QuantitySelectorSizes,
    onStep: (Int) -> Unit,
) {
    if (!isEditing) {
        QuantityStepButton(text = "−10", enabled = isEnabled, sizes = sizes) { onStep(-QUANTITY_STEP_SIZE) }
        Spacer(modifier = Modifier.width(STEP_SPACING))
    }
    QuantityStepButton(text = "−", enabled = isEnabled, sizes = sizes) { onStep(-1) }
}

/** 右侧步进按钮组：[+] / [+10]（非编辑态） */
@Composable
private fun IncrementButtons(
    isEditing: Boolean,
    isEnabled: Boolean,
    sizes: QuantitySelectorSizes,
    onStep: (Int) -> Unit,
) {
    Spacer(modifier = Modifier.width(STEP_SPACING))
    QuantityStepButton(text = "+", enabled = isEnabled, sizes = sizes) { onStep(1) }
    if (!isEditing) {
        Spacer(modifier = Modifier.width(STEP_SPACING))
        QuantityStepButton(text = "+10", enabled = isEnabled, sizes = sizes) { onStep(QUANTITY_STEP_SIZE) }
    }
}

/** 步进按钮：启用态浅灰底 / 禁用态浅灰底 + 灰色文字，clickableWithSound */
@Composable
private fun QuantityStepButton(
    text: String,
    enabled: Boolean,
    sizes: QuantitySelectorSizes,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(sizes.buttonSize)
            .clip(RoundedCornerShape(sizes.buttonCornerRadius))
            .background(if (enabled) GameColors.SurfaceLightGray else Color(0xFFF5F5F5))
            .clickableWithSound(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = sizes.buttonFontSize,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color.Black else GameColors.ButtonDisabled
        )
    }
}

/**
 * 常驻数量输入框（BasicTextField）：点击即聚焦、焦点与 IME 由平台原子管理
 * （对齐 AutoManagementDialog 已验证模式），数字键盘 + Done。
 * 聚焦/失焦经 [onFocused] 驱动编辑态；输入经净化实时截断；Done 走 [onDone]（含 clearFocus）。
 *
 * 垂直居中：显式 height(boxHeight) 下 BasicTextField 文本默认顶部对齐，
 * 经 decorationBox 内 Box(contentAlignment = Center) 居中（M3 TextField 同款结构）。
 */
@Composable
private fun QuantityInputField(
    value: String,
    isEditing: Boolean,
    maxQuantity: Int,
    onSanitized: (SanitizedQuantityInput) -> Unit,
    onFocused: (Boolean) -> Unit,
    onDone: () -> Unit,
    sizes: QuantitySelectorSizes,
) {
    BasicTextField(
        value = value,
        onValueChange = { raw -> onSanitized(sanitizeQuantityInput(raw, maxQuantity)) },
        modifier = Modifier
            // 固定宽度而非 widthIn(min)：decorationBox 内 fillMaxSize 会撑满外层
            // 约束，min 语义下输入框占满整行、把右侧 +10 按钮挤出布局
            .width(sizes.numberBoxWidth)
            .height(sizes.numberBoxHeight)
            .onFocusChanged { focusState -> onFocused(focusState.isFocused) },
        // 装饰层（M3 同款）：白底 + 边框 + 文本垂直居中
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(sizes.buttonCornerRadius))
                    .border(
                        1.dp,
                        if (isEditing) GameColors.Primary else GameColors.DividerGray,
                        RoundedCornerShape(sizes.buttonCornerRadius)
                    )
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                innerTextField()
            }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = Color.Black
        ),
        cursorBrush = SolidColor(Color.Black),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() })
    )
}
