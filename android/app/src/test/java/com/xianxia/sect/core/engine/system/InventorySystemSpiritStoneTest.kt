package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.SpiritStoneExchange
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class InventorySystemSpiritStoneTest {

    private lateinit var system: InventorySystem
    private lateinit var stateStore: GameStateStore
    private lateinit var scopeProvider: ApplicationScopeProvider
    private lateinit var inventoryConfig: InventoryConfig

    @Before
    fun setUp() {
        scopeProvider = ApplicationScopeProvider()
        stateStore = GameStateStoreImpl(scopeProvider, mock(GameStateRepository::class.java))
        inventoryConfig = InventoryConfig()
        system = InventorySystem(stateStore, inventoryConfig)
        system.initialize()
        runBlocking {
            stateStore.reset()
            stateStore.update {
                gameData = gameData.copy(
                    spiritStones = 0L,
                    midGradeSpiritStones = 0L,
                    highGradeSpiritStones = 0L
                )
            }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            delay(100)
            stateStore.reset()
        }
    }

    // ═══ addSpiritStones ═══

    @Test
    fun `addSpiritStones - increases low grade stones`() = runBlocking {
        system.addSpiritStones(1_000L, SpiritStoneGrade.LOW)
        assertEquals(1_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `addSpiritStones - increases mid and high grade stones`() = runBlocking {
        system.addSpiritStones(5L, SpiritStoneGrade.MID)
        system.addSpiritStones(2L, SpiritStoneGrade.HIGH)
        assertEquals(5L, system.getSpiritStones(SpiritStoneGrade.MID))
        assertEquals(2L, system.getSpiritStones(SpiritStoneGrade.HIGH))
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `addSpiritStones - non positive amount returns current value`() = runBlocking {
        assertEquals(0L, system.addSpiritStones(0L, SpiritStoneGrade.LOW))
        assertEquals(0L, system.addSpiritStones(-1L, SpiritStoneGrade.MID))
    }

    // ═══ deductSpiritStones ═══

    @Test
    fun `deductSpiritStones - decreases stones`() = runBlocking {
        system.addSpiritStones(1_000L, SpiritStoneGrade.LOW)
        system.deductSpiritStones(300L, SpiritStoneGrade.LOW)
        assertEquals(700L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - cannot go below zero`() = runBlocking {
        system.addSpiritStones(100L, SpiritStoneGrade.LOW)
        system.deductSpiritStones(500L, SpiritStoneGrade.LOW)
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - non positive amount returns current value`() = runBlocking {
        system.addSpiritStones(100L, SpiritStoneGrade.LOW)
        assertEquals(100L, system.deductSpiritStones(0L, SpiritStoneGrade.LOW))
    }

    // ═══ canAfford ═══

    @Test
    fun `canAfford - reflects current balance`() = runBlocking {
        system.addSpiritStones(1_000L, SpiritStoneGrade.LOW)
        assertTrue(system.canAfford(1_000L, SpiritStoneGrade.LOW))
        assertFalse(system.canAfford(1_001L, SpiritStoneGrade.LOW))
    }

    // ═══ exchangeSpiritStones ═══

    @Test
    fun `exchangeSpiritStones - low to mid success`() = runBlocking {
        system.addSpiritStones(25_000L, SpiritStoneGrade.LOW)
        assertTrue(system.exchangeSpiritStones(25_000L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID))
        assertEquals(1_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
        assertEquals(3L, system.getSpiritStones(SpiritStoneGrade.MID))
    }

    @Test
    fun `exchangeSpiritStones - mid to low success`() = runBlocking {
        system.addSpiritStones(3L, SpiritStoneGrade.MID)
        assertTrue(system.exchangeSpiritStones(3L, SpiritStoneGrade.MID, SpiritStoneGrade.LOW))
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.MID))
        assertEquals(24_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `exchangeSpiritStones - fails when insufficient`() = runBlocking {
        system.addSpiritStones(100L, SpiritStoneGrade.LOW)
        assertFalse(system.exchangeSpiritStones(10_000L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID))
        assertEquals(100L, system.getSpiritStones(SpiritStoneGrade.LOW))
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.MID))
    }

    @Test
    fun `exchangeSpiritStones - same grade returns false`() = runBlocking {
        system.addSpiritStones(100L, SpiritStoneGrade.LOW)
        assertFalse(system.exchangeSpiritStones(100L, SpiritStoneGrade.LOW, SpiritStoneGrade.LOW))
    }

    @Test
    fun `exchangeSpiritStones - zero or negative returns false`() = runBlocking {
        system.addSpiritStones(100L, SpiritStoneGrade.LOW)
        assertFalse(system.exchangeSpiritStones(0L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID))
        assertFalse(system.exchangeSpiritStones(-1L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID))
    }

    @Test
    fun `exchangeSpiritStones - high to mid and low preserves unrelated balances`() = runBlocking {
        system.addSpiritStones(2L, SpiritStoneGrade.HIGH)
        system.addSpiritStones(100L, SpiritStoneGrade.LOW)
        assertTrue(system.exchangeSpiritStones(1L, SpiritStoneGrade.HIGH, SpiritStoneGrade.MID))
        assertEquals(1L, system.getSpiritStones(SpiritStoneGrade.HIGH))
        assertEquals(8_000L, system.getSpiritStones(SpiritStoneGrade.MID))
        assertEquals(100L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    // ═══ auto-sell on deduct ═══

    @Test
    fun `deductSpiritStones - auto sell mid covers shortfall`() = runBlocking {
        system.addSpiritStones(5L, SpiritStoneGrade.MID)
        stateStore.update { gameData = gameData.copy(autoSellMidGradeForPurchase = true) }
        system.deductSpiritStones(30_000L, SpiritStoneGrade.LOW)
        assertEquals(1L, system.getSpiritStones(SpiritStoneGrade.MID))
        assertEquals(2_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - auto sell mid not triggered when disabled`() = runBlocking {
        system.addSpiritStones(5L, SpiritStoneGrade.MID)
        system.deductSpiritStones(30_000L, SpiritStoneGrade.LOW)
        assertEquals(5L, system.getSpiritStones(SpiritStoneGrade.MID))
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - auto sell high covers shortfall`() = runBlocking {
        system.addSpiritStones(2L, SpiritStoneGrade.HIGH)
        stateStore.update { gameData = gameData.copy(autoSellHighGradeForPurchase = true) }
        system.deductSpiritStones(100_000L, SpiritStoneGrade.LOW)
        assertEquals(1L, system.getSpiritStones(SpiritStoneGrade.HIGH))
        assertEquals(63_900_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - auto sell mid then high for large shortfall`() = runBlocking {
        system.addSpiritStones(1L, SpiritStoneGrade.MID)
        system.addSpiritStones(1L, SpiritStoneGrade.HIGH)
        stateStore.update {
            gameData = gameData.copy(
                autoSellMidGradeForPurchase = true,
                autoSellHighGradeForPurchase = true
            )
        }
        system.deductSpiritStones(10_000L, SpiritStoneGrade.LOW)
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.MID))
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.HIGH))
        assertEquals(63_998_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - sufficient low grade skips auto sell`() = runBlocking {
        system.addSpiritStones(50_000L, SpiritStoneGrade.LOW)
        system.addSpiritStones(5L, SpiritStoneGrade.MID)
        stateStore.update { gameData = gameData.copy(autoSellMidGradeForPurchase = true) }
        system.deductSpiritStones(30_000L, SpiritStoneGrade.LOW)
        assertEquals(20_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
        assertEquals(5L, system.getSpiritStones(SpiritStoneGrade.MID))
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.HIGH))
    }
}
