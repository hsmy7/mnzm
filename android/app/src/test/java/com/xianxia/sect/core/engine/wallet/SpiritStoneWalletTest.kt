package com.xianxia.sect.core.engine.wallet

import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.SpiritStoneExchange
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.core.wallet.BatchResult
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneOperation
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpiritStoneWalletTest {

    private lateinit var stateStore: GameStateStore
    private lateinit var ledger: SpiritStoneLedger
    private lateinit var wallet: SpiritStoneWallet

    @Before
    fun setUp() {
        stateStore = GameStateStoreImpl(
            ApplicationScopeProvider(),
            mock(GameStateRepository::class.java)
        )
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = true
        ledger = SpiritStoneLedger()
        wallet = SpiritStoneWallet(stateStore, ledger, mock(EventBus::class.java))
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
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = false
        runBlocking {
            delay(100)
            stateStore.reset()
        }
    }

    // ═══ add ═══

    @Test
    fun `add - zero amount returns current balance`() = runBlocking {
        stateStore.update { wallet.add(this, 100, SpiritStoneGrade.LOW) }
        val balance = stateStore.updateAndReturn { wallet.add(this, 0, SpiritStoneGrade.LOW) }
        assertEquals(100L, balance)
        assertEquals(100L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `add - negative amount returns current balance`() = runBlocking {
        stateStore.update { wallet.add(this, 100, SpiritStoneGrade.LOW) }
        val balance = stateStore.updateAndReturn { wallet.add(this, -1, SpiritStoneGrade.LOW) }
        assertEquals(100L, balance)
        assertEquals(100L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `add - overflow clamps to Long MAX_VALUE`() = runBlocking {
        stateStore.update { gameData = gameData.copy(spiritStones = Long.MAX_VALUE - 50) }
        stateStore.updateAndReturn { wallet.add(this, 100, SpiritStoneGrade.LOW) }
        assertEquals(Long.MAX_VALUE, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `add - mid and high grades work independently`() = runBlocking {
        stateStore.updateAndReturn { wallet.add(this, 5, SpiritStoneGrade.MID) }
        stateStore.updateAndReturn { wallet.add(this, 2, SpiritStoneGrade.HIGH) }
        assertEquals(5L, wallet.balance(SpiritStoneGrade.MID))
        assertEquals(2L, wallet.balance(SpiritStoneGrade.HIGH))
        assertEquals(0L, wallet.balance(SpiritStoneGrade.LOW))
    }

    // ═══ deduct ═══

    @Test
    fun `deduct - zero amount returns Invalid`() = runBlocking {
        val result = stateStore.updateAndReturn { wallet.deduct(this, 0, SpiritStoneGrade.LOW) }
        assertTrue("Expected Invalid but got $result", result is DeductResult.Invalid)
    }

    @Test
    fun `deduct - more than balance returns Insufficient`() = runBlocking {
        stateStore.update { wallet.add(this, 100, SpiritStoneGrade.LOW) }
        val result = stateStore.updateAndReturn { wallet.deduct(this, 500, SpiritStoneGrade.LOW) }
        assertTrue("Expected Insufficient but got $result", result is DeductResult.Insufficient)
        assertEquals(100L, (result as DeductResult.Insufficient).balance)
        assertEquals(500L, result.required)
        assertEquals(100L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deduct - exact balance returns Success of zero`() = runBlocking {
        stateStore.update { wallet.add(this, 100, SpiritStoneGrade.LOW) }
        val result = stateStore.updateAndReturn { wallet.deduct(this, 100, SpiritStoneGrade.LOW) }
        assertTrue("Expected Success but got $result", result is DeductResult.Success)
        assertEquals(0L, (result as DeductResult.Success).balanceAfter)
        assertEquals(0L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deduct - with auto sell mid grade covers shortfall`() = runBlocking {
        stateStore.update { wallet.add(this, 100, SpiritStoneGrade.LOW) }
        stateStore.update { wallet.add(this, 1, SpiritStoneGrade.MID) }
        stateStore.update { gameData = gameData.copy(autoSellMidGradeForPurchase = true) }
        val result = stateStore.updateAndReturn { wallet.deduct(this, 8100, SpiritStoneGrade.LOW) }
        assertTrue("Expected Success but got $result", result is DeductResult.Success)
        assertEquals(0L, wallet.balance(SpiritStoneGrade.MID))
        assertEquals(0L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deduct - with auto sell mid still insufficient`() = runBlocking {
        stateStore.update { wallet.add(this, 100, SpiritStoneGrade.LOW) }
        stateStore.update { wallet.add(this, 1, SpiritStoneGrade.MID) }
        stateStore.update { gameData = gameData.copy(autoSellMidGradeForPurchase = true) }
        val result = stateStore.updateAndReturn { wallet.deduct(this, 9000, SpiritStoneGrade.LOW) }
        assertTrue("Expected Insufficient but got $result", result is DeductResult.Insufficient)
        val insufficient = result as DeductResult.Insufficient
        // calculateAutoSell 先检查 → 8100 < 9000 → 不执行售卖，余额不变
        assertEquals(100L, insufficient.balance)
        assertEquals(9000L, insufficient.required)
        assertEquals(1L, wallet.balance(SpiritStoneGrade.MID)) // MID 未被消耗
        assertEquals(100L, wallet.balance(SpiritStoneGrade.LOW))
    }

    @Test
    fun `deduct - auto sell disabled when autoConvert is false`() = runBlocking {
        stateStore.update { wallet.add(this, 100, SpiritStoneGrade.LOW) }
        stateStore.update { wallet.add(this, 1, SpiritStoneGrade.MID) }
        stateStore.update { gameData = gameData.copy(autoSellMidGradeForPurchase = true) }
        // autoConvert=false bypasses auto-sell logic
        val result = stateStore.updateAndReturn { wallet.deduct(this, 8100, SpiritStoneGrade.LOW,
            SpiritStoneReason.Internal, SpiritStoneSource.Internal, false) }
        assertTrue("Expected Insufficient but got $result", result is DeductResult.Insufficient)
        // MID was not consumed because autoConvert=false prevents autoSellHigherGrades
        assertEquals(1L, wallet.balance(SpiritStoneGrade.MID))
        assertEquals(100L, wallet.balance(SpiritStoneGrade.LOW))
    }

    // ═══ batch ═══

    @Test
    fun `batch - empty operations returns zero counts`() = runBlocking {
        val result = stateStore.updateAndReturn { wallet.batch(this, emptyList()) }
        assertEquals(0, result.successCount)
        assertEquals(0, result.failedCount)
        assertTrue(result.results.isEmpty())
    }

    @Test
    fun `batch - all or nothing when any deduct fails precheck`() = runBlocking {
        stateStore.update { wallet.add(this, 500, SpiritStoneGrade.LOW) }
        val ops = listOf(
            SpiritStoneOperation(delta = 500, grade = SpiritStoneGrade.LOW, reason = SpiritStoneReason.Internal, source = SpiritStoneSource.Internal),
            SpiritStoneOperation(delta = -2000, grade = SpiritStoneGrade.LOW, reason = SpiritStoneReason.Internal, source = SpiritStoneSource.Internal)
        )
        val result = stateStore.updateAndReturn { wallet.batch(this, ops) }
        // 预检查拒绝整个 batch（-2000 超过余额），无操作执行
        assertEquals(0, result.successCount)
        assertEquals(2, result.failedCount)
        assertEquals(500L, wallet.balance(SpiritStoneGrade.LOW)) // 余额不变
    }

    @Test
    fun `batch - all operations succeed when initial balance covers deducts`() = runBlocking {
        stateStore.update { wallet.add(this, 2000, SpiritStoneGrade.LOW) }
        val ops = listOf(
            SpiritStoneOperation(delta = 500, grade = SpiritStoneGrade.LOW, reason = SpiritStoneReason.Internal, source = SpiritStoneSource.Internal),
            SpiritStoneOperation(delta = -800, grade = SpiritStoneGrade.LOW, reason = SpiritStoneReason.Internal, source = SpiritStoneSource.Internal)
        )
        val result = stateStore.updateAndReturn { wallet.batch(this, ops) }
        assertEquals(2, result.successCount)
        assertEquals(0, result.failedCount)
        assertEquals(1700L, wallet.balance(SpiritStoneGrade.LOW)) // 2000 + 500 - 800 = 1700
    }

    // ═══ canAfford ═══

    @Test
    fun `canAfford - large numbers correctly evaluated`() = runBlocking {
        stateStore.update { wallet.add(this, 1000, SpiritStoneGrade.LOW) }
        assertTrue(wallet.canAfford(1000))
        assertFalse(wallet.canAfford(1001))
    }

    @Test
    fun `canAfford - zero and negative amounts`() = runBlocking {
        stateStore.update { wallet.add(this, 100, SpiritStoneGrade.LOW) }
        assertTrue(wallet.canAfford(0))
        assertTrue(wallet.canAfford(-1))
    }

    // ═══ balance ═══

    @Test
    fun `balance - totalSellValue reflects all grades`() = runBlocking {
        stateStore.update { wallet.add(this, 1000, SpiritStoneGrade.LOW) }
        stateStore.update { wallet.add(this, 2, SpiritStoneGrade.MID) }
        val expected = 1000L + 2L * SpiritStoneExchange.EFFECTIVE_RATIO
        assertEquals(expected, wallet.totalSellValue())
    }
}
