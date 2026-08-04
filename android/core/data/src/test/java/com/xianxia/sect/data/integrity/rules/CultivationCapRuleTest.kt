package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test



class CultivationCapRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(CultivationCapRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    @Test
    fun `cultivation within cap returns Passed`() {
        val d = makeDisciple(realm = 9, realmLayer = 1, cultivation = 30.0)
        val data = saveData(disciples = listOf(d))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `cultivation exceeding max caps`() {
        val d = makeDisciple(realm = 9, realmLayer = 1, cultivation = 999.0)
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(65.0, (result as IntegrityResult.Repaired).data.disciples.first().cultivation, 0.001)
    }

    @Test
    fun `immortal realm cultivation capped at absolute cap`() {
        // T7（2026-08-04）：realm<=0 不再不限制，改用绝对上限 1e9 钳制
        // （原实现 Double.MAX_VALUE，恶意云档可携带巨大 cultivation 穿透）
        val d = makeDisciple(realm = 0, realmLayer = 1, cultivation = 1e12)
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals(1e9, (result as IntegrityResult.Repaired).data.disciples.first().cultivation, 0.001)
    }

    @Test
    fun `immortal realm cultivation below cap passes`() {
        val d = makeDisciple(realm = 0, realmLayer = 1, cultivation = 1e6)
        val data = saveData(disciples = listOf(d))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `cultivation at exact boundary passes`() {
        val d = makeDisciple(realm = 9, realmLayer = 1, cultivation = 65.0)
        val data = saveData(disciples = listOf(d))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `negative cultivation does not crash`() {
        val d = makeDisciple(realm = 9, realmLayer = 1, cultivation = -1.0)
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Passed || result is IntegrityResult.Repaired)
    }

    @Test
    fun `NaN cultivation does not crash`() {
        val d = makeDisciple(realm = 9, realmLayer = 1, cultivation = Double.NaN)
        val data = saveData(disciples = listOf(d))
        // NaN 比较始终为 false: NaN > anything → false, 不会被截断
        val result = SaveValidator.validate(data)
        assertTrue("NaN 不应崩溃", result is IntegrityResult.Passed || result is IntegrityResult.Repaired)
    }

    @Test
    fun `computeMaxCultivation realm 9 layer 1 returns 65`() {
        assertEquals(65.0, computeMaxCultivation(9, 1), 0.001)
    }

    @Test
    fun `computeMaxCultivation realm 0 returns Double MAX_VALUE`() {
        assertEquals(Double.MAX_VALUE, computeMaxCultivation(0, 1), 0.001)
    }

    private fun makeDisciple(
        id: String = "d-1", name: String = "甲", realm: Int = 9,
        realmLayer: Int = 1, cultivation: Double = 10.0,
        age: Int = 20, lifespan: Int = 80, isAlive: Boolean = true
    ) = Disciple(
        id = id, name = name, realm = realm, realmLayer = realmLayer,
        cultivation = cultivation, age = age, lifespan = lifespan, isAlive = isAlive,
        equipment = EquipmentSet(weaponId = "", armorId = "", bootsId = "", accessoryId = "")
    )

    private fun saveData(disciples: List<Disciple>) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
        disciples = disciples, pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(), teams = emptyList()
    )
}
