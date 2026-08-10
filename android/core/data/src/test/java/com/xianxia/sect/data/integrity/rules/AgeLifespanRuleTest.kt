package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test





class AgeLifespanRuleTest {
    @Before fun setup() { SaveValidationRuleRegistry.clear(); SaveValidationRuleRegistry.register(AgeLifespanRule) }
    @After fun teardown() { SaveValidationRuleRegistry.clear() }

    @Test fun `age within lifespan passes`() {
        val data = saveData(listOf(disciple(age = 50, lifespan = 120)))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test fun `age exceeding maxAge with affix clamps to maxAge not lifespan`() {
        // 带"延年"词条（+28%）弟子：寿元上限 = 80 × 1.28 = 102
        val data = saveData(listOf(disciple(age = 110, lifespan = 80, affixIds = listOf("r3_aff_lifespan"))))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val repaired = result as IntegrityResult.Repaired
        // 引擎口径 computeMaxAge=102 —— 不再回滚到 lifespan=80（原 Bug：回滚死循环永生）
        assertEquals(102, repaired.data.disciples.first().age)
    }

    @Test fun `age exceeding maxAge without affix clamps to maxAge 80`() {
        val data = saveData(listOf(disciple(age = 100, lifespan = 80)))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val repaired = result as IntegrityResult.Repaired
        assertEquals(80, repaired.data.disciples.first().age)
    }

    @Test fun `age within affix-extended lifespan passes`() {
        // age=100 ∈ (lifespan=80, computeMaxAge=102]：合法延寿区间，不截断（原 Bug 会回滚）
        val data = saveData(listOf(disciple(age = 100, lifespan = 80, affixIds = listOf("r3_aff_lifespan"))))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test fun `age beyond absolute ceiling clamps to 20000`() {
        val data = saveData(listOf(disciple(age = 25000, lifespan = 99999)))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(20000, (result as IntegrityResult.Repaired).data.disciples.first().age)
    }

    @Test fun `dead disciple age exceeding lifespan unchanged`() {
        val data = saveData(listOf(disciple(age = 200, lifespan = 80, isAlive = false)))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test fun `age equals lifespan passes`() {
        val data = saveData(listOf(disciple(age = 80, lifespan = 80)))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    private fun disciple(
        age: Int = 20,
        lifespan: Int = 80,
        isAlive: Boolean = true,
        talentIds: List<String> = emptyList(),
        affixIds: List<String> = emptyList()
    ) = Disciple(
        id = "d-1", name = "甲", realm = 9, realmLayer = 1, cultivation = 10.0,
        age = age, lifespan = lifespan, isAlive = isAlive,
        talentIds = talentIds, affixIds = affixIds,
        equipment = EquipmentSet()
    )
    private fun saveData(d: List<Disciple>) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
        disciples = d, pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(), teams = emptyList()
    )
}
