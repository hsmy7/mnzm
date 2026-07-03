package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.domain.save.SaveFacade
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * 测试所有 14 个 UseCase 的 invoke/方法调用逻辑。
 *
 * 验证：
 * - 正确的委托调用（verify facade method called）
 * - 成功的返回路径
 * - 失败的返回路径
 * - 边界情况（空列表、默认参数等）
 */
class UseCaseInvocationTest {

    // ==================== 1. AssignDiscipleWorkUseCase ====================

    @Test
    fun `AssignDiscipleWorkUseCase - success path`() = runTest {
        val discipleFacade = mock<DiscipleFacade>()
        val useCase = AssignDiscipleWorkUseCase(discipleFacade)

        val result = useCase("disciple-1", DiscipleStatus.IDLE)

        assertTrue(result.isSuccess)
        verify(discipleFacade).updateDiscipleStatus("disciple-1", DiscipleStatus.IDLE)
    }

    @Test
    fun `AssignDiscipleWorkUseCase - failure when facade throws`() = runTest {
        val discipleFacade = mock<DiscipleFacade>()
        whenever(discipleFacade.updateDiscipleStatus("disciple-1", DiscipleStatus.IDLE))
            .thenThrow(RuntimeException("更新失败"))
        val useCase = AssignDiscipleWorkUseCase(discipleFacade)

        val result = useCase("disciple-1", DiscipleStatus.IDLE)

        assertTrue(result.isFailure)
    }

    @Test
    fun `AssignDiscipleWorkUseCase - success with non-default status`() = runTest {
        val discipleFacade = mock<DiscipleFacade>()
        val useCase = AssignDiscipleWorkUseCase(discipleFacade)

        val result = useCase("disciple-2", DiscipleStatus.MINING)

        assertTrue(result.isSuccess)
        verify(discipleFacade).updateDiscipleStatus("disciple-2", DiscipleStatus.MINING)
    }

    // ==================== 2. EquipItemUseCase ====================

    @Test
    fun `EquipItemUseCase - success path`() = runTest {
        val discipleFacade = mock<DiscipleFacade>()
        whenever(discipleFacade.equipEquipment("disciple-1", "equip-1"))
            .thenReturn(DomainResult.Success(Unit))
        val useCase = EquipItemUseCase(discipleFacade)

        val result = useCase("disciple-1", "equip-1")

        assertTrue(result.isSuccess)
        assertEquals(DomainResult.Success(Unit), result.getOrNull())
        verify(discipleFacade).equipEquipment("disciple-1", "equip-1")
    }

    @Test
    fun `EquipItemUseCase - failure when facade throws`() = runTest {
        val discipleFacade = mock<DiscipleFacade>()
        whenever(discipleFacade.equipEquipment("disciple-1", "equip-1"))
            .thenThrow(RuntimeException("装备失败"))
        val useCase = EquipItemUseCase(discipleFacade)

        val result = useCase("disciple-1", "equip-1")

        assertTrue(result.isFailure)
    }

    // ==================== 3. ExpelDiscipleUseCase ====================

    @Test
    fun `ExpelDiscipleUseCase - success path`() = runTest {
        val discipleFacade = mock<DiscipleFacade>()
        whenever(discipleFacade.expelDisciple("disciple-1"))
            .thenReturn(DomainResult.Success(Unit))
        val useCase = ExpelDiscipleUseCase(discipleFacade)

        val result = useCase("disciple-1")

        assertTrue(result.isSuccess)
        assertEquals(DomainResult.Success(Unit), result.getOrNull())
        verify(discipleFacade).expelDisciple("disciple-1")
    }

    @Test
    fun `ExpelDiscipleUseCase - failure when facade throws`() = runTest {
        val discipleFacade = mock<DiscipleFacade>()
        whenever(discipleFacade.expelDisciple("disciple-1"))
            .thenThrow(RuntimeException("驱逐失败"))
        val useCase = ExpelDiscipleUseCase(discipleFacade)

        val result = useCase("disciple-1")

        assertTrue(result.isFailure)
    }

    // ==================== 4. GetBattleLogsUseCase ====================

    @Test
    fun `GetBattleLogsUseCase - returns same StateFlow instance from facade`() {
        val battleFacade = mock<BattleFacade>()
        val flow = MutableStateFlow<List<BattleLog>>(emptyList())
        whenever(battleFacade.battleLogs).thenReturn(flow)
        val useCase = GetBattleLogsUseCase(battleFacade)

        val result = useCase()

        assertSame(flow, result)
        assertTrue(result.value.isEmpty())
    }

