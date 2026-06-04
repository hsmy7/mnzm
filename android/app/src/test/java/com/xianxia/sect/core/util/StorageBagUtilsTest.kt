package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.StorageBagItem
import org.junit.Assert.*
import org.junit.Test

class StorageBagUtilsTest {

    // ---- 辅助方法 ----

    private fun createItem(
        itemId: String = "item1",
        itemType: String = "pill",
        name: String = "Test Item",
        rarity: Int = 1,
        quantity: Int = 1,
        forgetYear: Int? = null,
        forgetMonth: Int? = null,
        forgetPhase: Int? = null
    ): StorageBagItem = StorageBagItem(
        itemId = itemId,
        itemType = itemType,
        name = name,
        rarity = rarity,
        quantity = quantity,
        forgetYear = forgetYear,
        forgetMonth = forgetMonth,
        forgetPhase = forgetPhase
    )

    // ---- isInCoolingPeriod ----

    @Test
    fun isInCoolingPeriod_noForgetYear_returnsFalse() {
        val item = createItem(forgetYear = null, forgetMonth = 2, forgetPhase = 1)
        assertFalse(StorageBagUtils.isInCoolingPeriod(item, 1, 1, 1))
    }

    @Test
    fun isInCoolingPeriod_noForgetMonth_returnsFalse() {
        val item = createItem(forgetYear = 1, forgetMonth = null, forgetPhase = 1)
        assertFalse(StorageBagUtils.isInCoolingPeriod(item, 1, 1, 1))
    }

    @Test
    fun isInCoolingPeriod_withPhase_withinCoolingPeriod() {
        // forgetYear=1, forgetMonth=1, forgetPhase=1 → totalPhases = 1*36 + 0*3 + 1 = 37
        // currentYear=1, currentMonth=2, currentPhase=1 → totalPhases = 1*36 + 1*3 + 1 = 40
        // diff = 40 - 37 = 3 < 9 → in cooling
        val item = createItem(forgetYear = 1, forgetMonth = 1, forgetPhase = 1)
        assertTrue(StorageBagUtils.isInCoolingPeriod(item, 1, 2, 1))
    }

    @Test
    fun isInCoolingPeriod_withPhase_outsideCoolingPeriod() {
        // forgetTotalPhases = 1*36 + 0*3 + 1 = 37
        // currentTotalPhases = 1*36 + 2*3 + 2 = 44
        // diff = 44 - 37 = 7 < 9 → still in cooling
        // Let's use larger gap: currentYear=1, currentMonth=4, currentPhase=1 → 1*36 + 3*3 + 1 = 46
        // diff = 46 - 37 = 9 → NOT in cooling (< 9 is the condition)
        val item = createItem(forgetYear = 1, forgetMonth = 1, forgetPhase = 1)
        assertFalse(StorageBagUtils.isInCoolingPeriod(item, 1, 4, 1))
    }

    @Test
    fun isInCoolingPeriod_withoutPhase_withinCoolingPeriod() {
        // forgetTotalMonths = 1*12 + 1 = 13
        // currentTotalMonths = 1*12 + 2 = 14
        // diff = 14 - 13 = 1 < 3 → in cooling
        val item = createItem(forgetYear = 1, forgetMonth = 1, forgetPhase = null)
        assertTrue(StorageBagUtils.isInCoolingPeriod(item, 1, 2, 1))
    }

    @Test
    fun isInCoolingPeriod_withoutPhase_outsideCoolingPeriod() {
        // forgetTotalMonths = 1*12 + 1 = 13
        // currentTotalMonths = 1*12 + 4 = 16
        // diff = 16 - 13 = 3 → NOT in cooling (< 3 is the condition)
        val item = createItem(forgetYear = 1, forgetMonth = 1, forgetPhase = null)
        assertFalse(StorageBagUtils.isInCoolingPeriod(item, 1, 4, 1))
    }

    // ---- decreaseItemQuantity ----

    @Test
    fun decreaseItemQuantity_reducesQuantity() {
        val items = listOf(createItem(itemId = "i1", quantity = 5))
        val result = StorageBagUtils.decreaseItemQuantity(items, "i1", 2)
        assertEquals(1, result.size)
        assertEquals(3, result[0].quantity)
    }

    @Test
    fun decreaseItemQuantity_removesItemWhenZero() {
        val items = listOf(createItem(itemId = "i1", quantity = 3))
        val result = StorageBagUtils.decreaseItemQuantity(items, "i1", 3)
        assertTrue(result.isEmpty())
    }

    @Test
    fun decreaseItemQuantity_itemNotFound_returnsOriginal() {
        val items = listOf(createItem(itemId = "i1", quantity = 5))
        val result = StorageBagUtils.decreaseItemQuantity(items, "i2", 1)
        assertSame(items, result)
    }

    @Test
    fun decreaseItemQuantity_defaultAmountIs1() {
        val items = listOf(createItem(itemId = "i1", quantity = 5))
        val result = StorageBagUtils.decreaseItemQuantity(items, "i1")
        assertEquals(4, result[0].quantity)
    }

    // ---- increaseItemQuantity ----

    @Test
    fun increaseItemQuantity_existingItem_stacks() {
        val items = listOf(createItem(itemId = "i1", itemType = "pill", quantity = 3))
        val newItem = createItem(itemId = "i1", itemType = "pill", quantity = 2)
        val result = StorageBagUtils.increaseItemQuantity(items, newItem)
        assertEquals(1, result.size)
        assertEquals(5, result[0].quantity)
    }

