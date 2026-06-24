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
        system = InventorySystem(stateStore, scopeProvider, inventoryConfig)
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
        scopeProvider.close()
    }

    @Test
    fun `addSpiritStones - increases low grade stones`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(1_000L, SpiritStoneGrade.LOW)
        }
        assertEquals(1_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `addSpiritStones - increases mid and high grade stones`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(5L, SpiritStoneGrade.MID)
            system.addSpiritStones(2L, SpiritStoneGrade.HIGH)
        }
        assertEquals(5L, system.getSpiritStones(SpiritStoneGrade.MID))
        assertEquals(2L, system.getSpiritStones(SpiritStoneGrade.HIGH))
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `addSpiritStones - non positive amount returns current value`() = runBlocking {
        stateStore.update {
            assertEquals(0L, system.addSpiritStones(0L, SpiritStoneGrade.LOW))
            assertEquals(0L, system.addSpiritStones(-1L, SpiritStoneGrade.MID))
        }
    }

    @Test
    fun `deductSpiritStones - decreases stones`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(1_000L, SpiritStoneGrade.LOW)
            system.deductSpiritStones(300L, SpiritStoneGrade.LOW)
        }
        assertEquals(700L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - cannot go below zero`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(100L, SpiritStoneGrade.LOW)
            system.deductSpiritStones(500L, SpiritStoneGrade.LOW)
        }
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - non positive amount returns current value`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(100L, SpiritStoneGrade.LOW)
            assertEquals(100L, system.deductSpiritStones(0L, SpiritStoneGrade.LOW))
        }
    }

    @Test
    fun `canAfford - reflects current balance inside transaction`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(1_000L, SpiritStoneGrade.LOW)
            assertTrue(system.canAfford(1_000L, SpiritStoneGrade.LOW))
            assertFalse(system.canAfford(1_001L, SpiritStoneGrade.LOW))
        }
    }

    @Test
    fun `exchangeSpiritStones - low to mid success`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(25_000L, SpiritStoneGrade.LOW)
            assertTrue(system.exchangeSpiritStones(25_000L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID))
        }
        // 25_000 / 8_000 = 3 MID, remainder 1_000
        assertEquals(1_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
        assertEquals(3L, system.getSpiritStones(SpiritStoneGrade.MID))
    }

    @Test
    fun `exchangeSpiritStones - mid to low success`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(3L, SpiritStoneGrade.MID)
            assertTrue(system.exchangeSpiritStones(3L, SpiritStoneGrade.MID, SpiritStoneGrade.LOW))
        }
        // 3 MID × 8_000 = 24_000 LOW
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.MID))
        assertEquals(24_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `exchangeSpiritStones - fails when insufficient`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(100L, SpiritStoneGrade.LOW)
            assertFalse(system.exchangeSpiritStones(10_000L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID))
        }
        assertEquals(100L, system.getSpiritStones(SpiritStoneGrade.LOW))
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.MID))
    }

    @Test
    fun `exchangeSpiritStones - same grade returns false`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(100L, SpiritStoneGrade.LOW)
            assertFalse(system.exchangeSpiritStones(100L, SpiritStoneGrade.LOW, SpiritStoneGrade.LOW))
        }
    }

    @Test
    fun `exchangeSpiritStones - zero or negative returns false`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(100L, SpiritStoneGrade.LOW)
            assertFalse(system.exchangeSpiritStones(0L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID))
            assertFalse(system.exchangeSpiritStones(-1L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID))
        }
    }

    @Test
    fun `exchangeSpiritStones - high to mid and low preserves unrelated balances`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(2L, SpiritStoneGrade.HIGH)
            system.addSpiritStones(100L, SpiritStoneGrade.LOW)
            assertTrue(system.exchangeSpiritStones(1L, SpiritStoneGrade.HIGH, SpiritStoneGrade.MID))
        }
        // 1 HIGH → 8_000 MID (EFFECTIVE_RATIO)
        assertEquals(1L, system.getSpiritStones(SpiritStoneGrade.HIGH))
        assertEquals(8_000L, system.getSpiritStones(SpiritStoneGrade.MID))
        assertEquals(100L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    // region 自动补差价测试

    @Test
    fun `deductSpiritStones - auto sell mid covers shortfall`() = runBlocking {
        stateStore.update {
            // 只有中品灵石，开启自动补差价
            system.addSpiritStones(5L, SpiritStoneGrade.MID)
            gameData = gameData.copy(autoSellMidGradeForPurchase = true)
            // 需要 30_000 下品，5 中品 = 40_000 下品（×8000）
            // 需卖出 ceil(30_000/8_000)=4 中品 → 得 32_000 下品
            // 扣 30_000 后剩余 2_000 下品
            system.deductSpiritStones(30_000L, SpiritStoneGrade.LOW)
        }
        // 5 - 4 = 1 中品
        assertEquals(1L, system.getSpiritStones(SpiritStoneGrade.MID))
        // 32_000 - 30_000 = 2_000 下品
        assertEquals(2_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - auto sell mid not triggered when disabled`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(5L, SpiritStoneGrade.MID)
            // autoSellMidGradeForPurchase 默认 false
            system.deductSpiritStones(30_000L, SpiritStoneGrade.LOW)
        }
        // 中品未动，下品扣到 0
        assertEquals(5L, system.getSpiritStones(SpiritStoneGrade.MID))
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - auto sell high covers shortfall`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(2L, SpiritStoneGrade.HIGH)
            gameData = gameData.copy(autoSellHighGradeForPurchase = true)
            // 需要 100_000 下品，2 上品 = 2×64_000_000 = 128_000_000
            // 需卖出 ceil(100_000/64_000_000)=1 上品 → 得 64_000_000 下品
            // 扣 100_000 后剩余 63_900_000 下品
            system.deductSpiritStones(100_000L, SpiritStoneGrade.LOW)
        }
        assertEquals(1L, system.getSpiritStones(SpiritStoneGrade.HIGH))
        assertEquals(63_900_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - auto sell mid then high for large shortfall`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(1L, SpiritStoneGrade.MID)    // 8_000 下品
            system.addSpiritStones(1L, SpiritStoneGrade.HIGH)   // 64_000_000 下品
            gameData = gameData.copy(
                autoSellMidGradeForPurchase = true,
                autoSellHighGradeForPurchase = true
            )
            // 需要 10_000 下品：中品可提供 8_000 下品，还需 2_000
            // 从 1 上品中卖 → ceil(2_000/64_000_000)=1 上品 → 得 64_000_000
            // 总计：中品扣完（8_000）+ 上品扣完（64_000_000）- 10_000 = 63_998_000
            system.deductSpiritStones(10_000L, SpiritStoneGrade.LOW)
        }
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.MID))
        assertEquals(0L, system.getSpiritStones(SpiritStoneGrade.HIGH))
        // 8_000 + 64_000_000 - 10_000 = 63_998_000
        assertEquals(63_998_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - sufficient low grade skips auto sell`() = runBlocking {
        stateStore.update {
            system.addSpiritStones(50_000L, SpiritStoneGrade.LOW)
            system.addSpiritStones(5L, SpiritStoneGrade.MID)
            gameData = gameData.copy(autoSellMidGradeForPurchase = true)
            // 有足够下品，不触发自动售卖
            system.deductSpiritStones(30_000L, SpiritStoneGrade.LOW)
        }
        // 中品未被售卖
        assertEquals(5L, system.getSpiritStones(SpiritStoneGrade.MID))
        assertEquals(20_000L, system.getSpiritStones(SpiritStoneGrade.LOW))
    }

    // endregion
}
