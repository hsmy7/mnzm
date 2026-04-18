package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Seed
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InventorySystemTest {

    private lateinit var system: InventorySystem

    @Before
    fun setUp() {
        system = InventorySystem()
        system.initialize()
    }

    // ========== Equipment 测试 ==========

    @Test
    fun `addEquipment - 正常添加装备`() {
        val item = Equipment(id = "e1", name = "铁剑", rarity = 1)
        assertEquals(AddResult.SUCCESS, system.addEquipment(item))
        assertNotNull(system.getEquipmentById("e1"))
    }

    @Test
    fun `addEquipment - 空id返回INVALID_ID`() {
        val item = Equipment(id = "", name = "铁剑", rarity = 1)
        assertEquals(AddResult.INVALID_ID, system.addEquipment(item))
    }

    @Test
    fun `addEquipment - 空name返回INVALID_NAME`() {
        val item = Equipment(id = "e1", name = "", rarity = 1)
        assertEquals(AddResult.INVALID_NAME, system.addEquipment(item))
    }

    @Test
    fun `addEquipment - 无效rarity返回INVALID_RARITY`() {
        val item0 = Equipment(id = "e1", name = "铁剑", rarity = 0)
        assertEquals(AddResult.INVALID_RARITY, system.addEquipment(item0))
        val item7 = Equipment(id = "e2", name = "铁剑", rarity = 7)
        assertEquals(AddResult.INVALID_RARITY, system.addEquipment(item7))
    }

    @Test
    fun `addEquipment - 重复id返回DUPLICATE_ID`() {
        system.addEquipment(Equipment(id = "e1", name = "铁剑", rarity = 1))
        val result = system.addEquipment(Equipment(id = "e1", name = "铜剑", rarity = 2))
        assertEquals(AddResult.DUPLICATE_ID, result)
    }

    @Test
    fun `removeEquipment - 正常删除装备`() {
        system.addEquipment(Equipment(id = "e1", name = "铁剑", rarity = 1))
        assertTrue(system.removeEquipment("e1"))
        assertNull(system.getEquipmentById("e1"))
    }

    @Test
    fun `removeEquipment - 删除不存在的装备返回false`() {
        assertFalse(system.removeEquipment("nonexistent"))
    }

    @Test
    fun `removeEquipment - 锁定装备不能删除`() {
        system.addEquipment(Equipment(id = "e1", name = "铁剑", rarity = 1, isLocked = true))
        assertFalse(system.removeEquipment("e1"))
        assertNotNull(system.getEquipmentById("e1"))
    }

    @Test
    fun `removeEquipment - 无效数量返回false`() {
        system.addEquipment(Equipment(id = "e1", name = "铁剑", rarity = 1))
        assertFalse(system.removeEquipment("e1", 0))
        assertFalse(system.removeEquipment("e1", -1))
    }

    @Test
    fun `updateEquipment - 正常更新装备`() {
        system.addEquipment(Equipment(id = "e1", name = "铁剑", rarity = 1))
        val result = system.updateEquipment("e1") { it.copy(name = "铜剑") }
        assertTrue(result)
        assertEquals("铜剑", system.getEquipmentById("e1")?.name)
    }

    @Test
    fun `updateEquipment - 更新不存在的装备返回false`() {
        assertFalse(system.updateEquipment("nonexistent") { it.copy(name = "铜剑") })
    }

    // ========== Pill 测试 ==========

    @Test
    fun `addPill - 正常添加丹药`() {
        val pill = Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5)
        assertEquals(AddResult.SUCCESS, system.addPill(pill))
        assertNotNull(system.getPillById("p1"))
        assertEquals(5, system.getPillQuantity("p1"))
    }

    @Test
    fun `addPill - 合并同名同品质同类别丹药`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        system.addPill(Pill(id = "p2", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 3))
        assertEquals(8, system.getPillQuantity("p1"))
        assertNull(system.getPillById("p2"))
    }

    @Test
    fun `addPill - 不同类别不合并`() {
        system.addPill(Pill(id = "p1", name = "灵丹", rarity = 1, category = PillCategory.CULTIVATION, quantity = 5))
        system.addPill(Pill(id = "p2", name = "灵丹", rarity = 1, category = PillCategory.BATTLE_PHYSICAL, quantity = 3))
        assertNotNull(system.getPillById("p1"))
        assertNotNull(system.getPillById("p2"))
    }

    @Test
    fun `addPill - 合并数量不超过MAX_STACK_SIZE`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 998))
        system.addPill(Pill(id = "p2", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        assertEquals(InventorySystem.MAX_STACK_SIZE, system.getPillQuantity("p1"))
    }

    @Test
    fun `addPill - 禁止合并时添加新物品`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        system.addPill(Pill(id = "p2", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 3), merge = false)
        assertNotNull(system.getPillById("p2"))
        assertEquals(3, system.getPillQuantity("p2"))
    }

    @Test
    fun `addPill - 空name返回INVALID_NAME`() {
        val pill = Pill(id = "p1", name = "", rarity = 1, quantity = 1)
        assertEquals(AddResult.INVALID_NAME, system.addPill(pill))
    }

    @Test
    fun `addPill - 无效rarity返回INVALID_RARITY`() {
        val pill = Pill(id = "p1", name = "丹药", rarity = 0, quantity = 1)
        assertEquals(AddResult.INVALID_RARITY, system.addPill(pill))
    }

    @Test
    fun `addPill - 无效quantity返回INVALID_QUANTITY`() {
        val pill = Pill(id = "p1", name = "丹药", rarity = 1, quantity = 0)
        assertEquals(AddResult.INVALID_QUANTITY, system.addPill(pill))
    }

    @Test
    fun `removePill - 正常减少数量`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        assertTrue(system.removePill("p1", 3))
        assertEquals(2, system.getPillQuantity("p1"))
    }

    @Test
    fun `removePill - 数量减为0时删除物品`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        assertTrue(system.removePill("p1", 5))
        assertNull(system.getPillById("p1"))
    }

    @Test
    fun `removePill - 锁定丹药不能删除`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5, isLocked = true))
        assertFalse(system.removePill("p1", 1))
        assertEquals(5, system.getPillQuantity("p1"))
    }

    @Test
    fun `removePillByName - 按名称和品质删除`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        assertTrue(system.removePillByName("筑基丹", 2, 3))
        assertEquals(2, system.getPillQuantity("p1"))
    }

    @Test
    fun `removePillByName - 不存在的丹药返回false`() {
        assertFalse(system.removePillByName("不存在", 1, 1))
    }

    @Test
    fun `hasPill - 检查丹药是否存在且数量足够`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        assertTrue(system.hasPill("筑基丹", 2, 5))
        assertTrue(system.hasPill("筑基丹", 2, 1))
        assertFalse(system.hasPill("筑基丹", 2, 6))
        assertFalse(system.hasPill("筑基丹", 3, 1))
    }

    @Test
    fun `updatePill - 正常更新丹药`() {
        system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2, category = PillCategory.FUNCTIONAL, quantity = 5))
        assertTrue(system.updatePill("p1") { it.copy(quantity = 10) })
        assertEquals(10, system.getPillQuantity("p1"))
    }

    // ========== Manual 测试 ==========

    @Test
    fun `addManual - 正常添加功法`() {
        val manual = Manual(id = "m1", name = "基础功法", rarity = 1, quantity = 1)
        assertEquals(AddResult.SUCCESS, system.addManual(manual))
        assertNotNull(system.getManualById("m1"))
    }

    @Test
    fun `addManual - 合并同名同品质同类型功法`() {
        system.addManual(Manual(id = "m1", name = "基础功法", rarity = 1, type = ManualType.MIND, quantity = 3))
        system.addManual(Manual(id = "m2", name = "基础功法", rarity = 1, type = ManualType.MIND, quantity = 2))
        val manual = system.getManualById("m1")
        assertNotNull(manual)
        assertEquals(5, manual!!.quantity)
    }

    @Test
    fun `removeManual - 正常减少数量`() {
        system.addManual(Manual(id = "m1", name = "基础功法", rarity = 1, quantity = 5))
        assertTrue(system.removeManual("m1", 3))
        assertEquals(2, system.getManualById("m1")!!.quantity)
    }

    @Test
    fun `removeManual - 锁定功法不能删除`() {
        system.addManual(Manual(id = "m1", name = "基础功法", rarity = 1, quantity = 5, isLocked = true))
        assertFalse(system.removeManual("m1", 1))
    }

    // ========== Material 测试 ==========

    @Test
    fun `addMaterial - 正常添加材料`() {
        val material = Material(id = "mat1", name = "铁矿石", rarity = 1, quantity = 10)
        assertEquals(AddResult.SUCCESS, system.addMaterial(material))
        assertNotNull(system.getMaterialById("mat1"))
    }

    @Test
    fun `addMaterial - 合并同名同品质同类别材料`() {
        system.addMaterial(Material(id = "mat1", name = "铁矿石", rarity = 1, category = MaterialCategory.BEAST_HIDE, quantity = 10))
        system.addMaterial(Material(id = "mat2", name = "铁矿石", rarity = 1, category = MaterialCategory.BEAST_HIDE, quantity = 5))
        assertEquals(15, system.getMaterialById("mat1")!!.quantity)
    }

    @Test
    fun `removeMaterial - 正常减少数量`() {
        system.addMaterial(Material(id = "mat1", name = "铁矿石", rarity = 1, quantity = 10))
        assertTrue(system.removeMaterial("mat1", 5))
        assertEquals(5, system.getMaterialById("mat1")!!.quantity)
    }

    @Test
    fun `removeMaterialByName - 按名称和品质删除`() {
        system.addMaterial(Material(id = "mat1", name = "铁矿石", rarity = 1, quantity = 10))
        assertTrue(system.removeMaterialByName("铁矿石", 1, 5))
        assertEquals(5, system.getMaterialById("mat1")!!.quantity)
    }

    @Test
    fun `removeMaterial - 锁定材料不能删除`() {
        system.addMaterial(Material(id = "mat1", name = "铁矿石", rarity = 1, quantity = 10, isLocked = true))
        assertFalse(system.removeMaterial("mat1", 1))
    }

    // ========== 容量测试 ==========

    @Test
    fun `getCapacityInfo - 初始容量正确`() {
        val info = system.getCapacityInfo()
        assertEquals(0, info.currentSlots)
        assertEquals(InventorySystem.MAX_INVENTORY_SIZE, info.maxSlots)
        assertEquals(InventorySystem.MAX_INVENTORY_SIZE, info.remainingSlots)
        assertFalse(info.isFull)
    }

    @Test
    fun `getCapacityInfo - 添加物品后容量变化`() {
        system.addEquipment(Equipment(id = "e1", name = "铁剑", rarity = 1))
        system.addPill(Pill(id = "p1", name = "丹药", rarity = 1, quantity = 5))
        val info = system.getCapacityInfo()
        assertEquals(2, info.currentSlots)
        assertEquals(InventorySystem.MAX_INVENTORY_SIZE - 2, info.remainingSlots)
    }

    @Test
    fun `canAddItem - 未满时返回true`() {
        assertTrue(system.canAddItem())
    }

    @Test
    fun `canAddItems - 检查批量添加`() {
        assertTrue(system.canAddItems(10))
        assertFalse(system.canAddItems(InventorySystem.MAX_INVENTORY_SIZE + 1))
    }

    // ========== loadInventory 测试 ==========

    @Test
    fun `loadInventory - 加载所有物品类型`() {
        system.loadInventory(
            equipment = listOf(Equipment(id = "e1", name = "铁剑", rarity = 1)),
            manuals = listOf(Manual(id = "m1", name = "功法", rarity = 1, quantity = 1)),
            pills = listOf(Pill(id = "p1", name = "丹药", rarity = 1, quantity = 1)),
            materials = listOf(Material(id = "mat1", name = "材料", rarity = 1, quantity = 1)),
            herbs = listOf(),
            seeds = listOf()
        )
        assertNotNull(system.getEquipmentById("e1"))
        assertNotNull(system.getManualById("m1"))
        assertNotNull(system.getPillById("p1"))
        assertNotNull(system.getMaterialById("mat1"))
    }

    // ========== clear 测试 ==========

    @Test
    fun `clear - 清空所有物品`() = runBlocking {
        system.addEquipment(Equipment(id = "e1", name = "铁剑", rarity = 1))
        system.addPill(Pill(id = "p1", name = "丹药", rarity = 1, quantity = 5))
        system.clear()
        assertEquals(0, system.getCapacityInfo().currentSlots)
        assertNull(system.getEquipmentById("e1"))
        assertNull(system.getPillById("p1"))
    }
}
