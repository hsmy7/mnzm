package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.BagStackedData
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.registry.ManualDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 取回（没收）路径模板重建测试（BagItemReconstructor）。
 *
 * 袋条目持有 name/quantity + BagStackedData 元数据，重建时按数据库模板补齐
 * 完整堆叠。核心守卫：
 * - minRealm 用条目 stackedData 保真（保留实际境界门槛）
 * - quantity 用条目数量
 * - 模板缺失分型契约：manual/pill 返回 null（丢弃，不复制物品）；
 *   material/herb/seed 兜底铸造（身份=name/rarity/quantity 自持，防模板漂移毁财产）
 * - B3 装备重构：装备条目（equipment/equipment_stack）随堆叠轨退役**不再重建**
 *   （恒返回 null；装备以 equipment_instance 完整实例条目随袋流转）
 */
class BagItemReconstructorTest {

    @Before
    fun setUp() {
        ManualDatabase.initializeWithManuals(mapOf(
            "t1" to ManualDatabase.ManualTemplate(
                id = "t1", name = "太乙剑诀", type = ManualType.ATTACK, rarity = 2,
                description = "测试功法"
            )
        ))
    }

    @After
    fun tearDown() {
        // 恢复未初始化态，防污染其他条件初始化 ManualDatabase 的测试类
        ManualDatabase.resetForTest()
    }

    // ═══════════════════════════════════════════════════════════════
    // B3：装备条目不再重建（堆叠轨退役）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `equipment entries no longer rebuilt - returns null regardless of stackedData`() {
        // 装备堆叠条目（含 minRealm 保真元数据）一律不重建——B3 无装备堆叠语义
        val item = StorageBagItem(
            itemId = "bag1", itemType = "equipment_stack", name = "精铁剑", rarity = 1, quantity = 2,
            stackedData = BagStackedData(minRealm = 7, slot = EquipmentSlot.WEAPON.name)
        )
        assertNull("装备条目不再重建", BagItemReconstructor.reconstruct(item))
        // 无 stackedData / 空数据同口径
        assertNull(
            "装备条目不再重建（无 stackedData）",
            BagItemReconstructor.reconstruct(
                StorageBagItem(itemId = "bag2", itemType = "equipment_stack", name = "精铁剑", rarity = 1)
            )
        )
    }

    @Test
    fun `manual stack rebuilds with manualType and quantity`() {
        val item = StorageBagItem(
            itemId = "bag2", itemType = "manual_stack", name = "太乙剑诀", rarity = 2, quantity = 3,
            stackedData = BagStackedData(minRealm = 6, manualType = ManualType.ATTACK.name)
        )
        val result = BagItemReconstructor.reconstruct(item)
        assertNotNull(result)
        val stack = (result as ReconstructedBagStack.Manual).stack
        assertEquals("太乙剑诀", stack.name)
        assertEquals("类型保真", ManualType.ATTACK, stack.type)
        assertEquals("数量保真", 3, stack.quantity)
    }

    // ═══════════════════════════════════════════════════════════════
    // pill / herb / seed / material：模板补齐
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `pill rebuilds via itemId template first then name fallback`() {
        val item = StorageBagItem(itemId = "p1", itemType = "pill", name = "聚气丹", rarity = 1, quantity = 5)
        val result = BagItemReconstructor.reconstruct(item)
        assertNotNull(result)
        val stack = (result as ReconstructedBagStack.Pill).stack
        assertEquals("聚气丹", stack.name)
        assertEquals("数量保真", 5, stack.quantity)
    }

    @Test
    fun `herb rebuilds with name and category template`() {
        val item = StorageBagItem(itemId = "h1", itemType = "herb", name = "灵草", rarity = 1, quantity = 2)
        val result = BagItemReconstructor.reconstruct(item)
        assertNotNull(result)
        val stack = (result as ReconstructedBagStack.Herb).stack
        assertEquals("灵草", stack.name)
        assertEquals("数量保真", 2, stack.quantity)
    }

    @Test
    fun `seed rebuilds with growTime from template`() {
        val item = StorageBagItem(itemId = "s1", itemType = "seed", name = "灵稻种", rarity = 1, quantity = 1)
        val result = BagItemReconstructor.reconstruct(item)
        assertNotNull(result)
        val stack = (result as ReconstructedBagStack.Seed).stack
        assertEquals("灵稻种", stack.name)
        assertEquals("数量保真", 1, stack.quantity)
    }

    @Test
    fun `material rebuilds with category from template`() {
        val item = StorageBagItem(itemId = "m1", itemType = "material", name = "妖兽皮", rarity = 1, quantity = 4)
        val result = BagItemReconstructor.reconstruct(item)
        assertNotNull(result)
        val stack = (result as ReconstructedBagStack.Material).stack
        assertEquals("妖兽皮", stack.name)
        assertEquals("数量保真", 4, stack.quantity)
    }

    // ═══════════════════════════════════════════════════════════════
    // 失败路径：模板缺失的分型契约
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `unknown manual template returns null - dropped not copied`() {
        // manual/pill 模板承载身份数据（stats/效果），缺模板不可凭空铸造 → null（丢弃）
        val item = StorageBagItem(itemId = "b1", itemType = "manual_stack", name = "不存在的功法", rarity = 1)
        assertNull(BagItemReconstructor.reconstruct(item))
    }

    @Test
    fun `unknown material template falls back to default fabrication`() {
        // material/herb/seed 身份 = name/rarity/quantity，模板只补 description/category——
        // 缺模板兜底铸造（BEAST_HIDE 默认类目）而非丢弃：防模板库漂移毁玩家袋内财产（存量语义）
        val item = StorageBagItem(itemId = "b1", itemType = "material", name = "不存在的材料", rarity = 1)
        val result = BagItemReconstructor.reconstruct(item)
        assertNotNull("缺模板 material 兜底铸造，不丢弃", result)
        val stack = (result as ReconstructedBagStack.Material).stack
        assertEquals("条目身份保真", "不存在的材料", stack.name)
        assertEquals("默认类目兜底", MaterialCategory.BEAST_HIDE, stack.category)
    }

    @Test
    fun `unknown itemType returns null`() {
        val item = StorageBagItem(itemId = "x1", itemType = "奇异类型", name = "未知", rarity = 1)
        assertNull(BagItemReconstructor.reconstruct(item))
    }
}
