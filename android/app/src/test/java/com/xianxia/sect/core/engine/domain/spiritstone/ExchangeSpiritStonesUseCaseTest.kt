package com.xianxia.sect.core.engine.domain.spiritstone

import com.xianxia.sect.core.domain.spiritstone.ExchangeSpiritStonesUseCase
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.SpiritStoneGrade
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever

class ExchangeSpiritStonesUseCaseTest {

    @Mock
    private lateinit var inventorySystem: InventorySystem

    private lateinit var useCase: ExchangeSpiritStonesUseCase

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        useCase = ExchangeSpiritStonesUseCase(inventorySystem)
    }

    @Test
    fun `invoke - successful low to mid exchange`() = runBlocking {
        whenever(inventorySystem.getSpiritStones(SpiritStoneGrade.LOW)).thenReturn(25_000L)
        // 25_000 / 8_000 = 3 MID
        whenever(inventorySystem.getSpiritStones(SpiritStoneGrade.MID)).thenReturn(0L, 3L)
        whenever(inventorySystem.exchangeSpiritStones(25_000L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID))
            .thenReturn(true)

        val result = useCase(25_000L, SpiritStoneGrade.LOW, SpiritStoneGrade.MID)

        assertTrue(result is ExchangeSpiritStonesUseCase.Result.Success)
        val success = result as ExchangeSpiritStonesUseCase.Result.Success
        assertEquals(3L, success.converted)
    }

    @Test
    fun `invoke - successful mid to high exchange`() = runBlocking {
        whenever(inventorySystem.getSpiritStones(SpiritStoneGrade.MID)).thenReturn(16_000L)
        // 16_000 / 8_000 = 2 HIGH
        whenever(inventorySystem.getSpiritStones(SpiritStoneGrade.HIGH)).thenReturn(0L, 2L)
        whenever(inventorySystem.exchangeSpiritStones(16_000L, SpiritStoneGrade.MID, SpiritStoneGrade.HIGH))
            .thenReturn(true)

        val result = useCase(16_000L, SpiritStoneGrade.MID, SpiritStoneGrade.HIGH)

        assertTrue(result is ExchangeSpiritStonesUseCase.Result.Success)
        val success = result as ExchangeSpiritStonesUseCase.Result.Success
        assertEquals(2L, success.converted)
    }

    @Test
    fun `invoke - successful high to mid exchange`() = runBlocking {
        whenever(inventorySystem.getSpiritStones(SpiritStoneGrade.HIGH)).thenReturn(2L)
        // 2 HIGH → 2 * 8_000 = 16_000 MID
        whenever(inventorySystem.getSpiritStones(SpiritStoneGrade.MID)).thenReturn(0L, 16_000L)
        whenever(inventorySystem.exchangeSpiritStones(2L, SpiritStoneGrade.HIGH, SpiritStoneGrade.MID))
            .thenReturn(true)

        val result = useCase(2L, SpiritStoneGrade.HIGH, SpiritStoneGrade.MID)

        assertTrue(result is ExchangeSpiritStonesUseCase.Result.Success)
        val success = result as ExchangeSpiritStonesUseCase.Result.Success
        assertEquals(16_000L, success.converted)
    }

    @Test
    fun `invoke - insufficient balance returns Insufficient`() = runBlocking {
        whenever(inventorySystem.getSpiritStones(SpiritStoneGrade.LOW)).thenReturn(100L)

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

    @Test
    fun `invoke - exchange failure returns Invalid`() = runBlocking {
        whenever(inventorySystem.getSpiritStones(SpiritStoneGrade.MID)).thenReturn(1L)
        whenever(inventorySystem.exchangeSpiritStones(1L, SpiritStoneGrade.MID, SpiritStoneGrade.HIGH))
            .thenReturn(false)

        val result = useCase(1L, SpiritStoneGrade.MID, SpiritStoneGrade.HIGH)
        assertTrue(result is ExchangeSpiritStonesUseCase.Result.Invalid)
    }
}
