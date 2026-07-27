package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.exploration.LootCalculator
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LootCalculatorTest {

    private lateinit var calculator: LootCalculator

    @Before
    fun setUp() {
        calculator = LootCalculator(GameRngManager().also { it.initSystemSeed(42L) })
    }

    private fun newState(gd: GameData = GameData()): MutableGameState {
        return MutableGameState(
            gameData = gd,
            discipleTables = DiscipleTables(),
            equipmentStacks = EntityStore<EquipmentStack>(),
            equipmentInstances = EntityStore<EquipmentInstance>(),
            manualStacks = EntityStore<ManualStack>(),
            manualInstances = EntityStore<ManualInstance>(),
            pills = EntityStore<Pill>(),
            materials = EntityStore<Material>(),
            herbs = EntityStore<Herb>(),
            seeds = EntityStore<Seed>(),
            storageBags = EntityStore<StorageBag>(),
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )
    }

    @Test
    fun `computeLootPlan returns empty when no items`() {
        val state = newState()
        val plan = calculator.computeLootPlan(state.gameData, state)
        assertEquals(0L, plan.stolenSpiritStones)
        assertEquals(0, plan.stolenBagCount)
        assertTrue(plan.stolenItems.isEmpty())
    }

    @Test
    fun `computeLootPlan handles zero spirit stones`() {
        val state = newState(GameData(spiritStones = 0))
        val plan = calculator.computeLootPlan(state.gameData, state)
        assertEquals(0L, plan.stolenSpiritStones)
    }

    @Test
    fun `computeLootPlan handles negative quantities safely`() {
        val state = newState()
        state.materials.add(Material(id = "m1", name = "铁矿石", rarity = 1, quantity = -1))
        val plan = calculator.computeLootPlan(state.gameData, state)
        assertNotNull(plan)
    }

    @Test
    fun `applyLoot single pass bag deduction`() = runBlocking {
        val state = newState()
        state.storageBags.add(StorageBag(id = "bag1", name = "储物袋", rarity = 1, quantity = 5))
        state.storageBags.add(StorageBag(id = "bag2", name = "灵兽袋", rarity = 2, quantity = 3))

        val plan = LootCalculator.BeastLootData(stolenBagCount = 4)
        calculator.applyLoot(state, plan)

        val remaining = state.storageBags.items.sumOf { it.quantity }
        assertTrue("should deduct ≤ 4 units, remaining=$remaining", remaining <= 8 - 4)
    }

    @Test
    fun `applyLoot filters zero quantity items`() = runBlocking {
        val state = newState()
        state.materials.add(Material(id = "m1", name = "铁矿石", rarity = 1, quantity = 1))

        val plan = LootCalculator.BeastLootData(stolenItems = listOf(
            LootCalculator.LootedItem("m1", "铁矿石", "material", 1, 1)
        ))
        calculator.applyLoot(state, plan)

        assertTrue("zero quantity material should be filtered",
            state.materials.items.none { it.id == "m1" })
    }

    @Test
    fun `applyLoot filters manualStacks to zero`() = runBlocking {
        val state = newState()
        state.manualStacks.add(ManualStack(id = "man1", name = "基础功法", rarity = 1, quantity = 2))

        val plan = LootCalculator.BeastLootData(stolenItems = listOf(
            LootCalculator.LootedItem("man1", "基础功法", "manual", 1, 2)
        ))
        calculator.applyLoot(state, plan)

        assertTrue("zero quantity manualStack should be filtered",
            state.manualStacks.items.none { it.id == "man1" })
    }

    @Test
    fun `computeLootPlan filters zero quantity items`() {
        val state = newState(GameData(spiritStones = 100000))
        state.materials.add(Material(id = "m1", name = "铁矿石", rarity = 1, quantity = 0))
        state.materials.add(Material(id = "m2", name = "灵石碎片", rarity = 1, quantity = 5))

        val plan = calculator.computeLootPlan(state.gameData, state)
        assertNotNull(plan)
    }

    @Test
    fun `toDetailString formats correctly`() {
        val loot = LootCalculator.BeastLootData(
            stolenSpiritStones = 20000,
            stolenItems = listOf(
                LootCalculator.LootedItem("m1", "铁矿石", "material", 1, 3)
            )
        )
        val str = loot.toDetailString("虎妖")
        assertTrue(str.contains("20000"))
        assertTrue(str.contains("铁矿石"))
        assertTrue(str.contains("虎妖"))
    }

    @Test
    fun `toRewardItems converts correctly`() {
        val loot = LootCalculator.BeastLootData(
            stolenSpiritStones = 20000,
            stolenBagCount = 1,
            stolenItems = listOf(
                LootCalculator.LootedItem("m1", "铁矿石", "material", 1, 3)
            )
        )
        val items = loot.toRewardItems()
        assertTrue(items.any { it.type == "spiritStones" })
        assertTrue(items.any { it.type == "material" })
        assertTrue(items.any { it.type == "storageBag" })
    }

    @Test
    fun `applyLoot deducts full bags first`() = runBlocking {
        val state = newState()
        state.storageBags.add(StorageBag(id = "a", name = "小袋", rarity = 1, quantity = 2))
        state.storageBags.add(StorageBag(id = "b", name = "大袋", rarity = 2, quantity = 5))
        state.storageBags.add(StorageBag(id = "c", name = "中袋", rarity = 1, quantity = 3))

        val plan = LootCalculator.BeastLootData(stolenBagCount = 7)
        calculator.applyLoot(state, plan)

        val remaining = state.storageBags.items.sumOf { it.quantity }
        assertTrue("7 of 10 deducted, 3 remaining, actual=$remaining", remaining <= 3)
    }

    @Test
    fun `applyLoot handles material deduction`() = runBlocking {
        val state = newState()
        state.materials.add(Material(id = "m1", name = "铁", rarity = 1, quantity = 10))
        state.materials.add(Material(id = "m2", name = "铜", rarity = 1, quantity = 5))

        val plan = LootCalculator.BeastLootData(stolenItems = listOf(
            LootCalculator.LootedItem("m1", "铁", "material", 1, 3)
        ))
        calculator.applyLoot(state, plan)

        assertEquals(7, state.materials.items.find { it.id == "m1" }?.quantity)
        assertEquals(5, state.materials.items.find { it.id == "m2" }?.quantity)
    }

    @Test
    fun `applyLoot handles pill deduction`() = runBlocking {
        val state = newState()
        state.pills.add(Pill(id = "p1", name = "丹药", rarity = 1, quantity = 5))

        val plan = LootCalculator.BeastLootData(stolenItems = listOf(
            LootCalculator.LootedItem("p1", "丹药", "pill", 1, 2)
        ))
        calculator.applyLoot(state, plan)

        assertEquals(3, state.pills.items.find { it.id == "p1" }?.quantity)
    }

    @Test
    fun `applyLoot handles equipment deduction and filters zero`() = runBlocking {
        val state = newState()
        state.equipmentStacks.add(EquipmentStack(id = "e1", name = "剑", rarity = 1, quantity = 1))

        val plan = LootCalculator.BeastLootData(stolenItems = listOf(
            LootCalculator.LootedItem("e1", "剑", "equipment", 1, 1)
        ))
        calculator.applyLoot(state, plan)

        assertTrue(state.equipmentStacks.items.isEmpty())
    }

    @Test
    fun `applyLoot handles spirit stone deduction`() = runBlocking {
        val state = newState(GameData(spiritStones = 50000))
        val plan = LootCalculator.BeastLootData(stolenSpiritStones = 20000)
        calculator.applyLoot(state, plan)
        assertEquals(30000, state.gameData.spiritStones)
    }

}
