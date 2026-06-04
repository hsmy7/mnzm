package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyService
import org.junit.Assert.*
import org.junit.Test

class UseCaseModelsTest {

    // ==================== BattleStats ====================

    @Test
    fun battleStats_defaultConstruction() {
        val stats = GetBattleStatsUseCase.BattleStats(totalBattles = 0, winRate = 0.0)
        assertEquals(0, stats.totalBattles)
        assertEquals(0.0, stats.winRate, 0.001)
    }

    @Test
    fun battleStats_dataClassEquality() {
        val a = GetBattleStatsUseCase.BattleStats(totalBattles = 10, winRate = 0.75)
        val b = GetBattleStatsUseCase.BattleStats(totalBattles = 10, winRate = 0.75)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun battleStats_dataClassCopy() {
        val original = GetBattleStatsUseCase.BattleStats(totalBattles = 5, winRate = 0.6)
        val copied = original.copy(totalBattles = 20)
        assertEquals(20, copied.totalBattles)
        assertEquals(0.6, copied.winRate, 0.001)
    }

    @Test
    fun battleStats_dataClassInequality() {
        val a = GetBattleStatsUseCase.BattleStats(totalBattles = 10, winRate = 0.75)
        val b = GetBattleStatsUseCase.BattleStats(totalBattles = 10, winRate = 0.50)
        assertNotEquals(a, b)
    }

    // ==================== ToggleResult ====================

    @Test
    fun toggleResult_successIsSingleton() {
        val a = SectPolicyToggleUseCase.ToggleResult.Success
        val b = SectPolicyToggleUseCase.ToggleResult.Success
        assertSame(a, b)
    }

    @Test
    fun toggleResult_errorHoldsMessage() {
        val error = SectPolicyToggleUseCase.ToggleResult.Error("灵石不足")
        assertEquals("灵石不足", error.message)
    }

    @Test
    fun toggleResult_errorEquality() {
        val a = SectPolicyToggleUseCase.ToggleResult.Error("msg")
        val b = SectPolicyToggleUseCase.ToggleResult.Error("msg")
        assertEquals(a, b)
    }

    @Test
    fun toggleResult_errorInequality() {
        val a = SectPolicyToggleUseCase.ToggleResult.Error("a")
        val b = SectPolicyToggleUseCase.ToggleResult.Error("b")
        assertNotEquals(a, b)
    }

    @Test
    fun toggleResult_successNotEqualToError() {
        val success = SectPolicyToggleUseCase.ToggleResult.Success
        val error = SectPolicyToggleUseCase.ToggleResult.Error("err")
        assertNotEquals(success, error)
    }

    // ==================== AllianceResult ====================

    @Test
    fun allianceResult_successConstruction() {
        val result = ManageAllianceUseCase.AllianceResult(success = true, message = "结盟成功")
        assertTrue(result.success)
        assertEquals("结盟成功", result.message)
    }

    @Test
    fun allianceResult_failureConstruction() {
        val result = ManageAllianceUseCase.AllianceResult(success = false, message = "游说失败")
        assertFalse(result.success)
        assertEquals("游说失败", result.message)
    }

    @Test
    fun allianceResult_equality() {
        val a = ManageAllianceUseCase.AllianceResult(true, "ok")
        val b = ManageAllianceUseCase.AllianceResult(true, "ok")
        assertEquals(a, b)
    }

    @Test
    fun allianceResult_copy() {
        val original = ManageAllianceUseCase.AllianceResult(true, "ok")
        val modified = original.copy(success = false)
        assertFalse(modified.success)
        assertEquals("ok", modified.message)
    }

    // ==================== DiplomacyService.GiftResult ====================

    @Test
    fun giftResult_defaultValues() {
        val result = DiplomacyService.GiftResult(success = true)
        assertTrue(result.success)
        assertFalse(result.rejected)
        assertEquals(0, result.favorChange)
        assertEquals(0, result.newFavor)
        assertEquals("", result.message)
        assertEquals("", result.responseType)
    }

    @Test
    fun giftResult_fullConstruction() {
        val result = DiplomacyService.GiftResult(
            success = true,
            rejected = false,
            favorChange = 15,
            newFavor = 65,
            message = "送礼成功",
            responseType = "accept"
        )
        assertTrue(result.success)
        assertEquals(15, result.favorChange)
        assertEquals(65, result.newFavor)
        assertEquals("accept", result.responseType)
    }

    @Test
    fun giftResult_equality() {
        val a = DiplomacyService.GiftResult(true, false, 10, 50, "ok", "accept")
        val b = DiplomacyService.GiftResult(true, false, 10, 50, "ok", "accept")
        assertEquals(a, b)
    }

    @Test
    fun giftResult_rejectedState() {
        val result = DiplomacyService.GiftResult(
            success = false,
            rejected = true,
            responseType = "rejected",
            message = "对方拒绝了你的礼物"
        )
        assertFalse(result.success)
        assertTrue(result.rejected)
    }
}
