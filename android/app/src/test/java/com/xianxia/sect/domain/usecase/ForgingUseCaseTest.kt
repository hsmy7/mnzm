package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.SlotStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

class ForgingUseCaseTest {

    @Mock
    private lateinit var gameEngine: GameEngine

    private lateinit var useCase: ForgingUseCase

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        useCase = ForgingUseCase(gameEngine)
    }

    private fun idleSlot(index: Int = 0) = BuildingSlot(buildingId = "forge", slotIndex = index, status = SlotStatus.IDLE)

    private fun workingSlot(index: Int = 0) = BuildingSlot(buildingId = "forge", slotIndex = index, status = SlotStatus.WORKING)

    private fun recipe(id: String = "r1") = ForgeRecipe(
        id = id, name = "配方$id", equipmentId = "e$id", equipmentName = "装备$id",
        equipmentRarity = 1, tier = 1, equipmentSlot = EquipmentSlot.WEAPON,
        description = "desc", materials = emptyMap(), duration = 3, successRate = 0.8
    )

    @Test
    fun `startForging - slotIndex 小于0返回无效槽位错误`() = runBlocking {
        whenever(gameEngine.getBuildingSlots("forge")).thenReturn(listOf(idleSlot()))
        val result = useCase.startForging(ForgingUseCase.StartForgingParams(-1, recipe(), null))
        assertTrue(result is ForgingUseCase.ForgingResult.Error)
    }

    @Test
    fun `startForging - slotIndex 越界返回无效槽位错误`() = runBlocking {
        whenever(gameEngine.getBuildingSlots("forge")).thenReturn(listOf(idleSlot()))
        val result = useCase.startForging(ForgingUseCase.StartForgingParams(99, recipe(), null))
        assertTrue(result is ForgingUseCase.ForgingResult.Error)
    }

    @Test
    fun `startForging - 槽位非IDLE返回正在使用中错误`() = runBlocking {
        whenever(gameEngine.getBuildingSlots("forge")).thenReturn(listOf(workingSlot()))
        val result = useCase.startForging(ForgingUseCase.StartForgingParams(0, recipe(), null))
        assertTrue(result is ForgingUseCase.ForgingResult.Error)
        assertEquals("该槽位正在使用中", (result as ForgingUseCase.ForgingResult.Error).message)
    }

    @Test
    fun `startForging - 成功启动返回 Success`() = runBlocking {
        whenever(gameEngine.getBuildingSlots("forge")).thenReturn(listOf(idleSlot()))
        val result = useCase.startForging(ForgingUseCase.StartForgingParams(0, recipe(), "d1"))
        assertTrue(result is ForgingUseCase.ForgingResult.Success)
        assertEquals(0, (result as ForgingUseCase.ForgingResult.Success).slotIndex)
    }

    @Test
    fun `collectForgeResult - 自动收取无需手动操作`() {
        val result = useCase.collectForgeResult(0)
        assertFalse(result.success)
        assertEquals("锻造产物自动收取，无需手动操作", result.message)
    }
}
