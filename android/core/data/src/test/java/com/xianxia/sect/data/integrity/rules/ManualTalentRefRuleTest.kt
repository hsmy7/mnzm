package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.model.SaveData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ManualTalentRefRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(ManualTalentRefRule)
    }

    @Test
    fun `manual ids referencing existing stacks kept`() {
        val data = saveData(
            manualStacks = listOf(ManualStack(id = "manual-1", name = "心法")),
            disciples = listOf(makeDisciple(manualIds = listOf("manual-1")))
        )
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `manual ids referencing existing instances kept`() {
        val data = saveData(
            manualInstances = listOf(ManualInstance(id = "manual-inst-1", name = "心法")),
            disciples = listOf(makeDisciple(manualIds = listOf("manual-inst-1")))
        )
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `dangling manual ids removed`() {
        val data = saveData(
            manualStacks = listOf(ManualStack(id = "manual-1", name = "心法")),
            disciples = listOf(
                makeDisciple(id = "d-1", manualIds = listOf("manual-1", "ghost-manual")),
                makeDisciple(id = "d-2", manualIds = listOf("ghost-manual-2"))
            )
        )
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val fixed = (result as IntegrityResult.Repaired).data.disciples
        assertEquals(listOf("manual-1"), fixed.first { it.id == "d-1" }.manualIds)
        assertTrue(fixed.first { it.id == "d-2" }.manualIds.isEmpty())
    }

    @Test
    fun `valid talent ids kept`() {
        // r1_cult_speed 是 TalentDatabase 注册的真实天赋 id
        val data = saveData(disciples = listOf(makeDisciple(talentIds = listOf("r1_cult_speed"))))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `fake talent ids removed`() {
        val data = saveData(
            disciples = listOf(
                makeDisciple(talentIds = listOf("r1_cult_speed", "fake-talent-xyz")),
                makeDisciple(id = "d-2", talentIds = listOf("another-fake"))
            )
        )
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val fixed = (result as IntegrityResult.Repaired).data.disciples
        assertEquals(listOf("r1_cult_speed"), fixed.first { it.id == "d-1" }.talentIds)
        assertTrue(fixed.first { it.id == "d-2" }.talentIds.isEmpty())
    }

    @Test
    fun `empty id lists pass`() {
        val data = saveData(disciples = listOf(makeDisciple()))
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    private fun makeDisciple(
        id: String = "d-1",
        name: String = "甲",
        manualIds: List<String> = emptyList(),
        talentIds: List<String> = emptyList()
    ) = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1, cultivation = 10.0,
        age = 20, lifespan = 80, isAlive = true,
        manualIds = manualIds, talentIds = talentIds,
        equipment = EquipmentSet(weaponId = "", armorId = "", bootsId = "", accessoryId = "")
    )

    private fun saveData(
        disciples: List<Disciple>,
        manualStacks: List<ManualStack> = emptyList(),
        manualInstances: List<ManualInstance> = emptyList()
    ) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 5, gameMonth = 6),
        disciples = disciples, pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(),
        manualStacks = manualStacks, manualInstances = manualInstances
    )
}
