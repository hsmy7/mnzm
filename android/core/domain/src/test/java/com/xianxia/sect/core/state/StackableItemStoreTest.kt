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
    fun `add - maxStack overflow returns Partial`() {
        store.add(TestItem("1", "药草", 1, 95))
        val result = store.add(TestItem("2", "药草", 1, 10))
        assertTrue(result is DomainResult.Partial)
        assertEquals(99, store.quantity("1"))
        assertEquals(6, (result as DomainResult.Partial).overflow)
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
