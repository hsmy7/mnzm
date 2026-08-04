package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.PillEffects
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NumericSanitizeRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(NumericSanitizeRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    @Test
    fun `finite non-negative values pass unchanged`() {
        val d = makeDisciple(cultivation = 10.0, checkpoint = 5.0, speedBonus = 0.2)
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertEquals(IntegrityResult.Passed, result)
    }

    @Test
    fun `unchanged data passes without repairs`() {
        val d = makeDisciple()
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        // 无修复时不应误报 Repaired（引用相等保护下游增量逻辑不误判）
        assertEquals(IntegrityResult.Passed, result)
    }

    @Test
    fun `NaN cultivation reset to zero`() {
        val d = makeDisciple(cultivation = Double.NaN)
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(0.0, (result as IntegrityResult.Repaired).data.disciples.first().cultivation, 0.001)
    }

    @Test
    fun `positive infinity cultivation reset to zero`() {
        val d = makeDisciple(cultivation = Double.POSITIVE_INFINITY)
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(0.0, (result as IntegrityResult.Repaired).data.disciples.first().cultivation, 0.001)
    }

    @Test
    fun `negative infinity checkpoint reset to zero`() {
        val d = makeDisciple(cultivation = 10.0, checkpoint = Double.NEGATIVE_INFINITY)
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(0.0, (result as IntegrityResult.Repaired).data.disciples.first().cultivationCheckpoint, 0.001)
    }

    @Test
    fun `negative cultivation reset to zero`() {
        val d = makeDisciple(cultivation = -5.0)
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(0.0, (result as IntegrityResult.Repaired).data.disciples.first().cultivation, 0.001)
    }

    @Test
    fun `negative cultivation speed bonus reset to zero`() {
        val d = makeDisciple(speedBonus = -1.0)
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(0.0, (result as IntegrityResult.Repaired).data.disciples.first().cultivationSpeedBonus, 0.001)
    }

    @Test
    fun `NaN sect cultivation reset to zero`() {
        val data = saveData(
            gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1, sectCultivation = Double.NaN)
        )
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(0.0, (result as IntegrityResult.Repaired).data.gameData.sectCultivation, 0.001)
    }

    @Test
    fun `NaN cultivation in recruit list sanitized`() {
        val data = saveData(
            gameData = GameData(
                sectName = "宗", gameYear = 1, gameMonth = 1,
                recruitList = listOf(makeDisciple(id = "r-1", cultivation = Double.NaN))
            )
        )
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(
            0.0,
            (result as IntegrityResult.Repaired).data.gameData.recruitList.first().cultivation,
            0.001
        )
    }

    @Test
    fun `NaN cultivation in ai sect disciples sanitized`() {
        val data = saveData(
            gameData = GameData(
                sectName = "宗", gameYear = 1, gameMonth = 1,
                aiSectDisciples = mapOf(
                    "sect-a" to listOf(makeDisciple(id = "a-1", cultivation = Double.POSITIVE_INFINITY))
                )
            )
        )
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(
            0.0,
            (result as IntegrityResult.Repaired).data.gameData.aiSectDisciples.getValue("sect-a").first().cultivation,
            0.001
        )
    }

    @Test
    fun `NaN pill crit bonus reset while other fields preserved`() {
        val d = makeDisciple(
            cultivation = 10.0,
            pill = PillEffects(
                pillCritRateBonus = Double.NaN,
                pillCultivationSpeedBonus = 0.5,
                pillHpBonus = 100
            )
        )
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val fixed = (result as IntegrityResult.Repaired).data.disciples.first()
        assertEquals(0.0, fixed.pillEffects.pillCritRateBonus, 0.001)
        assertEquals(0.5, fixed.pillEffects.pillCultivationSpeedBonus, 0.001)
        assertEquals(100, fixed.pillEffects.pillHpBonus)
        // 未非法字段不变
        assertEquals(10.0, fixed.cultivation, 0.001)
    }

    @Test
    fun `negative pill nurture bonus reset`() {
        val d = makeDisciple(pill = PillEffects(pillNurtureSpeedBonus = -0.3))
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(
            0.0,
            (result as IntegrityResult.Repaired).data.disciples.first().pillEffects.pillNurtureSpeedBonus,
            0.001
        )
    }

    private fun makeDisciple(
        id: String = "d-1",
        name: String = "甲",
        cultivation: Double = 10.0,
        checkpoint: Double = 5.0,
        speedBonus: Double = 0.0,
        pill: PillEffects = PillEffects()
    ) = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1,
        cultivation = cultivation, cultivationCheckpoint = checkpoint,
        cultivationSpeedBonus = speedBonus, pillEffects = pill,
        age = 20, lifespan = 80, isAlive = true,
        equipment = EquipmentSet(weaponId = "", armorId = "", bootsId = "", accessoryId = "")
    )

    private fun saveData(
        disciples: List<Disciple> = listOf(makeDisciple()),
        gameData: GameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1)
    ) = SaveData(
        gameData = gameData,
        disciples = disciples, pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(), teams = emptyList()
    )
}
