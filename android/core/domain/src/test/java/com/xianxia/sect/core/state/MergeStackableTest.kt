package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [mergeStackable] 扩展函数单元测试。
 *
 * 守卫：溢出部分应新建堆叠（禁止 coerceAtMost 截断），确保物品数量完整转移。
 *
 * 载体说明（B3 起）：装备一行一实例、不再可堆叠（EquipmentStack 已退役出
 * StackableItem 体系），改用 [Material] 作为可堆叠载体验证通用合并算法。
 */
class MergeStackableTest {

    private fun createMaterial(
        id: String = java.util.UUID.randomUUID().toString(),
        name: String = "妖兽皮",
        rarity: Int = 1,
        quantity: Int = 1,
        category: MaterialCategory = MaterialCategory.BEAST_HIDE
    ): Material = Material(
        id = id,
        name = name,
        rarity = rarity,
        quantity = quantity,
        category = category
    )

    private fun matchByNameRarityCategory(target: Material): (Material) -> Boolean =
        { it.name == target.name && it.rarity == target.rarity && it.category == target.category }

    @Test
    fun `mergeStackable - 无同类堆叠时直接添加`() {
        val store = EntityStore<Material>(emptyList())
        val material = createMaterial(quantity = 5)

        val result = store.mergeStackable(
            item = material,
            matchPredicate = matchByNameRarityCategory(material),
            maxStack = 10
        )

        assertEquals(1, result.size)
        assertEquals(5, result.first().quantity)
    }

    @Test
    fun `mergeStackable - 合并后未超过maxStack时叠加到现有堆叠`() {
        val existing = createMaterial(id = "existing", quantity = 3)
        val store = EntityStore(listOf(existing))
        val newMaterial = createMaterial(id = "new", quantity = 5)

        val result = store.mergeStackable(
            item = newMaterial,
            matchPredicate = matchByNameRarityCategory(newMaterial),
            maxStack = 10
        )

        assertEquals(1, result.size)
        assertEquals("existing", result.first().id)
        assertEquals(8, result.first().quantity)
    }

    @Test
    fun `mergeStackable - 合并后超过maxStack时填满现有堆叠并新建溢出堆叠`() {
        val existing = createMaterial(id = "existing", quantity = 8)
        val store = EntityStore(listOf(existing))
        val newMaterial = createMaterial(id = "new", quantity = 5)

        val result = store.mergeStackable(
            item = newMaterial,
            matchPredicate = matchByNameRarityCategory(newMaterial),
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
        val existing = createMaterial(id = "existing", quantity = 7)
        val store = EntityStore(listOf(existing))
        val newMaterial = createMaterial(id = "new", quantity = 3)

        val result = store.mergeStackable(
            item = newMaterial,
            matchPredicate = matchByNameRarityCategory(newMaterial),
            maxStack = 10
        )

        assertEquals(1, result.size)
        assertEquals(10, result.first().quantity)
    }

    @Test
    fun `mergeStackable - 不丢失任何数量`() {
        // 验证：无论何种合并路径，总数量必须守恒
        val existing = createMaterial(id = "existing", quantity = 9)
        val store = EntityStore(listOf(existing))
        val newMaterial = createMaterial(id = "new", quantity = 5)

        val result = store.mergeStackable(
            item = newMaterial,
            matchPredicate = matchByNameRarityCategory(newMaterial),
            maxStack = 10
        )

        val totalAfter = result.sumOf { it.quantity }
        // 9 + 5 = 14，合并后总数量必须仍为 14
        assertEquals(14, totalAfter)
    }

    @Test
    fun `mergeStackable - matchPredicate不匹配时直接添加`() {
        val existing = createMaterial(id = "existing", name = "妖兽皮", quantity = 3)
        val store = EntityStore(listOf(existing))
        val newMaterial = createMaterial(
            id = "new", name = "妖兽骨", category = MaterialCategory.BEAST_BONE, quantity = 2
        )

        val result = store.mergeStackable(
            item = newMaterial,
            matchPredicate = matchByNameRarityCategory(newMaterial),
            maxStack = 10
        )

        // 名称/类目不同，不应合并
        assertEquals(2, result.size)
    }

    @Test
    fun `mergeStackable - 溢出堆叠保留原物品属性`() {
        val existing = createMaterial(id = "existing", name = "百年妖丹", rarity = 3, quantity = 8)
        val store = EntityStore(listOf(existing))
        val newMaterial = createMaterial(id = "new", name = "百年妖丹", rarity = 3, quantity = 5)

        val result = store.mergeStackable(
            item = newMaterial,
            matchPredicate = matchByNameRarityCategory(newMaterial),
            maxStack = 10
        )

        val overflow = result.first { it.id == "new" }
        assertEquals("百年妖丹", overflow.name)
        assertEquals(3, overflow.rarity)
        assertEquals(MaterialCategory.BEAST_HIDE, overflow.category)
        assertEquals(3, overflow.quantity)
    }
}
