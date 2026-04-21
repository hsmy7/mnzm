package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.di.ApplicationScopeProvider
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
    }

    @After
    fun tearDown() {
        scopeProvider.close()
    }

    @Test
    fun `addEquipmentStack - normal add`() {
        val item = EquipmentStack(id = "e1", name = "铁剑", rarity = 1)
        assertEquals(AddResult.SUCCESS, system.addEquipmentStack(item))
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
    fun `removeEquipment - locked equipment cannot be removed`() {
        system.addEquipmentStack(EquipmentStack(id = "e1", name = "铁剑", rarity = 1, isLocked = true))
        assertFalse(system.removeEquipment("e1"))
        assertNotNull(system.getEquipmentStackById("e1"))
    }

    @Test
    fun `removeEquipment - invalid quantity returns false`() {
        system.addEquipmentStack(EquipmentStack(id = "e1", name = "铁剑", rarity = 1))
        assertFalse(system.removeEquipment("e1", 0))
        assertFalse(system.removeEquipment("e1", -1))
    }

    @Test
    fun `updateEquipmentStack - normal update`() {
        system.addEquipmentStack(EquipmentStack(id = "e1", name = "铁剑", rarity = 1))
        val result = system.updateEquipmentStack("e1") { it.copy(name = "铜剑") }
        assertTrue(result)
        assertEquals("铜剑", system.getEquipmentStackById("e1")?.name)
    }

    @Test
    fun `updateEquipmentStack - nonexistent returns false`() {
        assertFalse(system.updateEquipmentStack("nonexistent") { it.copy(name = "铜剑") })
    }

    @Test
    fun `addPill - normal add`() {
        val pill = Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5)
        assertEquals(AddResult.SUCCESS, system.addPill(pill))
        assertNotNull(system.getPillById("p1"))
        assertEquals(5, system.getPillQuantity("p1"))
    }

    @Test
    fun `addPill - merge same name rarity category`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        system.addPill(Pill(id = "p2", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 3))
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
    fun `removePill - normal reduce quantity`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        assertTrue(system.removePill("p1", 3))
        assertEquals(2, system.getPillQuantity("p1"))
    }

    @Test
    fun `removePill - quantity reaches zero deletes item`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        assertTrue(system.removePill("p1", 5))
        assertNull(system.getPillById("p1"))
    }

    @Test
    fun `removePill - locked pill cannot be removed`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5, isLocked = true))
        assertFalse(system.removePill("p1", 1))
        assertEquals(5, system.getPillQuantity("p1"))
    }

    @Test
    fun `removePillByName - remove by name and rarity`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        assertTrue(system.removePillByName("筑基丹", 2, 3))
        assertEquals(2, system.getPillQuantity("p1"))
    }

    @Test
    fun `removePillByName - nonexistent returns false`() {
        assertFalse(system.removePillByName("不存在", 1, 1))
    }

    @Test
    fun `hasPill - check existence and quantity`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        assertTrue(system.hasPill("筑基丹", 2, 5))
        assertTrue(system.hasPill("筑基丹", 2, 1))
        assertFalse(system.hasPill("筑基丹", 2, 6))
        assertFalse(system.hasPill("筑基丹", 3, 1))
    }

    @Test
    fun `updatePill - normal update`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        assertTrue(system.updatePill("p1") { it.copy(quantity = 10) })
        assertEquals(10, system.getPillQuantity("p1"))
    }

    @Test
    fun `addManualStack - normal add`() {
        val manual = ManualStack(id = "m1", name = "基础功法", rarity = 1, quantity = 1)
        assertEquals(AddResult.SUCCESS, system.addManualStack(manual))
        assertNotNull(system.getManualStackById("m1"))
    }

    @Test
    fun `addManualStack - merge same name rarity type`() {
        system.addManualStack(ManualStack(id = "m1", name = "基础功法", rarity = 1, type = ManualType.MIND, quantity = 3))
        system.addManualStack(ManualStack(id = "m2", name = "基础功法", rarity = 1, type = ManualType.MIND, quantity = 2))
        val manual = system.getManualStackById("m1")
        assertNotNull(manual)
        assertEquals(5, manual!!.quantity)
    }

    @Test
    fun `removeManual - normal reduce quantity`() {
        system.addManualStack(ManualStack(id = "m1", name = "基础功法", rarity = 1, quantity = 5))
        assertTrue(system.removeManual("m1", 3))
        assertEquals(2, system.getManualStackById("m1")!!.quantity)
    }

    @Test
    fun `removeManual - locked manual cannot be removed`() {
        system.addManualStack(ManualStack(id = "m1", name = "基础功法", rarity = 1, quantity = 5, isLocked = true))
        assertFalse(system.removeManual("m1", 1))
    }

    @Test
    fun `addMaterial - normal add`() {
        val material = Material(id = "mat1", name = "铁矿石", rarity = 1, quantity = 10)
        assertEquals(AddResult.SUCCESS, system.addMaterial(material))
        assertNotNull(system.getMaterialById("mat1"))
    }

    @Test
    fun `addMaterial - merge same name rarity category`() {
        system.addMaterial(Material(id = "mat1", name = "铁矿石", rarity = 1, category = MaterialCategory.BEAST_HIDE, quantity = 10))
        system.addMaterial(Material(id = "mat2", name = "铁矿石", rarity = 1, category = MaterialCategory.BEAST_HIDE, quantity = 5))
        assertEquals(15, system.getMaterialById("mat1")!!.quantity)
    }

    @Test
    fun `removeMaterial - normal reduce quantity`() {
        system.addMaterial(Material(id = "mat1", name = "铁矿石", rarity = 1, quantity = 10))
        assertTrue(system.removeMaterial("mat1", 5))
        assertEquals(5, system.getMaterialById("mat1")!!.quantity)
    }

    @Test
    fun `removeMaterialByName - remove by name and rarity`() {
        system.addMaterial(Material(id = "mat1", name = "铁矿石", rarity = 1, quantity = 10))
        assertTrue(system.removeMaterialByName("铁矿石", 1, 5))
        assertEquals(5, system.getMaterialById("mat1")!!.quantity)
    }

    @Test
    fun `removeMaterial - locked material cannot be removed`() {
        system.addMaterial(Material(id = "mat1", name = "铁矿石", rarity = 1, quantity = 10, isLocked = true))
        assertFalse(system.removeMaterial("mat1", 1))
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
        system.addEquipmentStack(EquipmentStack(id = "e1", name = "铁剑", rarity = 1))
        system.addPill(Pill(id = "p1", name = "丹药", rarity = 1, quantity = 5))
        system.clear()
        assertEquals(0, system.getCapacityInfo().currentSlots)
        assertNull(system.getEquipmentStackById("e1"))
        assertNull(system.getPillById("p1"))
    }
}
