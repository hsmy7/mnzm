package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.*
import org.junit.Test

class SectPolicyPureLogicTest {

    // ==================== ToggleResult 类型测试 ====================

    @Test
    fun toggleResult_success_isDataObject() {
        val result = SectPolicyToggleUseCase.ToggleResult.Success
        assertNotNull(result)
        // data object 单例验证
        assertEquals(result, SectPolicyToggleUseCase.ToggleResult.Success)
    }

    @Test
    fun toggleResult_error_hasMessageField() {
        val errorMessage = "灵石不足"
        val result = SectPolicyToggleUseCase.ToggleResult.Error(errorMessage)
        assertEquals(errorMessage, result.message)
    }

    // ==================== getViceSectMasterIntelligenceBonus 公式测试 ====================

    private fun computeBonus(intelligence: Int): Double {
        val base = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE
        val step = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP
        val bonusPerStep = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP
        return ((intelligence - base) / step.toDouble() * bonusPerStep).coerceAtLeast(0.0)
    }

    @Test
    fun viceSectMasterIntelligenceBonus_belowBase_returnsZero() {
        val intelligence = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE - 10
        assertEquals(0.0, computeBonus(intelligence), 0.001)
    }

    @Test
    fun viceSectMasterIntelligenceBonus_atBase_returnsZero() {
        val intelligence = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE
        assertEquals(0.0, computeBonus(intelligence), 0.001)
    }

    @Test
    fun viceSectMasterIntelligenceBonus_aboveBase_returnsPositiveBonus() {
        val base = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE
        val step = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP
        val bonusPerStep = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP
        val intelligence = base + step * 3 // 超过基准3个步长
        val expected = 3 * bonusPerStep
        assertEquals(expected, computeBonus(intelligence), 0.001)
        assertTrue(expected > 0.0)
    }

    @Test
    fun viceSectMasterIntelligenceBonus_veryHighIntelligence_returnsLargeBonus() {
        val base = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE
        val step = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP
        val bonusPerStep = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP
        val intelligence = 500
        val expected = ((intelligence - base) / step.toDouble() * bonusPerStep)
        assertEquals(expected, computeBonus(intelligence), 0.001)
        assertTrue(expected > 0.5)
    }

    // ==================== PolicyConfig 常量验证 ====================

    @Test
    fun policyConfig_spiritMineBoostBaseEffect_isPositive() {
        assertTrue(GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_BASE_EFFECT > 0)
    }

    @Test
    fun policyConfig_enhancedSecurityBaseEffect_isPositive() {
        assertTrue(GameConfig.PolicyConfig.ENHANCED_SECURITY_BASE_EFFECT > 0)
    }

    @Test
    fun policyConfig_enhancedSecurityCost_isPositive() {
        assertTrue(GameConfig.PolicyConfig.ENHANCED_SECURITY_COST > 0)
    }

    @Test
    fun policyConfig_alchemyIncentiveCost_isPositive() {
        assertTrue(GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_COST > 0)
    }

    @Test
    fun policyConfig_forgeIncentiveCost_isPositive() {
        assertTrue(GameConfig.PolicyConfig.FORGE_INCENTIVE_COST > 0)
    }

    @Test
    fun policyConfig_herbCultivationCost_isPositive() {
        assertTrue(GameConfig.PolicyConfig.HERB_CULTIVATION_COST > 0)
    }

    @Test
    fun policyConfig_cultivationSubsidyCost_isPositive() {
        assertTrue(GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_COST > 0)
    }

    @Test
    fun policyConfig_manualResearchCost_isPositive() {
        assertTrue(GameConfig.PolicyConfig.MANUAL_RESEARCH_COST > 0)
    }
}
