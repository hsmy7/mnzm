package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.ItemEffect
import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 DisciplePillManager.classify() 对所有 pillType 和效果组合
 * 都能正确分类，不会崩溃。
 */
class DisciplePillManagerTest {

    // ── 已知 pillType 正确分类 ──

    @Test
    fun `classify - extendLife returns PERMANENT_LIFE`() {
        val effect = ItemEffect(pillType = "extendLife")
        assertEquals(PillRule.PERMANENT_LIFE, DisciplePillManager.classify(effect))
    }

    @Test
    fun `classify - cultivationAdd returns INSTANT_CULTIVATION`() {
        val effect = ItemEffect(pillType = "cultivationAdd")
        assertEquals(PillRule.INSTANT_CULTIVATION, DisciplePillManager.classify(effect))
    }

    @Test
    fun `classify - cultivationSpeed returns SUSTAINED_SPEED`() {
        val effect = ItemEffect(pillType = "cultivationSpeed")
        assertEquals(PillRule.SUSTAINED_SPEED, DisciplePillManager.classify(effect))
    }

    @Test
    fun `classify - breakthrough returns BREAKTHROUGH`() {
        val effect = ItemEffect(pillType = "breakthrough")
        assertEquals(PillRule.BREAKTHROUGH, DisciplePillManager.classify(effect))
    }

    // ── 空 pillType + 属性兜底 ──

    @Test
    fun `classify - empty pillType with base attrs returns PERMANENT_BASE_ATTR`() {
        val effect = ItemEffect(
            pillType = "",
            intelligenceAdd = 5
        )
        assertEquals(PillRule.PERMANENT_BASE_ATTR, DisciplePillManager.classify(effect))
    }

    @Test
    fun `classify - empty pillType with battle attrs returns TEMPORARY_BATTLE`() {
        val effect = ItemEffect(
            pillType = "",
            physicalAttackAdd = 10
        )
        assertEquals(PillRule.TEMPORARY_BATTLE, DisciplePillManager.classify(effect))
    }

    // ── 空 pillType + 治疗/恢复效果（之前会崩溃） ──

    @Test
    fun `classify - empty pillType with healMaxHpPercent returns INSTANT_CULTIVATION`() {
        val effect = ItemEffect(
            pillType = "",
            healMaxHpPercent = 30.0
        )
        assertEquals(PillRule.INSTANT_CULTIVATION, DisciplePillManager.classify(effect))
    }

    @Test
    fun `classify - empty pillType with revive returns INSTANT_CULTIVATION`() {
        val effect = ItemEffect(
            pillType = "",
            revive = true
        )
        assertEquals(PillRule.INSTANT_CULTIVATION, DisciplePillManager.classify(effect))
    }

    @Test
    fun `classify - empty pillType with clearAll returns INSTANT_CULTIVATION`() {
        val effect = ItemEffect(
            pillType = "",
            clearAll = true
        )
        assertEquals(PillRule.INSTANT_CULTIVATION, DisciplePillManager.classify(effect))
    }

    @Test
    fun `classify - empty pillType with mpRecoverMaxMpPercent returns INSTANT_CULTIVATION`() {
        val effect = ItemEffect(
            pillType = "",
            mpRecoverMaxMpPercent = 50.0
        )
        assertEquals(PillRule.INSTANT_CULTIVATION, DisciplePillManager.classify(effect))
    }

    // ── 关键回归：空效果不崩溃 ──

    @Test
    fun `classify - empty pillType with all-zero effects returns INSTANT_CULTIVATION no crash`() {
        val effect = ItemEffect(pillType = "", pillCategory = "CULTIVATION")
        val result = DisciplePillManager.classify(effect)
        assertEquals(PillRule.INSTANT_CULTIVATION, result)
    }

    // ── hasAnyHealingEffect 辅助函数 ──

    @Test
    fun `hasAnyHealingEffect - all false for empty effect`() {
        val effect = ItemEffect()
        assertFalse(DisciplePillManager.hasAnyHealingEffect(effect))
    }

    @Test
    fun `hasAnyHealingEffect - true when healMaxHpPercent is positive`() {
        val effect = ItemEffect(healMaxHpPercent = 10.0)
        assertTrue(DisciplePillManager.hasAnyHealingEffect(effect))
    }

    @Test
    fun `hasAnyHealingEffect - true when revive is true`() {
        val effect = ItemEffect(revive = true)
        assertTrue(DisciplePillManager.hasAnyHealingEffect(effect))
    }

    // ── hasAnyBaseAttrAdd / hasAnyBattleAttrAdd 边界 ──

    @Test
    fun `hasAnyBaseAttrAdd - false for empty effect`() {
        val effect = ItemEffect()
        assertFalse(DisciplePillManager.hasAnyBaseAttrAdd(effect))
    }

    @Test
    fun `hasAnyBattleAttrAdd - false for empty effect`() {
        val effect = ItemEffect()
        assertFalse(DisciplePillManager.hasAnyBattleAttrAdd(effect))
    }
}
