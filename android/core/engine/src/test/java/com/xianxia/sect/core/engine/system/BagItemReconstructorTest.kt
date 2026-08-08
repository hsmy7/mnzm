package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.BagStackedData
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.registry.ManualDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * D-03 取回（没收）路径模板重建测试（BagItemReconstructor）。
 *
 * 袋条目持有 name/quantity + BagStackedData 元数据，重建时按数据库模板补齐
 * 完整堆叠。核心守卫：
 * - minRealm 用条目 stackedData 保真（旧逻辑按 rarity 推导，丢失实际门槛）
 * - quantity 用条目数量
 * - 找不到模板返回 null（调用方按丢弃处理，物品不复制）
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
    // equipment / manual：minRealm 保真
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `equipment stack rebuilds with stackedData minRealm preserved`() {
        val item = StorageBagItem(
            itemId = "bag1", itemType = "equipment_stack", name = "精铁剑", rarity = 1, quantity = 2,
            stackedData = BagStackedData(minRealm = 7, slot = EquipmentSlot.WEAPON.name)
        )
        val result = BagItemReconstructor.reconstruct(item)
        assertNotNull(result)
        val stack = (result as ReconstructedBagStack.Equipment).stack
        assertEquals("名称从模板", "精铁剑", stack.name)
        assertEquals("minRealm 保真", 7, stack.minRealm)
        assertEquals("槽位从模板", EquipmentSlot.WEAPON, stack.slot)
        assertEquals("数量保真", 2, stack.quantity)
    }

    @Test
    fun `equipment without stackedData falls back to rarity-derived minRealm`() {
        // 老存档/手动构造条目无 stackedData：退化为 rarity 推导（与旧 confiscate 一致）
        val item = StorageBagItem(itemId = "bag1", itemType = "equipment_stack", name = "精铁剑", rarity = 1)
        val result = BagItemReconstructor.reconstruct(item)
        assertNotNull(result)
        val stack = (result as ReconstructedBagStack.Equipment).stack
        assertEquals("模板 minRealm", com.xianxia.sect.core.GameConfig.Realm.getMinRealmForRarity(1), stack.minRealm)
    }

    @Test
    fun `empty stackedData minRealm zero falls back to rarity-derived`() {
        // 对抗性审查：偷盗等路径写空 BagStackedData()（minRealm 默认 0）——
        // 0 非 null 不触发旧逻辑的 `?:` 回退，重建后成为"最高境界门槛"装备
        val item = StorageBagItem(
            itemId = "bag1", itemType = "equipment_stack", name = "精铁剑", rarity = 1, quantity = 1,
            stackedData = BagStackedData()
        )
        val result = BagItemReconstructor.reconstruct(item)
        assertNotNull(result)
        val stack = (result as ReconstructedBagStack.Equipment).stack
        assertEquals(
            "minRealm=0 回退 rarity 推导",
            com.xianxia.sect.core.GameConfig.Realm.getMinRealmForRarity(1), stack.minRealm
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
    // 失败路径：找不到模板 → null（丢弃，不复制物品）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `unknown template returns null`() {
        val item = StorageBagItem(itemId = "b1", itemType = "equipment_stack", name = "不存在的装备", rarity = 1)
        assertNull(BagItemReconstructor.reconstruct(item))
    }

    @Test
    fun `unknown itemType returns null`() {
        val item = StorageBagItem(itemId = "x1", itemType = "奇异类型", name = "未知", rarity = 1)
        assertNull(BagItemReconstructor.reconstruct(item))
    }

    @Test
    fun `zero quantity coerced to one`() {
        val item = StorageBagItem(itemId = "bag1", itemType = "equipment_stack", name = "精铁剑", rarity = 1, quantity = 0)
        val result = BagItemReconstructor.reconstruct(item)
        assertNotNull(result)
        assertEquals(1, (result as ReconstructedBagStack.Equipment).stack.quantity)
    }
}
