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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
 * 统一数量选择器：[-10][−][数字][+][+10] 四向步进 + 点击数字弹出**自绘数字面板**
 * （[NumberInputPanel]：数量输入绕开系统 IME）。
 *
 * 键盘防频闪约束（rules/dialog-soft-input-guard.md）：
 * - 本组件不创建平台 Dialog 窗口、**不依赖系统 IME**——点击数字框显示
 *   [NumberInputPanel]（自绘键盘，全屏覆盖层渲染于当前容器），彻底绕开
 *   "系统键盘 × 窗口 × 系统栏"交互面，天然免疫键盘振荡/闪屏（行业主流
 *   "自绘 UI + 事件流"范式，见 docs/ime-keyboard-industry-research.md §11.2.5）
 * - 不叠加 imePadding——避让由外层容器统一负责
 * - 输入净化复用 [sanitizeQuantityInput]（实时钳制 [QUANTITY_MIN, maxQuantity]，
 *   非法字符过滤），超上限自动截断
 * - 编辑态（面板打开）仅保留 [−][输入框][+]：键盘空间有限，且避免"步进作用于 *   未提交文本"的语义混乱；-10/+10 步进仅在非编辑态生效
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
        QuantityDisplayBox(
            text = quantityInput,
            isEditing = isEditing,
            sizes = sizes,
            onClick = { isEditing = true }
        )
        Spacer(modifier = Modifier.width(STEP_SPACING))
        IncrementButtons(
            isEditing = isEditing,
            isEnabled = quantity < maxQuantity,
            sizes = sizes,
            onStep = { step(it) }
        )
    }

    // 自绘数字面板（编辑态）：确定 → 提交数量；取消 → 退出编辑态。
    // 面板全屏覆盖渲染于当前容器（平台 Dialog 窗口内覆盖窗口内容，无新窗口）。
    if (isEditing) {
        NumberInputPanel(
            initialValue = quantity,
            maxQuantity = maxQuantity,
            onConfirm = { confirmed ->
                onQuantityChange(confirmed)
                quantityInput = confirmed.toString()
                isEditing = false
            },
            onDismiss = { isEditing = false }
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
 * 数量显示框（自绘面板替代系统键盘）：显示当前值，点击弹出
 * [NumberInputPanel]（自绘数字键盘）——不聚焦、不弹系统 IME。
 * 边框高亮编辑态（面板打开时 Primary 色，与非编辑态视觉区分）。
 */
@Composable
private fun QuantityDisplayBox(
    text: String,
    isEditing: Boolean,
    sizes: QuantitySelectorSizes,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(sizes.numberBoxWidth)
            .height(sizes.numberBoxHeight)
            .clip(RoundedCornerShape(sizes.buttonCornerRadius))
            .border(
                1.dp,
                if (isEditing) GameColors.Primary else GameColors.DividerGray,
                RoundedCornerShape(sizes.buttonCornerRadius)
            )
            .background(Color.White)
            .clickableWithSound(onClick = onClick)
            .testTag("quantity_display"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = Color.Black
        )
    }
}
