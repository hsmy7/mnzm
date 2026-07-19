package com.xianxia.sect.core.engine.domain.spiritstone

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.domain.spiritstone.ExchangeSpiritStonesUseCase
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class ExchangeSpiritStonesUseCaseTest {

    private lateinit var useCase: ExchangeSpiritStonesUseCase
    private lateinit var stateStore: GameStateStoreImpl
    private lateinit var wallet: SpiritStoneWallet

    @Before
    fun setUp() {
        stateStore = GameStateStoreImpl(
            ApplicationScopeProvider(),
            mock(GameStateRepository::class.java)
        )
        wallet = SpiritStoneWallet(
            stateStore, SpiritStoneLedger(), mock(EventBus::class.java)
        )
        InventoryConfig()
        useCase = ExchangeSpiritStonesUseCase(stateStore, wallet)
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

    @Test
    fun `invoke - successful low to mid exchange`() = runBlocking {
        stateStore.update { wallet.add(this, 25_000L, SpiritStoneGrade.LOW) }
        // 25_000 / 8_000 = 3 MID (余 1_000 LOW)

        val result = useCase(25_000L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID)

        assertTrue("Result should be Success, got $result", result is ExchangeSpiritStonesUseCase.Result.Success)
        val success = result as ExchangeSpiritStonesUseCase.Result.Success
        assertEquals(3L, success.converted)
    }

    @Test
    fun `invoke - successful mid to high exchange`() = runBlocking {
        stateStore.update { wallet.add(this, 16_000L, SpiritStoneGrade.MID) }
        // 16_000 / 8_000 = 2 HIGH

        val result = useCase(16_000L, SpiritStoneGrade.MID, SpiritStoneGrade.HIGH)

        assertTrue("Result should be Success, got $result", result is ExchangeSpiritStonesUseCase.Result.Success)
        val success = result as ExchangeSpiritStonesUseCase.Result.Success
        assertEquals(2L, success.converted)
    }

    @Test
    fun `invoke - successful high to mid exchange`() = runBlocking {
        stateStore.update { wallet.add(this, 2L, SpiritStoneGrade.HIGH) }
        // 2 HIGH → 2 * 8_000 = 16_000 MID

        val result = useCase(2L, SpiritStoneGrade.HIGH, SpiritStoneGrade.MID)

        assertTrue("Result should be Success, got $result", result is ExchangeSpiritStonesUseCase.Result.Success)
        val success = result as ExchangeSpiritStonesUseCase.Result.Success
        assertEquals(16_000L, success.converted)
    }

    @Test
    fun `invoke - insufficient balance returns Insufficient`() = runBlocking {
        stateStore.update { wallet.add(this, 100L, SpiritStoneGrade.LOW) }

        val result = useCase(10_000L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID)

        assertTrue(result is ExchangeSpiritStonesUseCase.Result.Insufficient)
        val insufficient = result as ExchangeSpiritStonesUseCase.Result.Insufficient
        assertEquals(10_000L, insufficient.required)
        assertEquals(100L, insufficient.owned)
    }

    @Test
    fun `invoke - zero quantity returns Invalid`() = runBlocking {
        val result = useCase(0L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID)
        assertTrue(result is ExchangeSpiritStonesUseCase.Result.Invalid)
    }

    @Test
    fun `invoke - negative quantity returns Invalid`() = runBlocking {
        val result = useCase(-1L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID)
        assertTrue(result is ExchangeSpiritStonesUseCase.Result.Invalid)
    }

    @Test
    fun `invoke - same grade returns Invalid`() = runBlocking {
        val result = useCase(100L, SpiritStoneGrade.LOW, SpiritStoneGrade.LOW)
        assertTrue(result is ExchangeSpiritStonesUseCase.Result.Invalid)
    }
}