    @Test
    fun increaseItemQuantity_newItem_addsToList() {
        val items = listOf(createItem(itemId = "i1", quantity = 3))
        val newItem = createItem(itemId = "i2", itemType = "herb", quantity = 1)
        val result = StorageBagUtils.increaseItemQuantity(items, newItem)
        assertEquals(2, result.size)
    }

    @Test
    fun increaseItemQuantity_respectsMaxStack() {
        val items = listOf(createItem(itemId = "i1", itemType = "pill", quantity = 8))
        val newItem = createItem(itemId = "i1", itemType = "pill", quantity = 5)
        val result = StorageBagUtils.increaseItemQuantity(items, newItem, maxStack = 10)
        assertEquals(10, result[0].quantity)
    }

    @Test
    fun increaseItemQuantity_newItem_respectsMaxStack() {
        val items = emptyList<StorageBagItem>()
        val newItem = createItem(itemId = "i1", itemType = "pill", quantity = 20)
        val result = StorageBagUtils.increaseItemQuantity(items, newItem, maxStack = 10)
        assertEquals(10, result[0].quantity)
    }

    @Test
    fun increaseItemQuantity_differentItemType_doesNotStack() {
        val items = listOf(createItem(itemId = "i1", itemType = "pill", quantity = 3))
        val newItem = createItem(itemId = "i1", itemType = "herb", quantity = 2)
        val result = StorageBagUtils.increaseItemQuantity(items, newItem)
        assertEquals(2, result.size)
    }

    // ---- decreaseMultipleItems ----

    @Test
    fun decreaseMultipleItems_decreasesAll() {
        val items = listOf(
            createItem(itemId = "i1", quantity = 5),
            createItem(itemId = "i2", quantity = 3)
        )
        val result = StorageBagUtils.decreaseMultipleItems(items, listOf("i1", "i2"))
        assertEquals(2, result.size)
        assertEquals(4, result[0].quantity)
        assertEquals(2, result[1].quantity)
    }

    @Test
    fun decreaseMultipleItems_emptyIds_returnsOriginal() {
        val items = listOf(createItem(itemId = "i1", quantity = 5))
        val result = StorageBagUtils.decreaseMultipleItems(items, emptyList())
        assertEquals(1, result.size)
        assertEquals(5, result[0].quantity)
    }

    // ---- hasEnoughItems ----

    @Test
    fun hasEnoughItems_sufficientQuantity_returnsTrue() {
        val items = listOf(createItem(itemId = "i1", quantity = 5))
        assertTrue(StorageBagUtils.hasEnoughItems(items, "i1", 3))
    }

    @Test
    fun hasEnoughItems_exactQuantity_returnsTrue() {
        val items = listOf(createItem(itemId = "i1", quantity = 3))
        assertTrue(StorageBagUtils.hasEnoughItems(items, "i1", 3))
    }

    @Test
    fun hasEnoughItems_insufficientQuantity_returnsFalse() {
        val items = listOf(createItem(itemId = "i1", quantity = 2))
        assertFalse(StorageBagUtils.hasEnoughItems(items, "i1", 3))
    }

    @Test
    fun hasEnoughItems_itemNotFound_returnsFalse() {
        val items = listOf(createItem(itemId = "i1", quantity = 5))
        assertFalse(StorageBagUtils.hasEnoughItems(items, "i2", 1))
    }

    @Test
    fun hasEnoughItems_defaultRequiredQuantityIs1() {
        val items = listOf(createItem(itemId = "i1", quantity = 1))
        assertTrue(StorageBagUtils.hasEnoughItems(items, "i1"))
    }

    // ---- getItemQuantity ----

    @Test
    fun getItemQuantity_existingItem_returnsQuantity() {
        val items = listOf(createItem(itemId = "i1", quantity = 7))
        assertEquals(7, StorageBagUtils.getItemQuantity(items, "i1"))
    }

    @Test
    fun getItemQuantity_missingItem_returnsZero() {
        val items = listOf(createItem(itemId = "i1", quantity = 7))
        assertEquals(0, StorageBagUtils.getItemQuantity(items, "i2"))
    }

    @Test
    fun getItemQuantity_emptyList_returnsZero() {
        assertEquals(0, StorageBagUtils.getItemQuantity(emptyList(), "i1"))
    }

    // ---- 扩展函数 ----

    @Test
    fun decreaseItem_extensionFunction_works() {
        val items = listOf(createItem(itemId = "i1", quantity = 5))
        val result = items.decreaseItem("i1", 2)
        assertEquals(3, result[0].quantity)
    }

    @Test
    fun increaseItem_extensionFunction_works() {
        val items = listOf(createItem(itemId = "i1", itemType = "pill", quantity = 3))
        val newItem = createItem(itemId = "i1", itemType = "pill", quantity = 2)
        val result = items.increaseItem(newItem)
        assertEquals(5, result[0].quantity)
    }

    @Test
    fun hasItem_extensionFunction_works() {
        val items = listOf(createItem(itemId = "i1", quantity = 5))
        assertTrue(items.hasItem("i1", 3))
        assertFalse(items.hasItem("i1", 6))
    }

    @Test
    fun getItemQty_extensionFunction_works() {
        val items = listOf(createItem(itemId = "i1", quantity = 7))
        assertEquals(7, items.getItemQty("i1"))
    }
}
