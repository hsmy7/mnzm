package com.xianxia.sect.core.model.production

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生产槽毫秒孪生判据测试（结算改造 2026-09-27 B5；方案 §3.4.4）。
 *
 * 覆盖：SlotStateMachine 启动双写 / 重置清孪生 / ProductionSlot 毫秒派生
 * 方法（孪生路径 + 零值回退路径）。C++ 同源锚点：production_test.cpp
 * ProductionMsTwinTest 组（两测互为锚点）。
 */
class ProductionSlotMsJudgementTest {

    private val startAt = GameConfig.Time.calendarToGameMs(3, 5, 0)

    private fun workingSlot(
        duration: Int = 2,
        startedAtGameMs: Long = startAt,
        completeAtGameMs: Long = startAt + duration * GameConfig.Time.GAME_MS_PER_MONTH,
        startYear: Int = 3,
        startMonth: Int = 5
    ) = ProductionSlot(
        slotIndex = 0,
        buildingType = BuildingType.ALCHEMY,
        buildingId = "alchemy",
        status = ProductionSlotStatus.WORKING,
        startYear = startYear,
        startMonth = startMonth,
        duration = duration,
        assignedDiscipleId = "d1",
        startedAtGameMs = startedAtGameMs,
        completeAtGameMs = completeAtGameMs
    )

    // ── SlotStateMachine 启动双写 ──

    @Test
    fun `startProduction writes ms twins anchored at month start`() {
        val slot = ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY)
        val spec = ProductionStartSpec(
            recipeId = "r1",
            recipeName = "丹",
            duration = 3,
            currentYear = 2,
            currentMonth = 7,
            discipleId = "d1",
            discipleName = "弟子",
            successRate = 0.5,
            materials = emptyMap(),
            outputItemId = "r1",
            outputItemName = "丹",
            outputItemRarity = 1
        )

        val result = SlotStateMachine.startProduction(slot, spec).getOrThrow()

        val expectedStart = GameConfig.Time.calendarToGameMs(2, 7, 0)
        assertEquals(expectedStart, result.startedAtGameMs)
        assertEquals(
            expectedStart + 3 * GameConfig.Time.GAME_MS_PER_MONTH,
            result.completeAtGameMs
        )
        // 旧月+旬编码保持原语义（双轨并行）；绝对月口径 = y*12+m
        //（B6 §9.1-14 统一，与 checkpoint/C++ startSlotWorking 同源）
        assertEquals(2 * 12 + 7 + 3, result.completionMonth)
    }

    @Test
    fun `startProduction coerces zero duration to one month in ms twin`() {
        val slot = ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.FORGE)
        val spec = ProductionStartSpec(
            recipeId = "r1", recipeName = "器", duration = 0,
            currentYear = 1, currentMonth = 1, discipleId = "d1", discipleName = "弟子",
            successRate = 1.0, materials = emptyMap(),
            outputItemId = "r1", outputItemName = "器", outputItemRarity = 1
        )

        val result = SlotStateMachine.startProduction(slot, spec).getOrThrow()

        val expectedStart = GameConfig.Time.calendarToGameMs(1, 1, 0)
        assertEquals(expectedStart + GameConfig.Time.GAME_MS_PER_MONTH, result.completeAtGameMs)
    }

    @Test
    fun `resetSlot clears ms twins with calendar fields`() {
        val slot = workingSlot()
        val reset = SlotStateMachine.resetSlot(slot).getOrThrow()
        assertEquals(0L, reset.startedAtGameMs)
        assertEquals(0L, reset.completeAtGameMs)
    }

    // ── ProductionSlot 毫秒派生方法 ──

    @Test
    fun `isFinishedMs true exactly at completeAt`() {
        val slot = workingSlot(duration = 2)
        val completeAt = startAt + 2 * GameConfig.Time.GAME_MS_PER_MONTH
        assertFalse(slot.isFinishedMs(completeAt - 1))
        assertTrue(slot.isFinishedMs(completeAt))
    }

    @Test
    fun `isFinishedMs falls back to calendar judgement when twin is zero`() {
        val slot = workingSlot(duration = 2, startedAtGameMs = 0L, completeAtGameMs = 0L)
        // 日历 (3,7)：elapsed=2 >= duration=2 → 到期（与 (3,5) 开工 + 2 月一致）
        assertTrue(slot.isFinishedMs(GameConfig.Time.calendarToGameMs(3, 7, 0)))
        // 日历 (3,6)：elapsed=1 < 2 → 未到期
        assertFalse(slot.isFinishedMs(GameConfig.Time.calendarToGameMs(3, 6, 0)))
    }

    @Test
    fun `remainingTimeMs counts down to zero at completeAt`() {
        val slot = workingSlot(duration = 2)
        val total = 2 * GameConfig.Time.GAME_MS_PER_MONTH
        assertEquals(total, slot.remainingTimeMs(startAt))
        assertEquals(total / 2, slot.remainingTimeMs(startAt + total / 2))
        assertEquals(0L, slot.remainingTimeMs(startAt + total))
        // 越过完成时点不为负
        assertEquals(0L, slot.remainingTimeMs(startAt + total + 12345))
    }

    @Test
    fun `getProgressFractionMs is linear and clamped`() {
        val slot = workingSlot(duration = 2)
        val total = 2 * GameConfig.Time.GAME_MS_PER_MONTH
        assertEquals(0f, slot.getProgressFractionMs(startAt), 1e-6f)
        assertEquals(0.5f, slot.getProgressFractionMs(startAt + total / 2), 1e-6f)
        assertEquals(1f, slot.getProgressFractionMs(startAt + total), 1e-6f)
        // 越界钳制
        assertEquals(1f, slot.getProgressFractionMs(startAt + total + 9999), 1e-6f)
    }

    @Test
    fun `non-working slot reports finished per status not time`() {
        val idle = workingSlot().copy(status = ProductionSlotStatus.IDLE)
        assertFalse(idle.isFinishedMs(startAt))
        assertEquals(0L, idle.remainingTimeMs(startAt))

        val completed = workingSlot().copy(status = ProductionSlotStatus.COMPLETED)
        assertTrue(completed.isFinishedMs(0L))
    }
}