    @Test
    fun `GetBattleLogsUseCase - returns flow with battle logs`() {
        val battleFacade = mock<BattleFacade>()
        val logs = listOf(mock<BattleLog>(), mock<BattleLog>())
        val flow = MutableStateFlow(logs)
        whenever(battleFacade.battleLogs).thenReturn(flow)
        val useCase = GetBattleLogsUseCase(battleFacade)

        val result = useCase()

        assertEquals(2, result.value.size)
    }

    // ==================== 5. GetBattleStatsUseCase ====================

    @Test
    fun `GetBattleStatsUseCase - success with default parameter`() {
        val battleFacade = mock<BattleFacade>()
        whenever(battleFacade.getTotalBattlesCount()).thenReturn(100)
        whenever(battleFacade.getWinRate(50)).thenReturn(0.75)
        val useCase = GetBattleStatsUseCase(battleFacade)

        val stats = useCase()

        assertEquals(100, stats.totalBattles)
        assertEquals(0.75, stats.winRate, 0.001)
        verify(battleFacade).getTotalBattlesCount()
        verify(battleFacade).getWinRate(50)
    }

    @Test
    fun `GetBattleStatsUseCase - success with custom lastNBattles`() {
        val battleFacade = mock<BattleFacade>()
        whenever(battleFacade.getTotalBattlesCount()).thenReturn(200)
        whenever(battleFacade.getWinRate(20)).thenReturn(0.5)
        val useCase = GetBattleStatsUseCase(battleFacade)

        val stats = useCase(lastNBattles = 20)

        assertEquals(200, stats.totalBattles)
        assertEquals(0.5, stats.winRate, 0.001)
        verify(battleFacade).getWinRate(20)
    }

    @Test
    fun `GetBattleStatsUseCase - zero battles returns zero win rate`() {
        val battleFacade = mock<BattleFacade>()
        whenever(battleFacade.getTotalBattlesCount()).thenReturn(0)
        whenever(battleFacade.getWinRate(50)).thenReturn(0.0)
        val useCase = GetBattleStatsUseCase(battleFacade)

        val stats = useCase()

        assertEquals(0, stats.totalBattles)
        assertEquals(0.0, stats.winRate, 0.001)
    }

    @Test
    fun `GetBattleStatsUseCase - perfect win rate`() {
        val battleFacade = mock<BattleFacade>()
        whenever(battleFacade.getTotalBattlesCount()).thenReturn(50)
        whenever(battleFacade.getWinRate(50)).thenReturn(1.0)
        val useCase = GetBattleStatsUseCase(battleFacade)

        val stats = useCase()

        assertEquals(50, stats.totalBattles)
        assertEquals(1.0, stats.winRate, 0.001)
    }

    // ==================== 6. GetDisciplesUseCase ====================

    @Test
    fun `GetDisciplesUseCase - returns same StateFlow instance from facade`() {
        val discipleFacade = mock<DiscipleFacade>()
        val flow = MutableStateFlow<List<DiscipleAggregate>>(emptyList())
        whenever(discipleFacade.discipleAggregates).thenReturn(flow)
        val useCase = GetDisciplesUseCase(discipleFacade)

        val result = useCase()

        assertSame(flow, result)
        assertTrue(result.value.isEmpty())
    }

    @Test
    fun `GetDisciplesUseCase - returns flow with disciple aggregates`() {
        val discipleFacade = mock<DiscipleFacade>()
        val aggregates = listOf(mock<DiscipleAggregate>(), mock<DiscipleAggregate>())
        val flow = MutableStateFlow(aggregates)
        whenever(discipleFacade.discipleAggregates).thenReturn(flow)
        val useCase = GetDisciplesUseCase(discipleFacade)

        val result = useCase()

        assertEquals(2, result.value.size)
    }

    // ==================== 7. GetSectTradeItemsUseCase ====================

