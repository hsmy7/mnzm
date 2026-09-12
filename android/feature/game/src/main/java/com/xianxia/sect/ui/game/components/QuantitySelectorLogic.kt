package com.xianxia.sect.ui.game.components

/** 数量选择器下限（业务最少 1 个） */
internal const val QUANTITY_MIN = 1

/**
 * 步进钳制：quantity + step 后钳制到 [min, max]。
 *
 * Long 中间量防 Int 溢出；max 小于 min 时以 min 兜底（防御性，调用方应保证 max >= min）；
 * step = 0 时退化为纯钳制（供上限变化时截断复用）。
 *
 * @return 钳制后的数量，始终位于 [min, max]（max 小于 min 时退化为 [min, min]）
 */
internal fun applyStep(quantity: Int, step: Int, min: Int, max: Int): Int {
    val safeMax = maxOf(min, max)
    return (quantity.toLong() + step).coerceIn(min.toLong(), safeMax.toLong()).toInt()
}

/** 输入净化结果：text 为输入框应显示的内容，quantity 为当前提交数量 */
internal data class SanitizedQuantityInput(
    val text: String,
    val quantity: Int,
)

/**
 * 数字输入净化：过滤非数字字符。
 *
 * - 空串/全非数字 → 空文本 + 数量 1（输入框留空待输入）
 * - 全零 → 空文本 + 数量 1
 * - 前导零（如 "007"）→ 保留原文本 + 解析数量 7
 * - 超过 [maxQuantity] 或 Int 溢出（超长数字无法解析）→ 文本与数量均截断为上限
 *   （需求：输入超出物品数量上限时默认为上限）
 *
 * @return [SanitizedQuantityInput]，quantity 始终位于 [1, maxQuantity]
 */
internal fun sanitizeQuantityInput(input: String, maxQuantity: Int): SanitizedQuantityInput {
    val safeMax = maxOf(maxQuantity, QUANTITY_MIN)
    val filtered = input.filter { it.isDigit() }
    val parsed = filtered.toIntOrNull()
    return when {
        // 空串/全非数字/全零 → 空文本待输入（filtered 空时 parsed 必为 null，短路安全）
        filtered.isEmpty() || parsed == 0 ->
            SanitizedQuantityInput("", QUANTITY_MIN)
        // Int 溢出（null）或超过上限 → 文本与数量均截断为上限（需求：超上限自动截断）
        parsed == null || parsed > safeMax ->
            SanitizedQuantityInput(safeMax.toString(), safeMax)
        // 前导零保留原文本（如 "007"），数值用解析结果
        else -> SanitizedQuantityInput(filtered, parsed)
    }
}
