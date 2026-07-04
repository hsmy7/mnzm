package com.xianxia.sect.core.concurrent

import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.BattleLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 并行结算结果类的单元测试。
 *
 * 验证 [PartnerMatchResult] 和 [ProductionBatchResult] 的 [apply] 方法
 * 能正确写入选定状态。
 */
@RunWith(RobolectricTestRunner::class)
class ParallelSettlementResultTest {

    // ═══════════════════════════════════════════════════════════════
    // PartnerMatchResult
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `PartnerMatchResult apply - writes partner IDs and loyalty`() = runBlocking {
        val tables = DiscipleTables()
        tables.ids.add(1); tables.ids.add(2)

        val state = createState(tables)

        val result = PartnerMatchResult(
            partnerUpdates = mapOf(1 to "2", 2 to "1"),
            loyaltyUpdates = mapOf(1 to 60, 2 to 55),
            consentRequest = null
        )
        result.apply(state)

        assertEquals("2", tables.partnerIds.getOrNull(1))
        assertEquals("1", tables.partnerIds.getOrNull(2))
        assertEquals(60, tables.loyalties.getOrDefault(1, 0))
        assertEquals(55, tables.loyalties.getOrDefault(2, 0))
    }

    @Test
    fun `PartnerMatchResult apply - clears partner IDs when null`() = runBlocking {
        val tables = DiscipleTables()
        tables.ids.add(1); tables.ids.add(2)
        tables.partnerIds[1] = "2"
        tables.partnerIds[2] = "1"

        val state = createState(tables)

        val result = PartnerMatchResult(
            partnerUpdates = mapOf(1 to null, 2 to null),
            loyaltyUpdates = emptyMap(),
            consentRequest = null
        )
        result.apply(state)

        assertNull(tables.partnerIds.getOrNull(1))
        assertNull(tables.partnerIds.getOrNull(2))
    }

    @Test
    fun `PartnerMatchResult apply - consent mode sets notification`() = runBlocking {
        val tables = DiscipleTables()
        tables.ids.add(1)
        tables.names[1] = "male1"
        tables.genders[1] = "male"
        tables.realms[1] = 5
        tables.realmLayers[1] = 1
        tables.ages[1] = 20
        tables.isAlive[1] = 1
        tables.spiritRootTypes[1] = "fire"
        tables.ids.add(2)
        tables.names[2] = "female1"
        tables.genders[2] = "female"
        tables.realms[2] = 5
        tables.realmLayers[2] = 1
        tables.ages[2] = 20
        tables.isAlive[2] = 1
        tables.spiritRootTypes[2] = "water"

        val state = createState(tables)

        val result = PartnerMatchResult(
            partnerUpdates = emptyMap(),
            loyaltyUpdates = emptyMap(),
            consentRequest = 1 to 2
        )
        result.apply(state)

        assertTrue(state.pendingNotification is GameNotification.MarriageRequest)
    }

    @Test
    fun `PartnerMatchResult apply - consent mode skips direct pairing`() = runBlocking {
        val tables = DiscipleTables()
        tables.ids.add(1)
        tables.genders[1] = "male"
        tables.ages[1] = 20
        tables.isAlive[1] = 1
        tables.partnerIds[1] = "old_partner"
        tables.ids.add(2)
        tables.genders[2] = "female"
        tables.ages[2] = 20
        tables.isAlive[2] = 1

        val state = createState(tables)

        val result = PartnerMatchResult(
            partnerUpdates = mapOf(1 to "2"),
            loyaltyUpdates = mapOf(1 to 99),
            consentRequest = 1 to 2
        )
        result.apply(state)

        assertEquals("old_partner", tables.partnerIds.getOrNull(1))
        assertNotEquals(99, tables.loyalties.getOrDefault(1, 0))
    }

    // ═══════════════════════════════════════════════════════════════
    // ProductionBatchResult
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `ProductionBatchResult apply - replaces entity stores`() = runBlocking {
        val tables = DiscipleTables()
        val state = createState(tables)

        val slots = listOf(ProductionSlot.createIdle(slotIndex = 0,
            buildingType = com.xianxia.sect.core.model.production.BuildingType.FORGE))

        var applied = false
        val delta = ProductionBatchDelta()
        delta.itemsToAdd.add(ItemOp.AddPill(Pill(name = "聚气丹", rarity = 2, quantity = 5)))
        delta.itemsToAdd.add(ItemOp.AddHerb(
            com.xianxia.sect.core.model.Herb(name = "龙涎草", rarity = 3, quantity = 10, category = "灵草")))
        delta.itemsToAdd.add(ItemOp.AddEquipment(EquipmentStack(name = "铁剑", rarity = 2, quantity = 1)))
        delta.finalSlots = slots
        val result = ProductionBatchResult(delta = delta, onApplied = { applied = true })
        result.apply(state)
        state.pills.freeze()
        state.herbs.freeze()
        state.equipmentStacks.freeze()

        assertEquals(1, state.pills.all().size)
        assertEquals("聚气丹", state.pills.all()[0].name)
        assertEquals(1, state.herbs.all().size)
        assertEquals(1, state.equipmentStacks.all().size)
        assertEquals(1, state.productionSlots.size)
        assertTrue(applied)
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    private fun createState(tables: DiscipleTables): MutableGameState {
        return MutableGameState(
            gameData = GameData(),
            discipleTables = tables,
            equipmentStacks = EntityStore(emptyList()),
            equipmentInstances = EntityStore(emptyList()),
            manualStacks = EntityStore(emptyList()),
            manualInstances = EntityStore(emptyList()),
            pills = EntityStore(emptyList()),
            materials = EntityStore(emptyList()),
            herbs = EntityStore(emptyList()),
            seeds = EntityStore(emptyList()),
            storageBags = EntityStore(emptyList()),
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }
}
