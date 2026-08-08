package com.xianxia.sect.ui.game.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 数量选择器纯函数测试（正常路径 + 边界 + 异常路径）。
 * 与 QuantitySelector 组件解耦——组件仅做薄接线，计算逻辑全在此处。
 */
class QuantitySelectorLogicTest {

    // ── applyStep 正常路径 ──────────────────────────────────────────────

    @Test
    fun `applyStep - 正常加一返回 quantity + 1`() {
        assertEquals(6, applyStep(5, 1, QUANTITY_MIN, 100))
    }

    @Test
    fun `applyStep - 正常减一返回 quantity - 1`() {
        assertEquals(4, applyStep(5, -1, QUANTITY_MIN, 100))
    }

    @Test
    fun `applyStep - 正常加十返回 quantity + 10`() {
        assertEquals(15, applyStep(5, 10, QUANTITY_MIN, 100))
    }

    @Test
    fun `applyStep - 正常减十返回 quantity - 10`() {
        assertEquals(5, applyStep(15, -10, QUANTITY_MIN, 100))
    }

    // ── applyStep 边界 ──────────────────────────────────────────────────

    @Test
    fun `applyStep - 下限处减一钳制为 1`() {
        assertEquals(1, applyStep(1, -1, QUANTITY_MIN, 100))
    }

    @Test
    fun `applyStep - 减十低于下限钳制为 1`() {
        assertEquals(1, applyStep(5, -10, QUANTITY_MIN, 100))
    }

    @Test
    fun `applyStep - 加十超上限钳制为 max`() {
        assertEquals(100, applyStep(95, 10, QUANTITY_MIN, 100))
    }

    @Test
    fun `applyStep - 处于上限时正步进保持不变`() {
        assertEquals(100, applyStep(100, 10, QUANTITY_MIN, 100))
    }

    @Test
    fun `applyStep - 处于下限时负步进保持不变`() {
        assertEquals(1, applyStep(1, -10, QUANTITY_MIN, 100))
    }

    @Test
    fun `applyStep - 步进 0 退化为纯钳制`() {
        assertEquals(50, applyStep(50, 0, QUANTITY_MIN, 100))
        assertEquals(100, applyStep(500, 0, QUANTITY_MIN, 100))
    }

    // ── applyStep 异常路径 ──────────────────────────────────────────────

    @Test
    fun `applyStep - max 小于 min 时不崩溃且按 min 兜底`() {
        assertEquals(10, applyStep(5, 1, 10, 3))
    }

    @Test
    fun `applyStep - Int 最大值附近不溢出崩溃`() {
        assertEquals(Int.MAX_VALUE, applyStep(Int.MAX_VALUE - 5, 10, QUANTITY_MIN, Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, applyStep(Int.MAX_VALUE, 10, QUANTITY_MIN, Int.MAX_VALUE))
    }

    // ── sanitizeQuantityInput 正常路径 ──────────────────────────────────

    @Test
    fun `sanitize - 纯数字透传`() {
        assertEquals(SanitizedQuantityInput("42", 42), sanitizeQuantityInput("42", 100))
    }

    @Test
    fun `sanitize - 非数字字符被过滤`() {
        assertEquals(SanitizedQuantityInput("123", 123), sanitizeQuantityInput("a1b2c3", 200))
    }

    @Test
    fun `sanitize - 前导零保留文本但解析正确`() {
        assertEquals(SanitizedQuantityInput("007", 7), sanitizeQuantityInput("007", 100))
    }

    @Test
    fun `sanitize - 上限边界值透传`() {
        assertEquals(SanitizedQuantityInput("100", 100), sanitizeQuantityInput("100", 100))
    }

    // ── sanitizeQuantityInput 边界 ──────────────────────────────────────

    @Test
    fun `sanitize - 空串返回空文本与数量 1`() {
        assertEquals(SanitizedQuantityInput("", 1), sanitizeQuantityInput("", 100))
    }

    @Test
    fun `sanitize - 全非数字字符返回空`() {
        assertEquals(SanitizedQuantityInput("", 1), sanitizeQuantityInput("abc", 100))
    }

    @Test
    fun `sanitize - 全零输入清空并置数量 1`() {
        assertEquals(SanitizedQuantityInput("", 1), sanitizeQuantityInput("000", 100))
    }

    // ── sanitizeQuantityInput 异常路径 ──────────────────────────────────

    @Test
    fun `sanitize - 超上限截断为上限`() {
        assertEquals(SanitizedQuantityInput("100", 100), sanitizeQuantityInput("150", 100))
    }

    @Test
    fun `sanitize - 超长数字 Int 溢出时截断为上限`() {
        assertEquals(SanitizedQuantityInput("100", 100), sanitizeQuantityInput("99999999999", 100))
    }

    @Test
    fun `sanitize - 上限为 1 时大于 1 的输入截断为 1`() {
        assertEquals(SanitizedQuantityInput("1", 1), sanitizeQuantityInput("5", 1))
    }

    @Test
    fun `sanitize - 上限为 Int 最大值时溢出输入截断为上限`() {
        assertEquals(
            SanitizedQuantityInput(Int.MAX_VALUE.toString(), Int.MAX_VALUE),
            sanitizeQuantityInput("2147483648", Int.MAX_VALUE)
        )
    }
}
