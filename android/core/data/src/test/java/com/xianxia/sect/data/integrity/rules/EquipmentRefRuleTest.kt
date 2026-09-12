package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test





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

    @Test
    fun `valid equipment refs return Passed`() {
        val d = makeDisciple(weaponId = "sword-1")
        val stack = EquipmentStack(id = "sword-1", name = "剑", rarity = 1, description = "")
        val data = saveData(disciples = listOf(d), stacks = listOf(stack))
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
        val d = makeDisciple(weaponId = "w", armorId = "a", bootsId = "b", accessoryId = "c")
        val data = saveData(disciples = listOf(d))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val equip = (result as IntegrityResult.Repaired).data.disciples.first().equipment
        assertEquals("", equip.weaponId)
        assertEquals("", equip.armorId)
        assertEquals("", equip.bootsId)
        assertEquals("", equip.accessoryId)
    }

    @Test
    fun `ref to equipment instance is valid`() {
        val d = makeDisciple(weaponId = "inst-1")
        val inst = EquipmentInstance(id = "inst-1", name = "神兵", rarity = 1, description = "")
        val data = saveData(disciples = listOf(d), instances = listOf(inst))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `no disciples returns Passed`() {
        val data = saveData()
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    private fun makeDisciple(
        id: String = "d-1", name: String = "甲",
        weaponId: String = "", armorId: String = "", bootsId: String = "", accessoryId: String = ""
    ) = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1, cultivation = 10.0,
        age = 20, lifespan = 80, isAlive = true,
        equipment = EquipmentSet(weaponId = weaponId, armorId = armorId,
            bootsId = bootsId, accessoryId = accessoryId)
    )

    private fun saveData(
        disciples: List<Disciple> = emptyList(),
        stacks: List<EquipmentStack> = emptyList(),
        instances: List<EquipmentInstance> = emptyList()
    ) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
        disciples = disciples, equipmentStacks = stacks, equipmentInstances = instances,
        pills = emptyList(), materials = emptyList(), herbs = emptyList(),
        seeds = emptyList()
    )
}
