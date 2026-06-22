package com.xianxia.sect.core.engine.domain.settlement

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SettlementCoordinator.calculateBatchCultivationGain] 单元测试。
 *
 * 覆盖历史 bug：曾错误地 `+ alreadyGained` 导致月结时修炼值被重复计算。
 * 焦点路径（processFocusedDiscipleImmediate）与批处理路径（clean/dirty）应行为一致。
 */
class SettlementCoordinatorCultivationGainTest {

    @Test
    fun `calculateBatchCultivationGain - alreadyGained为零时等于月增益乘月数`() {
        // 常规非焦点弟子：焦点域未为其写入任何增益
        val monthlyGain = 300.0
        val alreadyGained = 0.0
        val batchMonths = 3

        val result = SettlementCoordinator.calculateBatchCultivationGain(
            monthlyGain, alreadyGained, batchMonths
        )

        assertEquals(900.0, result, 0.001)
    }

    @Test
    fun `calculateBatchCultivationGain - 焦点域已写入部分增益时扣除已获部分`() {
        // 焦点域已为该弟子写入 100 点修炼值（已持久化到 disciple.cultivation）
        // 月增益 300×3 月=900，扣除已获 100，净增益=800
        val monthlyGain = 300.0
        val alreadyGained = 100.0
        val batchMonths = 3

        val result = SettlementCoordinator.calculateBatchCultivationGain(
            monthlyGain, alreadyGained, batchMonths
        )

        assertEquals(800.0, result, 0.001)
    }

    @Test
    fun `calculateBatchCultivationGain - 已获超过总增益时返回零`() {
        // 焦点域已写入 1000，月增益 300×3 月=900，净值为负，coerceAtLeast(0) 后为 0
        val monthlyGain = 300.0
        val alreadyGained = 1000.0
        val batchMonths = 3

        val result = SettlementCoordinator.calculateBatchCultivationGain(
            monthlyGain, alreadyGained, batchMonths
        )

        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun `calculateBatchCultivationGain - batchMonths为1时与焦点路径一致`() {
        // 焦点路径：netGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)
        // 批处理 batchMonths=1：netMonthlyGain * 1 = netGain
        val monthlyGain = 300.0
        val alreadyGained = 50.0
        val batchMonths = 1

        val batchResult = SettlementCoordinator.calculateBatchCultivationGain(
            monthlyGain, alreadyGained, batchMonths
        )
        val focusedResult = (monthlyGain - alreadyGained).coerceAtLeast(0.0)

        assertEquals(focusedResult, batchResult, 0.001)
    }

    @Test
    fun `calculateBatchCultivationGain - alreadyGained不乘batchMonths`() {
        // 历史 bug 验证：旧实现将 alreadyGained 乘以 batchMonths 导致多扣
        // 修复后 alreadyGained 只减一次：monthlyGain × batchMonths - alreadyGained
        val monthlyGain = 300.0
        val alreadyGained = 100.0
        val batchMonths = 2

        val result = SettlementCoordinator.calculateBatchCultivationGain(
            monthlyGain, alreadyGained, batchMonths
        )

        // 新公式：300×2 - 100 = 500
        assertEquals(500.0, result, 0.001)
    }

    @Test
    fun `calculateBatchCultivationGain - 月增益为零时返回零`() {
        val monthlyGain = 0.0
        val alreadyGained = 100.0
        val batchMonths = 3

        val result = SettlementCoordinator.calculateBatchCultivationGain(
            monthlyGain, alreadyGained, batchMonths
        )

        assertEquals(0.0, result, 0.001)
    }
}
