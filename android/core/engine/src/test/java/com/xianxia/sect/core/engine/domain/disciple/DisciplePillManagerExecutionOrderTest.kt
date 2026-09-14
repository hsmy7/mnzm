package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.ItemEffect
import org.junit.Assert.*
import org.junit.Test

/**
 * DisciplePillManager 丹药分类规则纯函数测试。
 */
class DisciplePillManagerExecutionOrderTest {

    @Test
    fun `classify - permanent base attr pill maps to PERMANENT_BASE_ATTR`() {
        assertEquals(PillRule.PERMANENT_BASE_ATTR,
            DisciplePillManager.classify(ItemEffect(pillType = "intelligence", intelligenceAdd = 3)))
        assertEquals(PillRule.PERMANENT_BASE_ATTR,
            DisciplePillManager.classify(ItemEffect(pillType = "pillRefining", pillRefiningAdd = 3)))
        assertEquals(PillRule.PERMANENT_BASE_ATTR,
            DisciplePillManager.classify(ItemEffect(pillType = "charm", charmAdd = 3)))
    }

    @Test
    fun `classify - extend life maps to PERMANENT_LIFE`() {
        assertEquals(PillRule.PERMANENT_LIFE,
            DisciplePillManager.classify(ItemEffect(pillType = "extendLife", extendLife = 5)))
    }

    @Test
    fun `classify - instant cultivation pill maps to INSTANT_CULTIVATION`() {
        assertEquals(PillRule.INSTANT_CULTIVATION,
            DisciplePillManager.classify(ItemEffect(pillType = "cultivationAdd", cultivationAdd = 100)))
        assertEquals(PillRule.INSTANT_CULTIVATION,
            DisciplePillManager.classify(ItemEffect(pillType = "skillExpAdd", skillExpAdd = 50)))
        assertEquals(PillRule.INSTANT_CULTIVATION,
            DisciplePillManager.classify(ItemEffect(pillType = "nurtureAdd", nurtureAdd = 100)))
    }

    @Test
    fun `classify - sustained speed pill maps to SUSTAINED_SPEED`() {
        assertEquals(PillRule.SUSTAINED_SPEED,
            DisciplePillManager.classify(ItemEffect(pillType = "cultivationSpeed", cultivationSpeedPercent = 0.3)))
        assertEquals(PillRule.SUSTAINED_SPEED,
            DisciplePillManager.classify(ItemEffect(pillType = "skillExpSpeed", skillExpSpeedPercent = 0.3)))
        assertEquals(PillRule.SUSTAINED_SPEED,
            DisciplePillManager.classify(ItemEffect(pillType = "nurtureSpeed", nurtureSpeedPercent = 0.3)))
    }

    @Test
    fun `classify - breakthrough pill maps to BREAKTHROUGH`() {
        assertEquals(PillRule.BREAKTHROUGH,
            DisciplePillManager.classify(ItemEffect(pillType = "breakthrough", breakthroughChance = 0.1)))
    }

    @Test
    fun `classify - battle attr pill maps to TEMPORARY_BATTLE`() {
        assertEquals(PillRule.TEMPORARY_BATTLE,
            DisciplePillManager.classify(ItemEffect(pillType = "physicalAttack", physicalAttackAdd = 10)))
        assertEquals(PillRule.TEMPORARY_BATTLE,
            DisciplePillManager.classify(ItemEffect(pillType = "hp", hpAdd = 100)))
        assertEquals(PillRule.TEMPORARY_BATTLE,
            DisciplePillManager.classify(ItemEffect(pillType = "speed", speedAdd = 10)))
    }
}
