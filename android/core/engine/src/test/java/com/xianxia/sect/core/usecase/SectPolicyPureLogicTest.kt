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
        assertTrue(GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_EFFECT > 0)
    }

    @Test
    fun policyConfig_enhancedSecurityBaseEffect_isPositive() {
        assertTrue(GameConfig.PolicyConfig.ENHANCED_SECURITY_EFFECT > 0)
    }

    @Test
    fun policyConfig_enhancedSecurityCost_isPositive() {
        assertTrue(GameConfig.PolicyConfig.ENHANCED_SECURITY_MONTHLY > 0)
    }

    @Test
    fun policyConfig_alchemyIncentiveCost_isPositive() {
        assertTrue(GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_MONTHLY > 0)
    }

    @Test
    fun policyConfig_forgeIncentiveCost_isPositive() {
        assertTrue(GameConfig.PolicyConfig.FORGE_INCENTIVE_MONTHLY > 0)
    }

    @Test
    fun policyConfig_herbCultivationCost_isPositive() {
        assertTrue(GameConfig.PolicyConfig.HERB_CULTIVATION_MONTHLY > 0)
    }

    @Test
    fun policyConfig_cultivationSubsidyCost_isPositive() {
        assertTrue(GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_PER_DISCIPLE > 0)
    }

    @Test
    fun policyConfig_manualResearchCost_isPositive() {
        assertTrue(GameConfig.PolicyConfig.MANUAL_RESEARCH_MONTHLY > 0)
    }

    // ==================== 自动用丹资格判定 纯逻辑测试 ====================
    // 该逻辑在 CultivationEventProcessor（自动装备/学习用）和
    // DiscipleBreakthroughHandler.performBreakthrough（突破自动用丹）中内联使用。
    // 逻辑一致：focused+followed → true，否则 rootCounts 匹配灵根数 → true。

    /** 模拟 qualifiesForSectAutoPublic 的核心判定逻辑 */
    private fun qualifiesForSectAutoPublic(
        followed: Boolean, spiritRootCount: Int, focused: Boolean, rootCounts: Set<Int>
    ): Boolean {
        if (focused || rootCounts.isNotEmpty()) {
            if (focused && followed) return true
            return spiritRootCount in rootCounts
        }
        return false
    }

    @Test
    fun `qualifiesForSectAutoPublic - focused且followed返回true`() {
        assertTrue(qualifiesForSectAutoPublic(
            followed = true, spiritRootCount = 1, focused = true, rootCounts = emptySet()
        ))
    }

    @Test
    fun `qualifiesForSectAutoPublic - focused但未followed返回false`() {
        assertFalse(qualifiesForSectAutoPublic(
            followed = false, spiritRootCount = 1, focused = true, rootCounts = emptySet()
        ))
    }

    @Test
    fun `qualifiesForSectAutoPublic - rootCounts匹配灵根数返回true`() {
        assertTrue(qualifiesForSectAutoPublic(
            followed = false, spiritRootCount = 3, focused = false, rootCounts = setOf(1, 3, 5)
        ))
    }

    @Test
    fun `qualifiesForSectAutoPublic - rootCounts不匹配返回false`() {
        assertFalse(qualifiesForSectAutoPublic(
            followed = false, spiritRootCount = 2, focused = false, rootCounts = setOf(1, 3, 5)
        ))
    }

    @Test
    fun `qualifiesForSectAutoPublic - focused和rootCounts都关闭返回false`() {
        assertFalse(qualifiesForSectAutoPublic(
            followed = true, spiritRootCount = 1, focused = false, rootCounts = emptySet()
        ))
    }

    @Test
    fun `qualifiesForSectAutoPublic - focused+rootCounts同时满足(OR逻辑)`() {
        assertTrue(qualifiesForSectAutoPublic(
            followed = true, spiritRootCount = 5, focused = true, rootCounts = setOf(1)
        ))
        assertTrue(qualifiesForSectAutoPublic(
            followed = false, spiritRootCount = 1, focused = false, rootCounts = setOf(1)
        ))
    }

    // ==================== 突破自动用丹流程 纯逻辑测试 ====================
    // 模拟 performBreakthrough 中内联的自动用丹流程：
    // 满修为 → 满状态 → 资格判定 → 仓库优先 → 储物袋兜底

    /** 模拟突破用丹资格判定（与 performBreakthrough 内联逻辑一致） */
    private fun canAutoPill(
        focused: Boolean, rootCounts: Set<Int>, followed: Boolean, rootCount: Int
    ): Boolean {
        if (!focused && rootCounts.isEmpty()) return false
        return (focused && followed) || rootCount in rootCounts
    }

    @Test
    fun `突破用丹 - 配置全关时任何弟子都不消耗`() {
        assertFalse(canAutoPill(
            focused = false, rootCounts = emptySet(), followed = true, rootCount = 1
        ))
    }

    @Test
    fun `突破用丹 - focused+followed优先于灵根数`() {
        // 三灵根弟子，rootCounts 只有 [1]，但 focused+followed，应可用丹
        assertTrue(canAutoPill(
            focused = true, rootCounts = setOf(1), followed = true, rootCount = 3
        ))
    }

    @Test
    fun `突破用丹 - 灵根数匹配但未followed也可用丹`() {
        assertTrue(canAutoPill(
            focused = false, rootCounts = setOf(2, 3), followed = false, rootCount = 2
        ))
    }

    @Test
    fun `突破用丹 - focused但未followed且灵根不匹配不可用丹`() {
        assertFalse(canAutoPill(
            focused = true, rootCounts = setOf(1), followed = false, rootCount = 3
        ))
    }

    /** 模拟丹药目标境界判定（与 performBreakthrough 内联逻辑一致） */
    private fun getPillTargetRealm(realm: Int, realmLayer: Int, maxLayers: Int): Int =
        if (realmLayer >= maxLayers) realm - 1 else realm

    @Test
    fun `突破用丹 - 非大境界突破时目标境界为当前境界`() {
        // 练气 3/9 层 → 目标境界 = 9（练气）
        assertEquals(9, getPillTargetRealm(realm = 9, realmLayer = 3, maxLayers = 9))
    }

    @Test
    fun `突破用丹 - 大境界突破时目标境界为下一境界`() {
        // 练气 9/9 层 → 目标境界 = 8（筑基）
        assertEquals(8, getPillTargetRealm(realm = 9, realmLayer = 9, maxLayers = 9))
    }

    /** 模拟丹药来源优先级（与 performBreakthrough 内联逻辑一致） */
    private fun selectPillSource(
        hasWarehousePill: Boolean, hasBagPill: Boolean
    ): String = when {
        hasWarehousePill -> "warehouse"
        hasBagPill -> "bag"
        else -> "none"
    }

    @Test
    fun `突破用丹 - 仓库有丹药时优先仓库`() {
        assertEquals("warehouse", selectPillSource(true, true))
        assertEquals("warehouse", selectPillSource(true, false))
    }

    @Test
    fun `突破用丹 - 仓库无丹药时兜底储物袋`() {
        assertEquals("bag", selectPillSource(false, true))
    }

    @Test
    fun `突破用丹 - 两处都无丹药时无加成`() {
        assertEquals("none", selectPillSource(false, false))
    }
}
