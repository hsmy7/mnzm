package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * SpiritStoneWallet 灵石操作测试（替代旧的 InventorySystem 直调测试，
 * 灵石操作已全部迁移至 SpiritStoneWallet）。
 */
class InventorySystemSpiritStoneTest {

    private lateinit var stateStore: GameStateStoreImpl
    private lateinit var wallet: SpiritStoneWallet

    @Before
    fun setUp() {
        stateStore = GameStateStoreImpl(
            ApplicationScopeProvider(),
            mock(GameStateRepository::class.java)
        )
        stateStore.unsafeAllowMainThreadUpdateForTest = true
        InventoryConfig()
        wallet = SpiritStoneWallet(
            stateStore, SpiritStoneLedger(), mock(EventBus::class.java)
        )
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

    // ═══ Wallet add ═══

    @Test
    fun `addSpiritStones - increases low grade stones`() = runBlocking {
        stateStore.update { wallet.add(this, 1_000L, SpiritStoneGrade.LOW) }
        assertEquals(1_000L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `addSpiritStones - increases mid and high grade stones`() = runBlocking {
        stateStore.update {
            wallet.add(this, 5L, SpiritStoneGrade.MID)
            wallet.add(this, 2L, SpiritStoneGrade.HIGH)
        }
        assertEquals(5L, wallet.balance(SpiritStoneGrade.MID))
        assertEquals(2L, wallet.balance(SpiritStoneGrade.HIGH))
        assertEquals(0L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `addSpiritStones - non positive amount no-ops`() = runBlocking {
        stateStore.update {
            assertEquals(0L, wallet.add(this, 0L, SpiritStoneGrade.LOW))
            assertEquals(0L, wallet.add(this, -1L, SpiritStoneGrade.MID))
        }
    }

    // ═══ Wallet deduct ═══

    @Test
    fun `deductSpiritStones - decreases stones`() = runBlocking {
        stateStore.update {
            wallet.add(this, 1_000L, SpiritStoneGrade.LOW)
            wallet.deduct(this, 300L, SpiritStoneGrade.LOW)
        }
        assertEquals(700L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - insufficient leaves balance unchanged`() = runBlocking {
        stateStore.update { wallet.add(this, 100L, SpiritStoneGrade.LOW) }
        val result = stateStore.updateAndReturn {
            wallet.deduct(this, 500L, SpiritStoneGrade.LOW)
        }
        assertTrue("Should be Insufficient, got $result", result is com.xianxia.sect.core.wallet.DeductResult.Insufficient)
        assertEquals(100L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - non positive amount returns Invalid`() = runBlocking {
        stateStore.update { wallet.add(this, 100L, SpiritStoneGrade.LOW) }
        val result0 = stateStore.updateAndReturn {
            wallet.deduct(this, 0L, SpiritStoneGrade.LOW)
        }
        assertTrue("Should be Invalid, got $result0", result0 is com.xianxia.sect.core.wallet.DeductResult.Invalid)
    }

    // ═══ canAfford ═══

    @Test
    fun `canAfford - reflects current balance`() = runBlocking {
        stateStore.update { wallet.add(this, 1_000L, SpiritStoneGrade.LOW) }
        assertTrue(wallet.canAfford(1_000L, SpiritStoneGrade.LOW))
        assertFalse(wallet.canAfford(1_001L, SpiritStoneGrade.LOW))
    }

    // ═══ auto-sell on deduct ═══

    @Test
    fun `deductSpiritStones - auto sell mid covers shortfall`() = runBlocking {
        stateStore.update {
            wallet.add(this, 5L, SpiritStoneGrade.MID)
            gameData = gameData.copy(autoSellMidGradeForPurchase = true)
        }
        stateStore.update { wallet.deduct(this, 30_000L, SpiritStoneGrade.LOW) }
        assertEquals(1L, wallet.balance(SpiritStoneGrade.MID))
        assertEquals(2_000L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - auto sell mid not triggered when disabled`() = runBlocking {
        stateStore.update { wallet.add(this, 5L, SpiritStoneGrade.MID) }
        stateStore.update { wallet.deduct(this, 30_000L, SpiritStoneGrade.LOW) }
        assertEquals(5L, wallet.balance(SpiritStoneGrade.MID))
        assertEquals(0L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - auto sell high covers shortfall`() = runBlocking {
        stateStore.update {
            wallet.add(this, 2L, SpiritStoneGrade.HIGH)
            gameData = gameData.copy(autoSellHighGradeForPurchase = true)
        }
        stateStore.update { wallet.deduct(this, 100_000L, SpiritStoneGrade.LOW) }
        assertEquals(1L, wallet.balance(SpiritStoneGrade.HIGH))
        assertEquals(63_900_000L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - auto sell mid then high for large shortfall`() = runBlocking {
        stateStore.update {
            wallet.add(this, 1L, SpiritStoneGrade.MID)
            wallet.add(this, 1L, SpiritStoneGrade.HIGH)
            gameData = gameData.copy(
                autoSellMidGradeForPurchase = true,
                autoSellHighGradeForPurchase = true
            )
        }
        stateStore.update { wallet.deduct(this, 10_000L, SpiritStoneGrade.LOW) }
        assertEquals(0L, wallet.balance(SpiritStoneGrade.MID))
        assertEquals(0L, wallet.balance(SpiritStoneGrade.HIGH))
        assertEquals(63_998_000L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deductSpiritStones - sufficient low grade skips auto sell`() = runBlocking {
        stateStore.update {
            wallet.add(this, 50_000L, SpiritStoneGrade.LOW)
            wallet.add(this, 5L, SpiritStoneGrade.MID)
            gameData = gameData.copy(autoSellMidGradeForPurchase = true)
        }
        stateStore.update { wallet.deduct(this, 30_000L, SpiritStoneGrade.LOW) }
        assertEquals(20_000L, wallet.balance(SpiritStoneGrade.LOW))
        assertEquals(5L, wallet.balance(SpiritStoneGrade.MID))
        assertEquals(0L, wallet.balance(SpiritStoneGrade.HIGH))
    }
}
