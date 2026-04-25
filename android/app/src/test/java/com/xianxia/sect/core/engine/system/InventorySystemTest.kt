package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InventorySystemTest {

    private lateinit var system: InventorySystem
    private lateinit var stateStore: GameStateStore
    private lateinit var scopeProvider: ApplicationScopeProvider
    private lateinit var inventoryConfig: InventoryConfig

    @Before
    fun setUp() {
        scopeProvider = ApplicationScopeProvider()
        stateStore = GameStateStore(scopeProvider)
        inventoryConfig = InventoryConfig()
        system = InventorySystem(stateStore, scopeProvider, inventoryConfig)
        system.initialize()
        runBlocking { stateStore.reset() }
    }

    @After
    fun tearDown() {
        runBlocking {
            delay(100)
            stateStore.reset()
        }
        scopeProvider.close()
    }

    @Test
    fun `addEquipmentStack - normal add`() = runBlocking {
        stateStore.update {
            val item = EquipmentStack(id = "e1", name = "铁剑", rarity = 1)
            assertEquals(AddResult.SUCCESS, system.addEquipmentStack(item))
        }
        assertNotNull(system.getEquipmentStackById("e1"))
    }

    @Test
    fun `addEquipmentStack - empty name returns INVALID_NAME`() {
        val item = EquipmentStack(id = "e1", name = "", rarity = 1)
        assertEquals(AddResult.INVALID_NAME, system.addEquipmentStack(item))
    }

    @Test
    fun `addEquipmentStack - invalid rarity returns INVALID_RARITY`() {
        val item0 = EquipmentStack(id = "e1", name = "铁剑", rarity = 0)
        assertEquals(AddResult.INVALID_RARITY, system.addEquipmentStack(item0))
        val item7 = EquipmentStack(id = "e2", name = "铁剑", rarity = 7)
        assertEquals(AddResult.INVALID_RARITY, system.addEquipmentStack(item7))
    }

    @Test
    fun `removeEquipment - locked equipment cannot be removed`() = runBlocking {
        stateStore.update {
            system.addEquipmentStack(EquipmentStack(id = "e1", name = "铁剑", rarity = 1, isLocked = true))
        }
        stateStore.update {
            assertFalse(system.removeEquipment("e1"))
        }
        assertNotNull(system.getEquipmentStackById("e1"))
    }

    @Test
    fun `removeEquipment - invalid quantity returns false`() = runBlocking {
        stateStore.update {
            system.addEquipmentStack(EquipmentStack(id = "e1", name = "铁剑", rarity = 1))
        }
        assertFalse(system.removeEquipment("e1", 0))
        assertFalse(system.removeEquipment("e1", -1))
    }

    @Test
    fun `updateEquipmentStack - normal update`() = runBlocking {
        stateStore.update {
            system.addEquipmentStack(EquipmentStack(id = "e1", name = "铁剑", rarity = 1))
        }
        stateStore.update {
            val result = system.updateEquipmentStack("e1") { it.copy(name = "铜剑") }
            assertTrue(result)
        }
        assertEquals("铜剑", system.getEquipmentStackById("e1")?.name)
    }

    @Test
    fun `updateEquipmentStack - nonexistent returns false`() = runBlocking {
        stateStore.update {
            assertFalse(system.updateEquipmentStack("nonexistent") { it.copy(name = "铜剑") })
        }
    }

    @Test
    fun `addPill - normal add`() = runBlocking {
        stateStore.update {
            val pill = Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5)
            assertEquals(AddResult.SUCCESS, system.addPill(pill))
        }
        assertNotNull(system.getPillById("p1"))
        assertEquals(5, system.getPillQuantity("p1"))
    }

    @Test
    fun `addPill - merge same name rarity category`() = runBlocking {
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
            system.addPill(Pill(id = "p2", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 3))
        }
        assertEquals(8, system.getPillQuantity("p1"))
        assertNull(system.getPillById("p2"))
    }

    @Test
    fun `addPill - empty name returns INVALID_NAME`() {
        val pill = Pill(id = "p1", name = "", rarity = 1, quantity = 1)
        assertEquals(AddResult.INVALID_NAME, system.addPill(pill))
    }

    @Test
    fun `addPill - invalid rarity returns INVALID_RARITY`() {
        val pill = Pill(id = "p1", name = "丹药", rarity = 0, quantity = 1)
        assertEquals(AddResult.INVALID_RARITY, system.addPill(pill))
    }

    @Test
    fun `addPill - invalid quantity returns INVALID_QUANTITY`() {
        val pill = Pill(id = "p1", name = "丹药", rarity = 1, quantity = 0)
        assertEquals(AddResult.INVALID_QUANTITY, system.addPill(pill))
    }

    @Test
    fun `removePill - normal reduce quantity`() = runBlocking {
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        }
        stateStore.update {
            assertTrue(system.removePill("p1", 3))
        }
        assertEquals(2, system.getPillQuantity("p1"))
    }

    @Test
    fun `removePill - quantity reaches zero deletes item`() = runBlocking {
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        }
        stateStore.update {
            assertTrue(system.removePill("p1", 5))
        }
        assertNull(system.getPillById("p1"))
    }

    @Test
    fun `removePill - locked pill cannot be removed`() = runBlocking {
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5, isLocked = true))
        }
        stateStore.update {
            assertFalse(system.removePill("p1", 1))
        }
        assertEquals(5, system.getPillQuantity("p1"))
    }

    @Test
    fun `removePillByName - remove by name and rarity`() = runBlocking {
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        }
        stateStore.update {
            assertTrue(system.removePillByName("筑基丹", 2, 3))
        }
        assertEquals(2, system.getPillQuantity("p1"))
    }

    @Test
    fun `removePillByName - nonexistent returns false`() = runBlocking {
        stateStore.update {
            assertFalse(system.removePillByName("不存在", 1, 1))
        }
    }

    @Test
    fun `hasPill - check existence and quantity`() = runBlocking {
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        }
        assertTrue(system.hasPill("筑基丹", 2, 5))
        assertTrue(system.hasPill("筑基丹", 2, 1))
        assertFalse(system.hasPill("筑基丹", 2, 6))
        assertFalse(system.hasPill("筑基丹", 3, 1))
    }

    @Test
    fun `updatePill - normal update`() = runBlocking {
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        }
        stateStore.update {
            assertTrue(system.updatePill("p1") { it.copy(quantity = 10) })
        }
        assertEquals(10, system.getPillQuantity("p1"))
    }

    @Test
    fun `addManualStack - normal add`() = runBlocking {
        stateStore.update {
            val manual = ManualStack(id = "m1", name = "基础功法", rarity = 1, quantity = 1)
            assertEquals(AddResult.SUCCESS, system.addManualStack(manual))
        }
        assertNotNull(system.getManualStackById("m1"))
    }

    @Test
    fun `addManualStack - merge same name rarity type`() = runBlocking {
        stateStore.update {
            system.addManualStack(ManualStack(id = "m1", name = "基础功法", rarity = 1, type = ManualType.MIND, quantity = 3))
            system.addManualStack(ManualStack(id = "m2", name = "基础功法", rarity = 1, type = ManualType.MIND, quantity = 2))
        }
        val manual = system.getManualStackById("m1")
        assertNotNull(manual)
        assertEquals(5, manual!!.quantity)
    }

    @Test
    fun `removeManual - normal reduce quantity`() = runBlocking {
        stateStore.update {
            system.addManualStack(ManualStack(id = "m1", name = "基础功法", rarity = 1, quantity = 5))
        }
        stateStore.update {
            assertTrue(system.removeManual("m1", 3))
        }
        assertEquals(2, system.getManualStackById("m1")!!.quantity)
    }

    @Test
    fun `removeManual - locked manual cannot be removed`() = runBlocking {
        stateStore.update {
            system.addManualStack(ManualStack(id = "m1", name = "基础功法", rarity = 1, quantity = 5, isLocked = true))
        }
        stateStore.update {
            assertFalse(system.removeManual("m1", 1))
        }
    }

    @Test
    fun `addMaterial - normal add`() = runBlocking {
        stateStore.update {
            val material = Material(id = "mat1", name = "铁矿石", rarity = 1, quantity = 10)
            assertEquals(AddResult.SUCCESS, system.addMaterial(material))
        }
        assertNotNull(system.getMaterialById("mat1"))
    }

    @Test
    fun `addMaterial - merge same name rarity category`() = runBlocking {
        stateStore.update {
            system.addMaterial(Material(id = "mat1", name = "铁矿石", rarity = 1, category = MaterialCategory.BEAST_HIDE, quantity = 10))
            system.addMaterial(Material(id = "mat2", name = "铁矿石", rarity = 1, category = MaterialCategory.BEAST_HIDE, quantity = 5))
        }
        assertEquals(15, system.getMaterialById("mat1")!!.quantity)
    }

    @Test
    fun `removeMaterial - normal reduce quantity`() = runBlocking {
        stateStore.update {
            system.addMaterial(Material(id = "mat1", name = "铁矿石", rarity = 1, quantity = 10))
        }
        stateStore.update {
            assertTrue(system.removeMaterial("mat1", 5))
        }
        assertEquals(5, system.getMaterialById("mat1")!!.quantity)
    }

    @Test
    fun `removeMaterialByName - remove by name and rarity`() = runBlocking {
        stateStore.update {
            system.addMaterial(Material(id = "mat1", name = "铁矿石", rarity = 1, quantity = 10))
        }
        stateStore.update {
            assertTrue(system.removeMaterialByName("铁矿石", 1, 5))
        }
        assertEquals(5, system.getMaterialById("mat1")!!.quantity)
    }

    @Test
    fun `removeMaterial - locked material cannot be removed`() = runBlocking {
        stateStore.update {
            system.addMaterial(Material(id = "mat1", name = "铁矿石", rarity = 1, quantity = 10, isLocked = true))
        }
        stateStore.update {
            assertFalse(system.removeMaterial("mat1", 1))
        }
    }

    @Test
    fun `getCapacityInfo - initial capacity`() {
        val info = system.getCapacityInfo()
        assertEquals(0, info.currentSlots)
        assertEquals(InventorySystem.MAX_INVENTORY_SIZE, info.maxSlots)
        assertEquals(InventorySystem.MAX_INVENTORY_SIZE, info.remainingSlots)
        assertFalse(info.isFull)
    }

    @Test
    fun `canAddItem - not full returns true`() {
        assertTrue(system.canAddItem())
    }

    @Test
    fun `canAddItems - check batch add`() {
        assertTrue(system.canAddItems(10))
        assertFalse(system.canAddItems(InventorySystem.MAX_INVENTORY_SIZE + 1))
    }

    @Test
    fun `clear - clears all items`() = runBlocking {
        stateStore.update {
            system.addEquipmentStack(EquipmentStack(id = "e1", name = "铁剑", rarity = 1))
            system.addPill(Pill(id = "p1", name = "丹药", rarity = 1, quantity = 5))
        }
        system.clear()
        assertEquals(0, system.getCapacityInfo().currentSlots)
        assertNull(system.getEquipmentStackById("e1"))
        assertNull(system.getPillById("p1"))
    }

    @Test
    fun `addPill - maxStack truncation returns PARTIAL_SUCCESS`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        var result = AddResult.SUCCESS
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = maxStack - 5))
            result = system.addPill(Pill(id = "p2", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 10))
        }
        assertEquals(AddResult.PARTIAL_SUCCESS, result)
        assertEquals(maxStack, system.getPillQuantity("p1"))
    }

    @Test
    fun `addPill - merge within maxStack returns SUCCESS`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        var result = AddResult.SUCCESS
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = maxStack - 10))
            result = system.addPill(Pill(id = "p2", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        }
        assertEquals(AddResult.SUCCESS, result)
        assertEquals(maxStack - 5, system.getPillQuantity("p1"))
    }

    @Test
    fun `addEquipmentStack - maxStack truncation returns PARTIAL_SUCCESS`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("equipment_stack")
        var result = AddResult.SUCCESS
        stateStore.update {
            system.addEquipmentStack(EquipmentStack(id = "e1", name = "铁剑", rarity = 1, quantity = maxStack - 5))
            result = system.addEquipmentStack(EquipmentStack(id = "e2", name = "铁剑", rarity = 1, quantity = 10))
        }
        assertEquals(AddResult.PARTIAL_SUCCESS, result)
        assertEquals(maxStack, system.getEquipmentStackById("e1")!!.quantity)
    }

    @Test
    fun `addHerb - merge same name rarity category`() = runBlocking {
        stateStore.update {
            system.addHerb(Herb(id = "h1", name = "灵草", rarity = 1, category = "common", quantity = 5))
            system.addHerb(Herb(id = "h2", name = "灵草", rarity = 1, category = "common", quantity = 3))
        }
        assertEquals(8, system.getHerbById("h1")!!.quantity)
        assertNull(system.getHerbById("h2"))
    }

    @Test
    fun `addHerb - maxStack truncation returns PARTIAL_SUCCESS`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("herb")
        var result = AddResult.SUCCESS
        stateStore.update {
            system.addHerb(Herb(id = "h1", name = "灵草", rarity = 1, category = "common", quantity = maxStack - 5))
            result = system.addHerb(Herb(id = "h2", name = "灵草", rarity = 1, category = "common", quantity = 10))
        }
        assertEquals(AddResult.PARTIAL_SUCCESS, result)
        assertEquals(maxStack, system.getHerbById("h1")!!.quantity)
    }

    @Test
    fun `addSeed - merge same name rarity growTime`() = runBlocking {
        stateStore.update {
            system.addSeed(Seed(id = "s1", name = "灵草种子", rarity = 1, growTime = 3, quantity = 5))
            system.addSeed(Seed(id = "s2", name = "灵草种子", rarity = 1, growTime = 3, quantity = 3))
        }
        assertEquals(8, system.getSeedById("s1")!!.quantity)
        assertNull(system.getSeedById("s2"))
    }

    @Test
    fun `addSeed - maxStack truncation returns PARTIAL_SUCCESS`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("seed")
        var result = AddResult.SUCCESS
        stateStore.update {
            system.addSeed(Seed(id = "s1", name = "灵草种子", rarity = 1, growTime = 3, quantity = maxStack - 5))
            result = system.addSeed(Seed(id = "s2", name = "灵草种子", rarity = 1, growTime = 3, quantity = 10))
        }
        assertEquals(AddResult.PARTIAL_SUCCESS, result)
        assertEquals(maxStack, system.getSeedById("s1")!!.quantity)
    }

    @Test
    fun `returnEquipmentToStack - merge into existing stack`() = runBlocking {
        var result = AddResult.SUCCESS
        stateStore.update {
            system.addEquipmentStack(EquipmentStack(id = "e1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 5))
            val instance = EquipmentInstance(id = "ei1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON)
            result = system.returnEquipmentToStack(instance)
        }
        assertEquals(AddResult.SUCCESS, result)
        val stack = stateStore.equipmentStacks.value.find { it.name == "铁剑" }
        assertNotNull(stack)
        assertEquals(6, stack!!.quantity)
    }

    @Test
    fun `returnEquipmentToStack - create new stack when no match`() = runBlocking {
        var result = AddResult.SUCCESS
        stateStore.update {
            val instance = EquipmentInstance(id = "ei1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON)
            result = system.returnEquipmentToStack(instance)
        }
        assertEquals(AddResult.SUCCESS, result)
        val stack = stateStore.equipmentStacks.value.find { it.name == "铁剑" }
        assertNotNull(stack)
        assertEquals(1, stack!!.quantity)
    }

    @Test
    fun `returnManualToStack - merge into existing stack`() = runBlocking {
        var result = AddResult.SUCCESS
        stateStore.update {
            system.addManualStack(ManualStack(id = "m1", name = "基础功法", rarity = 1, type = ManualType.MIND, quantity = 5))
            val instance = ManualInstance(id = "mi1", name = "基础功法", rarity = 1, type = ManualType.MIND)
            result = system.returnManualToStack(instance)
        }
        assertEquals(AddResult.SUCCESS, result)
        assertEquals(6, system.getManualStackById("m1")!!.quantity)
    }

    @Test
    fun `canAddPill - returns false when stack is at maxStack and inventory is full`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = maxStack))
            for (i in 0 until InventorySystem.MAX_INVENTORY_SIZE - 1) {
                system.addPill(Pill(id = "fill$i", name = "填充丹药$i", rarity = 1, category = PillCategory.FUNCTIONAL, quantity = 1))
            }
        }
        assertFalse(system.canAddPill("筑基丹", 2, PillCategory.FUNCTIONAL))
    }

    @Test
    fun `canAddPill - returns true when stack is at maxStack but has free slots`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = maxStack))
        }
        assertTrue(system.canAddPill("筑基丹", 2, PillCategory.FUNCTIONAL))
    }

    @Test
    fun `canAddPill - returns true when stack is below maxStack`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = maxStack - 1))
        }
        assertTrue(system.canAddPill("筑基丹", 2, PillCategory.FUNCTIONAL))
    }

    @Test
    fun `canAddPill - same name different grade should not merge`() = runBlocking {
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, grade = PillGrade.MEDIUM, quantity = 1))
        }
        // 不同品级不应合并，但仓库有空位所以可以添加为新槽位
        assertTrue(system.canAddPill("筑基丹", 2, PillCategory.FUNCTIONAL, PillGrade.HIGH))
    }

    @Test
    fun `canAddPill - same name different grade cannot add when inventory full`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, grade = PillGrade.MEDIUM, quantity = maxStack))
            for (i in 0 until InventorySystem.MAX_INVENTORY_SIZE - 1) {
                system.addPill(Pill(id = "fill$i", name = "填充丹药$i", rarity = 1, category = PillCategory.FUNCTIONAL, quantity = 1))
            }
        }
        // 中品筑基丹已满栈，上品筑基丹不应合并到中品，且仓库已满
        assertFalse(system.canAddPill("筑基丹", 2, PillCategory.FUNCTIONAL, PillGrade.HIGH))
    }

    @Test
    fun `canAddPill - same name same grade should merge`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, grade = PillGrade.HIGH, quantity = 1))
        }
        // 同品级应合并
        assertTrue(system.canAddPill("筑基丹", 2, PillCategory.FUNCTIONAL, PillGrade.HIGH))
    }

    @Test
    fun `canAddEquipment - returns false when stack is at maxStack and inventory is full`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("equipment_stack")
        stateStore.update {
            system.addEquipmentStack(EquipmentStack(id = "e1", name = "铁剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = maxStack))
            for (i in 0 until InventorySystem.MAX_INVENTORY_SIZE - 1) {
                system.addEquipmentStack(EquipmentStack(id = "fill$i", name = "填充装备$i", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 1))
            }
        }
        assertFalse(system.canAddEquipment("铁剑", 1, EquipmentSlot.WEAPON))
    }

    @Test
    fun `InventoryConfig - default stack limits match game design`() {
        assertEquals(999, inventoryConfig.getMaxStackSize("equipment_stack"))
        assertEquals(999, inventoryConfig.getMaxStackSize("manual_stack"))
        assertEquals(999, inventoryConfig.getMaxStackSize("pill"))
        assertEquals(9999, inventoryConfig.getMaxStackSize("material"))
        assertEquals(9999, inventoryConfig.getMaxStackSize("herb"))
        assertEquals(9999, inventoryConfig.getMaxStackSize("seed"))
    }
}
