package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.AlchemySlot
import com.xianxia.sect.core.model.AlchemySlotStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

class AlchemyUseCaseTest {

    @Mock
    private lateinit var gameEngine: GameEngine

    private lateinit var useCase: AlchemyUseCase

    private val alchemySlotsFlow = MutableStateFlow<List<AlchemySlot>>(emptyList())

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Mockito.doReturn(alchemySlotsFlow).`when`(gameEngine).alchemySlots
        useCase = AlchemyUseCase(gameEngine)
    }

    private fun idleSlot(index: Int = 0) = AlchemySlot(slotIndex = index, status = AlchemySlotStatus.IDLE)

    private fun workingSlot(index: Int = 0) = AlchemySlot(slotIndex = index, status = AlchemySlotStatus.WORKING)

    @Test
    fun `startAlchemy - slotIndex 小于0返回无效槽位错误`() = runBlocking {
        alchemySlotsFlow.value = listOf(idleSlot())
        val result = useCase.startAlchemy(AlchemyUseCase.StartAlchemyParams(-1, "r1", null))
        assertTrue(result is AlchemyUseCase.AlchemyResult.Error)
        assertEquals("无效的炼丹槽位", (result as AlchemyUseCase.AlchemyResult.Error).message)
    }

    @Test
    fun `startAlchemy - slotIndex 越界返回无效槽位错误`() = runBlocking {
        alchemySlotsFlow.value = listOf(idleSlot())
        val result = useCase.startAlchemy(AlchemyUseCase.StartAlchemyParams(99, "r1", null))
        assertTrue(result is AlchemyUseCase.AlchemyResult.Error)
    }

    @Test
    fun `startAlchemy - 槽位非IDLE返回正在使用中错误`() = runBlocking {
        alchemySlotsFlow.value = listOf(workingSlot())
        val result = useCase.startAlchemy(AlchemyUseCase.StartAlchemyParams(0, "r1", null))
        assertTrue(result is AlchemyUseCase.AlchemyResult.Error)
        assertEquals("该槽位正在使用中", (result as AlchemyUseCase.AlchemyResult.Error).message)
    }

    @Test
    fun `startAlchemy - 成功启动`() = runBlocking {
        alchemySlotsFlow.value = listOf(idleSlot())
        Mockito.`when`(gameEngine.startAlchemy(0, "r1")).thenReturn(true)
        val result = useCase.startAlchemy(AlchemyUseCase.StartAlchemyParams(0, "r1", "d1"))
        assertTrue(result is AlchemyUseCase.AlchemyResult.Success)
        assertEquals(0, (result as AlchemyUseCase.AlchemyResult.Success).slotIndex)
    }

    @Test
    fun `collectAlchemyResult - 自动收取无需手动操作`() {
        val result = useCase.collectAlchemyResult(0, 2024, 6)
        assertFalse(result.success)
        assertEquals("炼丹产物自动收取，无需手动操作", result.message)
    }
}
