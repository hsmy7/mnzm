package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StorageBagUtils 回归测试：验证归还装备/功法入库时走 StackableItemStore 统一合并
 * （多次归还同种物品 → 合并为单个堆叠；excludeStackId 排除；满堆叠新建）。
 */
class StorageBagUtilsTest {

    private fun newState(): MutableGameState = MutableGameState(
        gameData = GameData(id = "test", slotId = 1),
        discipleTables = DiscipleTables(),
        equipmentStacks = EntityStore(),
        equipmentInstances = EntityStore(),
        manualStacks = EntityStore(),
        manualInstances = EntityStore(),
        pills = EntityStore(),
        materials = EntityStore(),
        herbs = EntityStore(),
        seeds = EntityStore(),
        storageBags = EntityStore(),
        teams = emptyList(),
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    @Test
    fun `addEquipmentInstanceToDiscipleBag - merges into existing stack`() {
        val state = newState()
        state.equipmentStacks.add(
            EquipmentStack(id = "s1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 3)
        )
        val instance = EquipmentInstance(id = "i1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON)
        val result = state.addEquipmentInstanceToDiscipleBag(
            disciple = Disciple(), instance = instance,
            bagStackIds = emptySet(), gameYear = 1, gameMonth = 1, gamePhase = 1,
            maxStackSize = 999
        )
        assertEquals("s1", result.storageItemId)
        assertEquals(4, state.equipmentStacks.get("s1")!!.quantity)
        assertNull(state.equipmentInstances.get("i1"))
        assertEquals(1, state.equipmentStacks.size)
    }

    @Test
    fun `addEquipmentInstanceToDiscipleBag - multiple returns create single stack`() {
        val state = newState()
        repeat(3) { i ->
            state.addEquipmentInstanceToDiscipleBag(
                disciple = Disciple(),
                instance = EquipmentInstance(id = "i$i", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON),
                bagStackIds = emptySet(), gameYear = 1, gameMonth = 1, gamePhase = 1,
                maxStackSize = 999
            )
        }
        assertEquals(1, state.equipmentStacks.size)
        assertEquals(3, state.equipmentStacks.all().first().quantity)
    }

    @Test
    fun `addEquipmentInstanceToDiscipleBag - excludeStackId not merged and preserved`() {
        val state = newState()
        state.equipmentStacks.add(
            EquipmentStack(id = "s1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 1)
        )
        state.equipmentStacks.add(
            EquipmentStack(id = "s2", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 1)
        )
        val instance = EquipmentInstance(id = "i1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON)
        val result = state.addEquipmentInstanceToDiscipleBag(
            disciple = Disciple(), instance = instance,
            bagStackIds = setOf("s1"), excludeStackId = "s1",
            gameYear = 1, gameMonth = 1, gamePhase = 1,
            maxStackSize = 999
        )
        // 合并进非排除的 s2；s1（背包引用堆叠）保留且数量不变
        assertEquals("s2", result.storageItemId)
        assertEquals(2, state.equipmentStacks.size)
        assertEquals(1, state.equipmentStacks.get("s1")!!.quantity)
        assertEquals(2, state.equipmentStacks.get("s2")!!.quantity)
    }

    @Test
    fun `addEquipmentInstanceToDiscipleBag - full stack creates new stack`() {
        val state = newState()
        state.equipmentStacks.add(
            EquipmentStack(id = "s1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 999)
        )
        state.addEquipmentInstanceToDiscipleBag(
            disciple = Disciple(),
            instance = EquipmentInstance(id = "i1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON),
            bagStackIds = emptySet(), gameYear = 1, gameMonth = 1, gamePhase = 1,
            maxStackSize = 999
        )
        assertEquals(2, state.equipmentStacks.size)
        assertTrue(state.equipmentStacks.all().any { it.quantity == 1 })
    }

    @Test
    fun `addManualInstanceToDiscipleBag - merges into existing stack`() {
        val state = newState()
        state.manualStacks.add(
            ManualStack(id = "m1", name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK, quantity = 2)
        )
        val instance = ManualInstance(id = "mi1", name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK)
        val result = state.addManualInstanceToDiscipleBag(
            disciple = Disciple(), instance = instance,
            bagStackIds = emptySet(), gameYear = 1, gameMonth = 1, gamePhase = 1,
            maxStackSize = 999
        )
        assertEquals("m1", result.storageItemId)
        assertEquals(3, state.manualStacks.get("m1")!!.quantity)
        assertNull(state.manualInstances.get("mi1"))
        assertEquals(1, state.manualStacks.size)
    }

    @Test
    fun `increaseItemQuantity - merges same item into one entry`() {
        val items = listOf(
            StorageBagItem(itemId = "s1", itemType = "equipment_stack", name = "铁剑", rarity = 1, quantity = 1)
        )
        val updated = StorageBagUtils.increaseItemQuantity(
            items,
            StorageBagItem(itemId = "s1", itemType = "equipment_stack", name = "铁剑", rarity = 1, quantity = 2)
        )
        assertEquals(1, updated.size)
        assertEquals(3, updated[0].quantity)
    }
}
