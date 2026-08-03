package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 守卫测试：合并键单一事实来源。
 * 7 类物品的合并键构成断言——若键构成被意外改动（如丹药去掉品阶），
 * 此处立即失败，防止与主路径 `InventorySystem` 的合并语义再次分叉。
 */
class StackKeysTest {

    @Test
    fun `equipment key - name rarity slot`() {
        val a = EquipmentStack(id = "1", name = "青锋剑", rarity = 3, slot = EquipmentSlot.WEAPON)
        val b = EquipmentStack(id = "2", name = "青锋剑", rarity = 3, slot = EquipmentSlot.WEAPON)
        val c = EquipmentStack(id = "3", name = "青锋剑", rarity = 3, slot = EquipmentSlot.ARMOR)
        assertEquals(StackKeys.equipment(a), StackKeys.equipment(b))
        assertNotEquals(StackKeys.equipment(a), StackKeys.equipment(c))
    }

    @Test
    fun `manual key - name rarity type`() {
        val a = ManualStack(id = "1", name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK)
        val b = ManualStack(id = "2", name = "太乙剑诀", rarity = 2, type = ManualType.ATTACK)
        val c = ManualStack(id = "3", name = "太乙剑诀", rarity = 2, type = ManualType.DEFENSE)
        assertEquals(StackKeys.manual(a), StackKeys.manual(b))
        assertNotEquals(StackKeys.manual(a), StackKeys.manual(c))
    }

    @Test
    fun `pill key - contains grade - different grade does not merge`() {
        val low = Pill(id = "1", name = "回气丹", rarity = 1, category = PillCategory.CULTIVATION, grade = PillGrade.LOW)
        val low2 = Pill(id = "2", name = "回气丹", rarity = 1, category = PillCategory.CULTIVATION, grade = PillGrade.LOW)
        val medium = Pill(id = "3", name = "回气丹", rarity = 1, category = PillCategory.CULTIVATION, grade = PillGrade.MEDIUM)
        assertEquals(StackKeys.pill(low), StackKeys.pill(low2))
        // 品阶效果不同，属不同物品，键必须不同
        assertNotEquals(StackKeys.pill(low), StackKeys.pill(medium))
    }

    @Test
    fun `pill key - different category does not merge`() {
        val a = Pill(id = "1", name = "回气丹", rarity = 1, category = PillCategory.CULTIVATION, grade = PillGrade.LOW)
        val b = Pill(id = "2", name = "回气丹", rarity = 1, category = PillCategory.BATTLE, grade = PillGrade.LOW)
        assertNotEquals(StackKeys.pill(a), StackKeys.pill(b))
    }

    @Test
    fun `material key - name rarity category`() {
        val a = Material(id = "1", name = "妖兽皮", rarity = 2, category = MaterialCategory.BEAST_HIDE)
        val b = Material(id = "2", name = "妖兽皮", rarity = 2, category = MaterialCategory.BEAST_HIDE)
        val c = Material(id = "3", name = "妖兽皮", rarity = 2, category = MaterialCategory.BEAST_BONE)
        assertEquals(StackKeys.material(a), StackKeys.material(b))
        assertNotEquals(StackKeys.material(a), StackKeys.material(c))
    }

    @Test
    fun `herb key - name rarity category string`() {
        val a = Herb(id = "1", name = "聚灵草", rarity = 1, category = "grass")
        val b = Herb(id = "2", name = "聚灵草", rarity = 1, category = "grass")
        val c = Herb(id = "3", name = "聚灵草", rarity = 1, category = "flower")
        assertEquals(StackKeys.herb(a), StackKeys.herb(b))
        assertNotEquals(StackKeys.herb(a), StackKeys.herb(c))
    }

    @Test
    fun `seed key - name rarity growTime`() {
        val a = Seed(id = "1", name = "聚灵草种", rarity = 1, growTime = 36)
        val b = Seed(id = "2", name = "聚灵草种", rarity = 1, growTime = 36)
        val c = Seed(id = "3", name = "聚灵草种", rarity = 1, growTime = 72)
        assertEquals(StackKeys.seed(a), StackKeys.seed(b))
        assertNotEquals(StackKeys.seed(a), StackKeys.seed(c))
    }

    @Test
    fun `storageBag key - by rarity only`() {
        val a = StorageBag(id = "1", name = "凡品储物袋", rarity = 1, quantity = 1)
        val b = StorageBag(id = "2", name = "凡品储物袋", rarity = 1, quantity = 3)
        val c = StorageBag(id = "3", name = "灵品储物袋", rarity = 2, quantity = 1)
        assertEquals(StackKeys.storageBag(a), StackKeys.storageBag(b))
        assertNotEquals(StackKeys.storageBag(a), StackKeys.storageBag(c))
    }
}
