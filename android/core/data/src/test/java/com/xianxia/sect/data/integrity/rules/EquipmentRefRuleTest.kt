package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipInstanceMeta
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * EquipmentRefRule 六部位引用校验测试（B3：孤立部位引用清除）。
 */
class EquipmentRefRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(EquipmentRefRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    private fun instance(id: String) = EquipmentInstance(
        id = id,
        name = "部件",
        setId = "lietian",
        part = EquipmentSlot.WEAPON,
        growth = EquipGrowth(affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 0.0))),
        meta = EquipInstanceMeta()
    )

    @Test
    fun `valid equipment refs return Passed`() {
        val d = makeDisciple(weaponId = "sword-1")
        val data = saveData(disciples = listOf(d), instances = listOf(instance("sword-1")))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `orphan weaponId is cleared`() {
        val d = makeDisciple(weaponId = "ghost-weapon")
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertEquals("", (result as IntegrityResult.Repaired).data.disciples.first().equipment.weaponId)
    }

    @Test
    fun `all orphan fields are cleared`() {
        val d = makeDisciple(
            headId = "h", bodyId = "b", handsId = "ha", feetId = "f",
            weaponId = "w", legsId = "l"
        )
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val equip = (result as IntegrityResult.Repaired).data.disciples.first().equipment
        assertEquals("", equip.weaponId)
        assertEquals("", equip.headId)
        assertEquals("", equip.legsId)
    }

    @Test
    fun `ref to equipment instance is valid`() {
        val d = makeDisciple(weaponId = "inst-1")
        val data = saveData(disciples = listOf(d), instances = listOf(instance("inst-1")))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `no disciples returns Passed`() {
        val data = saveData()
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    private fun makeDisciple(
        id: String = "d-1", name: String = "甲",
        headId: String = "", bodyId: String = "", handsId: String = "",
        feetId: String = "", weaponId: String = "", legsId: String = ""
    ) = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1, cultivation = 10.0,
        isAlive = true,
        equipment = EquipmentSet(
            headId = headId, bodyId = bodyId, handsId = handsId,
            feetId = feetId, weaponId = weaponId, legsId = legsId
        )
    )

    private fun saveData(
        disciples: List<Disciple> = emptyList(),
        instances: List<EquipmentInstance> = emptyList()
    ): SaveData = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
        disciples = disciples,
        equipmentInstances = instances,
        pills = emptyList(),
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList()
    )
}
