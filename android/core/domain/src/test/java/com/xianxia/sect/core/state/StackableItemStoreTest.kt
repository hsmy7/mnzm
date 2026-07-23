package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.HasId
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.StackableItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StackableItemStoreTest {

    private lateinit var store: StackableItemStore<TestItem>

    data class TestItem(
        override val id: String,
        override val name: String,
        override val rarity: Int,
        override val quantity: Int,
        override val isLocked: Boolean = false,
        val category: String = ""
    ) : HasId, StackableItem {
        override fun withQuantity(newQuantity: Int) = copy(quantity = newQuantity)
    }

    @Before
    fun setup() {
        store = StackableItemStore(
            initialItems = emptyList(),
            stackKeyOf = { StackKey.of(it.name, it.rarity, it.category) },
            maxStack = 99,
            maxSlots = { 10 },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
    }

    @Test
    fun `add - new item succeeds`() {
        val item = TestItem("1", "灵草", 1, 5)
        val result = store.add(item)
        assertTrue(result.isSuccess)
        assertEquals(5, store.quantity("1"))
    }

    @Test
    fun `add - merge same key stacks`() {
        store.add(TestItem("1", "灵草", 1, 5, category = "common"))
        val result = store.add(TestItem("2", "灵草", 1, 3, category = "common"))
        assertTrue(result.isSuccess)
        assertEquals(8, store.quantity("1")) // merged into first
        assertNull(store.get("2")) // second not added separately
    }

    @Test
    fun `add - different category does not merge`() {
        store.add(TestItem("1", "灵草", 1, 5, category = "common"))
        store.add(TestItem("2", "灵草", 1, 5, category = "rare"))
        assertEquals(2, store.size)
    }

    @Test
    fun `add - maxStack overflow creates new stack`() {
        store.add(TestItem("1", "药草", 1, 95))
        val result = store.add(TestItem("2", "药草", 1, 10))
        // 新行为：溢出部分创建新堆叠，不再丢失物品
        assertTrue("应为 Success，溢出创建新堆叠: $result", result is DomainResult.Success)
        assertEquals("原堆叠填满至上限", 99, store.quantity("1"))
        assertEquals("新增一个溢出堆叠", 2, store.size)
        // 第二个堆叠（新创建）的 quantity = 6
        val newItem = store.all().find { it.id != "1" }
        assertNotNull("应有新堆叠", newItem)
        assertEquals("溢出数量", 6, newItem!!.quantity)
    }

    @Test
    fun `add - overflow at capacity limit returns Partial`() {
        // 填满 10 个槽位，但有一个与 id1 相同的堆叠
        store.add(TestItem("1", "药草", 1, 95))
        // 用满其余 9 个槽位
        for (i in 2..10) store.add(TestItem("$i", "其他$i", 1, 1))
        // 此时 10 个槽位全满，同 key 的药草已满 95
        // 再添加 10 个药草 → 填满 id1 到 99，但无空槽放溢出 6 个
        val result = store.add(TestItem("overflow", "药草", 1, 10))
        assertTrue("仓满时应返回 Partial: $result", result is DomainResult.Partial)
        assertEquals("原堆叠填满至上限", 99, store.quantity("1"))
        assertEquals("仓满不创建新堆叠", 10, store.size)
        assertEquals("溢出 6 个", 6, (result as DomainResult.Partial).overflow)
    }

    @Test
    fun `add - full slots returns Failure`() {
        for (i in 1..10) store.add(TestItem("$i", "Item$i", 1, 1))
        val result = store.add(TestItem("11", "Item11", 1, 1))
        assertTrue(result is DomainResult.Failure)
    }

    @Test
    fun `remove - reduces quantity`() {
        store.add(TestItem("1", "灵草", 1, 10))
        store.remove("1", 3)
        assertEquals(7, store.quantity("1"))
    }

    @Test
    fun `remove - deletes item when quantity reaches zero`() {
        store.add(TestItem("1", "灵草", 1, 5))
        store.remove("1", 5)
        assertNull(store.get("1"))
    }

    @Test
    fun `remove - locked item returns Failure`() {
        store.add(TestItem("1", "灵草", 1, 5, isLocked = true))
        val result = store.remove("1", 1)
        assertTrue(result is DomainResult.Failure)
        assertEquals(5, store.quantity("1"))
    }

    @Test
    fun `remove - not found returns Failure`() {
        val result = store.remove("nonexistent", 1)
        assertTrue(result is DomainResult.Failure)
    }

    @Test
    fun `add - fill multiple existing stacks`() {
        // 同 key 的 3 个非满堆叠：80, 80, 80, maxStack=99, add 50
        store.add(TestItem("1", "药草", 1, 80))
        store.add(TestItem("2", "药草", 1, 80))
        store.add(TestItem("3", "药草", 1, 80))
        assertEquals(3, store.size)
        val result = store.add(TestItem("4", "药草", 1, 50))
        assertTrue("应全部容纳: $result", result.isSuccess)
        // 80+19=99, 80+19=99, 80+12=92
        assertEquals(99, store.quantity("1"))
        assertEquals(99, store.quantity("2"))
        assertEquals(92, store.quantity("3"))
        assertEquals(3, store.size) // 不创建新堆叠
    }

    @Test
    fun `add - multiple stacks with partial fill`() {
        store.add(TestItem("1", "药草", 1, 90))
        store.add(TestItem("2", "药草", 1, 90))
        // add 20: 第一堆叠 90→99(用9), 第二堆叠 90→99(用9), 剩余2创建新堆叠
        val result = store.add(TestItem("3", "药草", 1, 20))
        assertTrue("应成功: $result", result.isSuccess)
        assertEquals(99, store.quantity("1"))
        assertEquals(99, store.quantity("2"))
        assertEquals(3, store.size)
        val newItem = store.all().find { it.id == "3" }
        assertNotNull(newItem)
        assertEquals(2, newItem!!.quantity)
    }

    @Test
    fun `remove - keyIndex updates after full removal`() {
        // 用不同 category 防止合并（确保两个独立堆叠）
        val catStore = StackableItemStore(
            initialItems = emptyList(),
            stackKeyOf = { StackKey.of(it.name, it.rarity, (it as TestItem).category) },
            maxStack = 99, maxSlots = { 10 },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
        catStore.add(TestItem("1", "药草", 1, 5, category = "a"))
        catStore.add(TestItem("2", "药草", 1, 5, category = "b"))
        assertEquals("应有 2 个独立堆叠", 2, catStore.size)
        catStore.remove("1", 5) // fully remove
        assertNull(catStore.get("1"))
        assertEquals(1, catStore.size)
    }

    @Test
    fun `different merge keys prevent merge`() {
        val catStore = StackableItemStore(
            initialItems = emptyList(),
            stackKeyOf = { StackKey.of(it.name, it.rarity, (it as TestItem).category) },
            maxStack = 99, maxSlots = { 100 },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
        catStore.add(TestItem("e1", "铁剑", 1, 1, category = "weapon"))
        catStore.add(TestItem("e2", "铁剑", 1, 1, category = "armor"))
        assertEquals(2, catStore.size)
    }
}
