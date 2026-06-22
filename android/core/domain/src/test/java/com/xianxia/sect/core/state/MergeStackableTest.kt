package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.HasId
import com.xianxia.sect.core.util.StackableItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mergeStackable] 扩展函数单元测试。
 *
 * 覆盖历史 bug：expelDisciple 中使用 coerceAtMost 截断导致装备数量丢失。
 * 修复后溢出部分应新建堆叠，确保物品数量完整转移。
 */
class MergeStackableTest {

    private fun createStack(
        id: String = java.util.UUID.randomUUID().toString(),
        name: String = "test-sword",
        rarity: Int = 1,
        quantity: Int = 1,
        slot: EquipmentSlot = EquipmentSlot.WEAPON
    ): EquipmentStack = EquipmentStack(
        id = id,
        name = name,
        rarity = rarity,
        quantity = quantity,
        slot = slot
    )

    private fun matchByNameRaritySlot(target: EquipmentStack): (EquipmentStack) -> Boolean =
        { it.name == target.name && it.rarity == target.rarity && it.slot == target.slot }

    @Test
    fun `mergeStackable - 无同类堆叠时直接添加`() {
        val store = EntityStore<EquipmentStack>(emptyList())
        val stack = createStack(quantity = 5)

        val result = store.mergeStackable(
            item = stack,
            matchPredicate = matchByNameRaritySlot(stack),
            maxStack = 10
        )

        assertEquals(1, result.size)
        assertEquals(5, result.first().quantity)
    }

    @Test
    fun `mergeStackable - 合并后未超过maxStack时叠加到现有堆叠`() {
        val existing = createStack(id = "existing", quantity = 3)
        val store = EntityStore(listOf(existing))
        val newStack = createStack(id = "new", quantity = 5)

        val result = store.mergeStackable(
            item = newStack,
            matchPredicate = matchByNameRaritySlot(newStack),
            maxStack = 10
        )

        assertEquals(1, result.size)
        assertEquals("existing", result.first().id)
        assertEquals(8, result.first().quantity)
    }

    @Test
    fun `mergeStackable - 合并后超过maxStack时填满现有堆叠并新建溢出堆叠`() {
        val existing = createStack(id = "existing", quantity = 8)
        val store = EntityStore(listOf(existing))
        val newStack = createStack(id = "new", quantity = 5)

        val result = store.mergeStackable(
            item = newStack,
            matchPredicate = matchByNameRaritySlot(newStack),
            maxStack = 10
        )

        // 应有 2 个堆叠
        assertEquals(2, result.size)

        val filled = result.first { it.id == "existing" }
        assertEquals(10, filled.quantity)

        val overflow = result.first { it.id == "new" }
        // 8 + 5 = 13, maxStack = 10, overflow = 3
        assertEquals(3, overflow.quantity)
    }

    @Test
    fun `mergeStackable - 合并后恰好等于maxStack时不新建溢出堆叠`() {
        val existing = createStack(id = "existing", quantity = 7)
        val store = EntityStore(listOf(existing))
        val newStack = createStack(id = "new", quantity = 3)

        val result = store.mergeStackable(
            item = newStack,
            matchPredicate = matchByNameRaritySlot(newStack),
            maxStack = 10
        )

        assertEquals(1, result.size)
        assertEquals(10, result.first().quantity)
    }

    @Test
    fun `mergeStackable - 不丢失任何数量`() {
        // 验证：无论何种合并路径，总数量必须守恒
        val existing = createStack(id = "existing", quantity = 9)
        val store = EntityStore(listOf(existing))
        val newStack = createStack(id = "new", quantity = 5)

        val result = store.mergeStackable(
            item = newStack,
            matchPredicate = matchByNameRaritySlot(newStack),
            maxStack = 10
        )

        val totalAfter = result.sumOf { it.quantity }
        // 9 + 5 = 14，合并后总数量必须仍为 14
        assertEquals(14, totalAfter)
    }

    @Test
    fun `mergeStackable - matchPredicate不匹配时直接添加`() {
        val existing = createStack(id = "existing", name = "sword", quantity = 3)
        val store = EntityStore(listOf(existing))
        val newStack = createStack(id = "new", name = "armor", slot = EquipmentSlot.ARMOR, quantity = 2)

        val result = store.mergeStackable(
            item = newStack,
            matchPredicate = matchByNameRaritySlot(newStack),
            maxStack = 10
        )

        // 名称/槽位不同，不应合并
        assertEquals(2, result.size)
    }

    @Test
    fun `mergeStackable - 溢出堆叠保留原物品属性`() {
        val existing = createStack(id = "existing", name = "flame-sword", rarity = 3, quantity = 8)
        val store = EntityStore(listOf(existing))
        val newStack = createStack(id = "new", name = "flame-sword", rarity = 3, quantity = 5)

        val result = store.mergeStackable(
            item = newStack,
            matchPredicate = matchByNameRaritySlot(newStack),
            maxStack = 10
        )

        val overflow = result.first { it.id == "new" }
        assertEquals("flame-sword", overflow.name)
        assertEquals(3, overflow.rarity)
        assertEquals(EquipmentSlot.WEAPON, overflow.slot)
        assertEquals(3, overflow.quantity)
    }
}