    @Test
    fun `GetSectTradeItemsUseCase - success path`() {
        val diplomacyFacade = mock<DiplomacyFacade>()
        val items = listOf(mock<MerchantItem>(), mock<MerchantItem>())
        whenever(diplomacyFacade.getOrRefreshSectTradeItems("sect-1")).thenReturn(items)
        val useCase = GetSectTradeItemsUseCase(diplomacyFacade)

        val result = useCase("sect-1")

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()?.size)
        verify(diplomacyFacade).getOrRefreshSectTradeItems("sect-1")
    }

    @Test
    fun `GetSectTradeItemsUseCase - failure when facade throws`() {
        val diplomacyFacade = mock<DiplomacyFacade>()
        whenever(diplomacyFacade.getOrRefreshSectTradeItems("sect-1"))
            .thenThrow(RuntimeException("加载失败"))
        val useCase = GetSectTradeItemsUseCase(diplomacyFacade)

        val result = useCase("sect-1")

        assertTrue(result.isFailure)
    }

    @Test
    fun `GetSectTradeItemsUseCase - returns empty list when no items`() {
        val diplomacyFacade = mock<DiplomacyFacade>()
        whenever(diplomacyFacade.getOrRefreshSectTradeItems("sect-new")).thenReturn(emptyList())
        val useCase = GetSectTradeItemsUseCase(diplomacyFacade)

        val result = useCase("sect-new")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull().orEmpty().isEmpty())
    }

    // ==================== 8. GetStateSnapshotUseCase ====================

    @Test
    fun `GetStateSnapshotUseCase - success path`() = runTest {
        val saveFacade = mock<SaveFacade>()
        val snapshot = mock<GameStateSnapshot>()
        whenever(saveFacade.getStateSnapshot()).thenReturn(snapshot)
        val useCase = GetStateSnapshotUseCase(saveFacade)

        val result = useCase()

        assertTrue(result.isSuccess)
        assertSame(snapshot, result.getOrNull())
        verify(saveFacade).getStateSnapshot()
    }

    @Test
    fun `GetStateSnapshotUseCase - failure when facade throws`() = runTest {
        val saveFacade = mock<SaveFacade>()
        whenever(saveFacade.getStateSnapshot()).thenThrow(RuntimeException("获取快照失败"))
        val useCase = GetStateSnapshotUseCase(saveFacade)

        val result = useCase()

        assertTrue(result.isFailure)
    }

    // ==================== 9. HarvestProductionUseCase ====================

    @Test
    fun `HarvestProductionUseCase - success path with results`() = runTest {
        val buildingFacade = mock<BuildingFacade>()
        val results = listOf(
            AlchemyResult(success = true, message = "炼丹成功"),
            AlchemyResult(success = false, message = "炼制失败")
        )
        whenever(buildingFacade.autoHarvestCompletedAlchemySlots()).thenReturn(results)
        val useCase = HarvestProductionUseCase(buildingFacade)

        val result = useCase()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()?.size)
        assertEquals("炼丹成功", result.getOrNull()?.get(0)?.message)
        verify(buildingFacade).autoHarvestCompletedAlchemySlots()
    }

    @Test
    fun `HarvestProductionUseCase - success path with empty list`() = runTest {
        val buildingFacade = mock<BuildingFacade>()
        whenever(buildingFacade.autoHarvestCompletedAlchemySlots()).thenReturn(emptyList())
        val useCase = HarvestProductionUseCase(buildingFacade)

        val result = useCase()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull().orEmpty().isEmpty())
    }

    @Test
    fun `HarvestProductionUseCase - failure when facade throws`() = runTest {
        val buildingFacade = mock<BuildingFacade>()
        whenever(buildingFacade.autoHarvestCompletedAlchemySlots())
            .thenThrow(RuntimeException("收获失败"))
        val useCase = HarvestProductionUseCase(buildingFacade)

        val result = useCase()

        assertTrue(result.isFailure)
    }

    // ==================== 10. ManageAllianceUseCase ====================

    @Test
    fun `ManageAllianceUseCase - requestAllianceSimple success`() = runTest {
        val diplomacyFacade = mock<DiplomacyFacade>()
        whenever(diplomacyFacade.requestAllianceSimple("sect-1")).thenReturn(true)
        val useCase = ManageAllianceUseCase(diplomacyFacade)

        val result = useCase.requestAllianceSimple("sect-1")

        assertTrue(result.success)
        assertEquals("结盟成功", result.message)
        verify(diplomacyFacade).requestAllianceSimple("sect-1")
    }

    @Test
    fun `ManageAllianceUseCase - requestAllianceSimple failure`() = runTest {
        val diplomacyFacade = mock<DiplomacyFacade>()
        whenever(diplomacyFacade.requestAllianceSimple("sect-1")).thenReturn(false)
        val useCase = ManageAllianceUseCase(diplomacyFacade)

        val result = useCase.requestAllianceSimple("sect-1")

        assertFalse(result.success)
        assertEquals("结盟失败", result.message)
    }

    @Test
    fun `ManageAllianceUseCase - dissolveAllianceSimple success`() = runTest {
        val diplomacyFacade = mock<DiplomacyFacade>()
        whenever(diplomacyFacade.dissolveAllianceSimple("sect-1")).thenReturn(true)
        val useCase = ManageAllianceUseCase(diplomacyFacade)

        val result = useCase.dissolveAllianceSimple("sect-1")

        assertTrue(result.success)
        assertEquals("已解除结盟", result.message)
        verify(diplomacyFacade).dissolveAllianceSimple("sect-1")
    }

    @Test
    fun `ManageAllianceUseCase - dissolveAllianceSimple failure`() = runTest {
        val diplomacyFacade = mock<DiplomacyFacade>()
        whenever(diplomacyFacade.dissolveAllianceSimple("sect-1")).thenReturn(false)
        val useCase = ManageAllianceUseCase(diplomacyFacade)

        val result = useCase.dissolveAllianceSimple("sect-1")

        assertFalse(result.success)
        assertEquals("解除结盟失败", result.message)
    }

    // ==================== 11. PlaceBuildingUseCase ====================

    @Test
    fun `PlaceBuildingUseCase - success path`() = runTest {
        val buildingFacade = mock<BuildingFacade>()
        val building = GridBuildingData(
            buildingId = "bld-1",
            displayName = "炼丹炉",
            gridX = 5,
            gridY = 10
        )
        val useCase = PlaceBuildingUseCase(buildingFacade)

        val result = useCase(building)

        assertTrue(result.isSuccess)
        verify(buildingFacade).placeBuilding(building)
    }

    @Test
    fun `PlaceBuildingUseCase - failure when facade throws`() = runTest {
        val buildingFacade = mock<BuildingFacade>()
        val building = GridBuildingData(buildingId = "bld-2")
        whenever(buildingFacade.placeBuilding(building))
            .thenThrow(RuntimeException("建造失败"))
        val useCase = PlaceBuildingUseCase(buildingFacade)

        val result = useCase(building)

        assertTrue(result.isFailure)
    }

    // ==================== 12. ProcessBattleCasualtiesUseCase ====================

    @Test
    fun `ProcessBattleCasualtiesUseCase - success path with all params`() = runTest {
        val battleFacade = mock<BattleFacade>()
        val deadIds = setOf("mem-1", "mem-2")
        val hpMap = mapOf("mem-3" to 50, "mem-4" to 100)
        val mpMap = mapOf("mem-3" to 30, "mem-4" to 80)
        val useCase = ProcessBattleCasualtiesUseCase(battleFacade)

        val result = useCase(deadIds, hpMap, mpMap)

        assertTrue(result.isSuccess)
        verify(battleFacade).processBattleCasualties(deadIds, hpMap, mpMap)
    }

    @Test
    fun `ProcessBattleCasualtiesUseCase - success path with default survivorMpMap`() = runTest {
        val battleFacade = mock<BattleFacade>()
        val deadIds = setOf("mem-1")
        val hpMap = mapOf("mem-2" to 100)
        val useCase = ProcessBattleCasualtiesUseCase(battleFacade)

        val result = useCase(deadIds, hpMap)

        assertTrue(result.isSuccess)
        verify(battleFacade).processBattleCasualties(deadIds, hpMap, emptyMap())
    }

    @Test
    fun `ProcessBattleCasualtiesUseCase - success with empty sets`() = runTest {
        val battleFacade = mock<BattleFacade>()

        val useCase = ProcessBattleCasualtiesUseCase(battleFacade)

        val result = useCase(emptySet(), emptyMap())

        assertTrue(result.isSuccess)
        verify(battleFacade).processBattleCasualties(emptySet(), emptyMap(), emptyMap())
    }

    @Test
    fun `ProcessBattleCasualtiesUseCase - failure when facade throws`() = runTest {
        val battleFacade = mock<BattleFacade>()
        val deadIds = setOf("mem-1")
        val hpMap = mapOf("mem-2" to 100)
        whenever(battleFacade.processBattleCasualties(deadIds, hpMap, emptyMap()))
            .thenThrow(RuntimeException("处理失败"))
        val useCase = ProcessBattleCasualtiesUseCase(battleFacade)

        val result = useCase(deadIds, hpMap)

        assertTrue(result.isFailure)
    }

    // ==================== 13. RecruitDiscipleUseCase ====================

    @Test
    fun `RecruitDiscipleUseCase - success path`() {
        val discipleFacade = mock<DiscipleFacade>()
        val disciple = mock<Disciple>()
        whenever(discipleFacade.recruitDisciple()).thenReturn(disciple)
        val useCase = RecruitDiscipleUseCase(discipleFacade)

        val result = useCase()

        assertTrue(result.isSuccess)
        assertSame(disciple, result.getOrNull())
        verify(discipleFacade).recruitDisciple()
    }

    @Test
    fun `RecruitDiscipleUseCase - failure when facade throws`() {
        val discipleFacade = mock<DiscipleFacade>()
        whenever(discipleFacade.recruitDisciple()).thenThrow(RuntimeException("招募失败"))
        val useCase = RecruitDiscipleUseCase(discipleFacade)

        val result = useCase()

        assertTrue(result.isFailure)
    }

    // ==================== 14. StartProductionUseCase ====================

    @Test
    fun `StartProductionUseCase - alchemy delegates to startAlchemy`() = runTest {
        val buildingFacade = mock<BuildingFacade>()
        val slot = mock<ProductionSlot>()
        whenever(buildingFacade.startAlchemy(0, "recipe-1")).thenReturn(DomainResult.Success(slot))
        val useCase = StartProductionUseCase(buildingFacade)

        val result = useCase(BuildingType.ALCHEMY, 0, "recipe-1")

        assertTrue(result.isSuccess)
        assertSame(slot, (result as DomainResult.Success).data)
        verify(buildingFacade).startAlchemy(0, "recipe-1")
        verify(buildingFacade, never()).startForging(any(), any())
    }

    @Test
    fun `StartProductionUseCase - forge delegates to startForging`() = runTest {
        val buildingFacade = mock<BuildingFacade>()
        val slot = mock<ProductionSlot>()
        whenever(buildingFacade.startForging(1, "recipe-2")).thenReturn(DomainResult.Success(slot))
        val useCase = StartProductionUseCase(buildingFacade)

        val result = useCase(BuildingType.FORGE, 1, "recipe-2")

        assertTrue(result.isSuccess)
        assertSame(slot, (result as DomainResult.Success).data)
        verify(buildingFacade).startForging(1, "recipe-2")
        verify(buildingFacade, never()).startAlchemy(any(), any())
    }

    @Test
    fun `StartProductionUseCase - alchemy returns failure result`() = runTest {
        val buildingFacade = mock<BuildingFacade>()
        val error = AppError.Domain.Production.SlotBusy(slotIndex = 2)
        whenever(buildingFacade.startAlchemy(2, "recipe-3")).thenReturn(DomainResult.Failure(error))
        val useCase = StartProductionUseCase(buildingFacade)

        val result = useCase(BuildingType.ALCHEMY, 2, "recipe-3")

        assertTrue(result.isFailure)
        assertEquals(error, (result as DomainResult.Failure).error)
    }

    @Test
    fun `StartProductionUseCase - forge returns failure result`() = runTest {
        val buildingFacade = mock<BuildingFacade>()
        val error = AppError.Domain.Production.RecipeNotFound(recipeId = "recipe-bad")
        whenever(buildingFacade.startForging(0, "recipe-bad")).thenReturn(DomainResult.Failure(error))
        val useCase = StartProductionUseCase(buildingFacade)

        val result = useCase(BuildingType.FORGE, 0, "recipe-bad")

        assertTrue(result.isFailure)
        assertEquals("PROD_004", (result as DomainResult.Failure).error.code)
    }

    @Test
    fun `StartProductionUseCase - unknown building type returns failure`() = runTest {
        val buildingFacade = mock<BuildingFacade>()
        val useCase = StartProductionUseCase(buildingFacade)

        val result = useCase(BuildingType.MINING, 0, "recipe-1")

        assertTrue(result.isFailure)
        val error = (result as DomainResult.Failure).error
        assertTrue(error is AppError.Domain.Production.InvalidSlot)
        assertEquals("PROD_003", error.code)
        verify(buildingFacade, never()).startAlchemy(any(), any())
        verify(buildingFacade, never()).startForging(any(), any())
    }
}
