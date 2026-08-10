package com.xianxia.sect.core.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.registry.TalentDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证 [Disciple.computeMaxAge] 扩展函数的正确性。
 *
 * 寿元计算规则：maxOf(lifespan, realmMaxAge, traitLifespan)
 * 硬上限 [ABSOLUTE_MAX_AGE_CEILING] = 20000
 */
class DiscipleAgeCalculatorTest {

    @Test
    fun `炼气弟子 lifespan80 的 maxAge 为 80`() {
        val d = Disciple(id = "1", realm = 9, age = 50, lifespan = 80, talentIds = emptyList())
        assertEquals(80, d.computeMaxAge())
    }

    @Test
    fun `金丹弟子 lifespan80 因 realmMaxAge200 使 maxAge 为 200`() {
        val d = Disciple(id = "2", realm = 7, age = 100, lifespan = 80, talentIds = emptyList())
        assertEquals(GameConfig.Realm.get(7).maxAge, d.computeMaxAge())
    }

    @Test
    fun `筑基弟子 lifespan150 大于 realmMaxAge120 时取 lifespan`() {
        val d = Disciple(id = "3", realm = 8, age = 100, lifespan = 150, talentIds = emptyList())
        assertEquals(150, d.computeMaxAge())
    }

    @Test
    fun `元婴弟子 lifespan300 与 realmMaxAge300 一致`() {
        val d = Disciple(id = "4", realm = 6, age = 200, lifespan = 300, talentIds = emptyList())
        assertEquals(GameConfig.Realm.get(6).maxAge, d.computeMaxAge())
    }

    @Test
    fun `仙人弟子 realm0 的 maxAge 为 9999`() {
        val d = Disciple(id = "5", realm = 0, age = 5000, lifespan = 9999, talentIds = emptyList())
        assertEquals(GameConfig.Realm.get(0).maxAge, d.computeMaxAge())
    }

    @Test
    fun `化神弟子 lifespan50 小于 realmMaxAge500 时取 realmMaxAge`() {
        val d = Disciple(id = "6", realm = 5, age = 400, lifespan = 50, talentIds = emptyList())
        assertEquals(GameConfig.Realm.get(5).maxAge, d.computeMaxAge())
    }

    @Test
    fun `天赋寿元加成45pc使炼气弟子 maxAge 从80提升至116`() {
        val talentIds = listOf("r5_lifespan")
        val bonus = TalentDatabase.calculateTalentEffects(talentIds)["lifespan"] ?: 0.0
        assertEquals(0.45, bonus, 0.001)
        val d = Disciple(id = "7", realm = 9, age = 50, lifespan = 80, talentIds = talentIds)
        val expected = (GameConfig.Realm.get(9).maxAge * (1.0 + bonus)).toInt().coerceAtLeast(1)
        assertEquals(expected, d.computeMaxAge())
    }

    @Test
    fun `天赋寿元加成45pc使金丹弟子 maxAge 从200提升至290`() {
        val talentIds = listOf("r5_lifespan")
        val d = Disciple(id = "8", realm = 7, age = 100, lifespan = 80, talentIds = talentIds)
        val bonus = TalentDatabase.calculateTalentEffects(talentIds)["lifespan"] ?: 0.0
        val expected = maxOf(
            d.lifespan,
            GameConfig.Realm.get(7).maxAge,
            (GameConfig.Realm.get(7).maxAge * (1.0 + bonus)).toInt().coerceAtLeast(1)
        )
        assertEquals(expected, d.computeMaxAge())
    }

    @Test
    fun `lifespan99999因硬上限20000使 maxAge 为20000`() {
        val d = Disciple(id = "9", realm = 9, age = 50, lifespan = 99999, talentIds = emptyList())
        assertEquals(ABSOLUTE_MAX_AGE_CEILING, d.computeMaxAge())
    }

    // ── 2026-08-10 新增：词条（延年/夭折）参与寿元派生 ─────────────────────

    @Test
    fun `词条延年28pc使炼气弟子 maxAge 从80提升至102`() {
        val affixIds = listOf("r3_aff_lifespan")
        val d = Disciple(id = "10", realm = 9, age = 50, lifespan = 80, affixIds = affixIds)
        // 80 × (1 + 0.28) = 102.4 → 102
        assertEquals(102, d.computeMaxAge())
    }

    @Test
    fun `天赋45pc与词条28pc叠加使炼气弟子 maxAge 提升至138`() {
        val talentIds = listOf("r5_lifespan")
        val affixIds = listOf("r3_aff_lifespan")
        val d = Disciple(
            id = "11", realm = 9, age = 50, lifespan = 80,
            talentIds = talentIds, affixIds = affixIds
        )
        // 80 × (1 + 0.45 + 0.28) = 138.4 → 138
        assertEquals(138, d.computeMaxAge())
    }

    @Test
    fun `词条夭折15pc压低特质寿元但不低于 realmMaxAge`() {
        val affixIds = listOf("neg_aff_lifespan")
        val d = Disciple(id = "12", realm = 9, age = 50, lifespan = 80, affixIds = affixIds)
        // 特质寿元 80 × (1 - 0.15) = 68，仍取 max(lifespan=80, realmMaxAge=80) = 80
        assertEquals(80, d.computeMaxAge())
    }
}
