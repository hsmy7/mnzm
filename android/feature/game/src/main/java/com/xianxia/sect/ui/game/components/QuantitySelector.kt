package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
 * - 焦点弹键盘仅用 LaunchedEffect + FocusRequester 既有模式（国产 ROM 无振荡回归）
 *
 * 编辑态（输入框激活）仅保留 [−][输入框][+]：键盘弹出空间有限，
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
    val focusRequester = remember { FocusRequester() }

    // 步进统一入口：计算新值 → 回调 + 同步输入串（防再进编辑态时内容漂移）
    fun step(step: Int) {
        val next = applyStep(quantity, step, QUANTITY_MIN, maxQuantity)
        onQuantityChange(next)
        quantityInput = next.toString()
    }

    // 防御性截断：上限变化或初始超限时钳制数量并同步输入串
    LaunchedEffect(maxQuantity) {
        if (quantity > maxQuantity) {
            val clamped = applyStep(quantity, 0, QUANTITY_MIN, maxQuantity)
            onQuantityChange(clamped)
            quantityInput = clamped.toString()
        }
    }

    // 进入编辑态自动聚焦弹键盘（沿用既有模式，防频闪兼容）
    LaunchedEffect(isEditing) {
        if (isEditing) focusRequester.requestFocus()
    }

    if (isEditing) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuantityStepButton(text = "−", enabled = quantity > QUANTITY_MIN, sizes = sizes) { step(-1) }
            Spacer(modifier = Modifier.width(8.dp))
            QuantityInputField(
                value = quantityInput,
                maxQuantity = maxQuantity,
                onSanitized = { sanitized ->
                    quantityInput = sanitized.text
                    onQuantityChange(sanitized.quantity)
                },
                onCommit = {
                    isEditing = false
                    quantityInput = quantity.toString()
                },
                focusRequester = focusRequester,
                minWidth = sizes.numberBoxWidth,
            )
            Spacer(modifier = Modifier.width(8.dp))
            QuantityStepButton(text = "+", enabled = quantity < maxQuantity, sizes = sizes) { step(1) }
        }
    } else {
        QuantityStepperRow(
            quantity = quantity,
            maxQuantity = maxQuantity,
            sizes = sizes,
            modifier = modifier,
            onStartEdit = {
                isEditing = true
                quantityInput = quantity.toString()
            },
            onStep = { step(it) }
        )
    }
}

/** 非编辑态步进行：[-10][−][数字框][+][+10]，点击数字框进入编辑态弹键盘 */
@Composable
private fun QuantityStepperRow(
    quantity: Int,
    maxQuantity: Int,
    sizes: QuantitySelectorSizes,
    modifier: Modifier,
    onStartEdit: () -> Unit,
    onStep: (Int) -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        QuantityStepButton(
            text = "−10",
            enabled = quantity > QUANTITY_MIN,
            sizes = sizes
        ) { onStep(-QUANTITY_STEP_SIZE) }
        Spacer(modifier = Modifier.width(8.dp))
        QuantityStepButton(
            text = "−",
            enabled = quantity > QUANTITY_MIN,
            sizes = sizes
        ) { onStep(-1) }
        Spacer(modifier = Modifier.width(8.dp))
        QuantityDisplayBox(
            quantity = quantity,
            sizes = sizes,
            onStartEdit = onStartEdit
        )
        Spacer(modifier = Modifier.width(8.dp))
        QuantityStepButton(
            text = "+",
            enabled = quantity < maxQuantity,
            sizes = sizes
        ) { onStep(1) }
        Spacer(modifier = Modifier.width(8.dp))
        QuantityStepButton(
            text = "+10",
            enabled = quantity < maxQuantity,
            sizes = sizes
        ) { onStep(QUANTITY_STEP_SIZE) }
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

/** 数量显示框：带边框可点击，点击进入编辑态弹键盘；宽度自适应长数字不裁剪 */
@Composable
private fun QuantityDisplayBox(
    quantity: Int,
    sizes: QuantitySelectorSizes,
    onStartEdit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .widthIn(min = sizes.numberBoxWidth)
            .height(sizes.numberBoxHeight)
            .clip(RoundedCornerShape(sizes.buttonCornerRadius))
            .border(1.dp, GameColors.DividerGray, RoundedCornerShape(sizes.buttonCornerRadius))
            .background(Color.White)
            .clickableWithSound(onClick = onStartEdit),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$quantity",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}

/** 编辑态输入框：数字键盘 + Done；输入经净化实时截断；失焦/Done 提交退出编辑态 */
@Composable
private fun QuantityInputField(
    value: String,
    maxQuantity: Int,
    onSanitized: (SanitizedQuantityInput) -> Unit,
    onCommit: () -> Unit,
    focusRequester: FocusRequester,
    minWidth: Dp,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onSanitized(sanitizeQuantityInput(raw, maxQuantity)) },
        modifier = Modifier
            .widthIn(min = minWidth)
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (!focusState.isFocused) onCommit()
            },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onCommit() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = GameColors.Primary,
            unfocusedBorderColor = GameColors.DividerGray
        )
    )
}
