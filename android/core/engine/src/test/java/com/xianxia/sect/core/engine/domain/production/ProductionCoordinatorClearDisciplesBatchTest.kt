package com.xianxia.sect.core.engine.domain.production

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.repository.ProductionSlotDataPort
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.transaction.ProductionTransactionManager
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * L3b 死亡清理批处理守卫：clearDisciplesFromRepository（批量版）聚合为单次
 * batchUpdate（单次 dao.updateAll），替代 N 次 clearDiscipleFromRepository
 *（N×M 次 dao.update）。语义与单弟子版逐位一致（仅清 assignedDiscipleId/Name）。
 */
class ProductionCoordinatorClearDisciplesBatchTest {

    private companion object {
        const val DISCIPLE_A = "101"
        const val DISCIPLE_B = "102"
        const val DISCIPLE_C = "103"
    }

    private fun newRepository(dao: ProductionSlotDataPort): ProductionSlotRepository {
        val scopeProvider = mock<CoroutineScopeProvider>()
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        return ProductionSlotRepository(
            dao = dao,
            configService = mock<BuildingConfigService>(),
            scopeProvider = scopeProvider
        )
    }

    private fun workingSlot(
        slotIndex: Int,
        buildingType: BuildingType,
        discipleId: String,
        discipleName: String
    ): ProductionSlot = ProductionSlot.createIdle(
        slotIndex = slotIndex,
        buildingType = buildingType,
        buildingId = when (buildingType) {
            BuildingType.FORGE -> BuildingNames.FORGE
            BuildingType.ALCHEMY -> BuildingNames.ALCHEMY
            BuildingType.HERB_GARDEN -> BuildingNames.HERB_GARDEN
            else -> buildingType.name.lowercase()
        }
    ).copy(
        assignedDiscipleId = discipleId,
        assignedDiscipleName = discipleName,
        status = ProductionSlotStatus.WORKING
    )

    @Test
    fun `clearDisciplesFromRepository - 多弟子多槽位聚合为单次 batchUpdate 且他弟子槽位保留`() = runTest {
        val dao = mock<ProductionSlotDataPort>()
        val repository = newRepository(dao)
        repository.restoreSlots(
            listOf(
                // A 占用 2 槽（跨建筑）
                workingSlot(0, BuildingType.FORGE, DISCIPLE_A, "弟子A"),
                workingSlot(0, BuildingType.ALCHEMY, DISCIPLE_A, "弟子A"),
                // B 占用 1 槽
                workingSlot(1, BuildingType.ALCHEMY, DISCIPLE_B, "弟子B"),
                // C 占用 1 槽（应保留）
                workingSlot(0, BuildingType.HERB_GARDEN, DISCIPLE_C, "弟子C")
            ),
            slotId = 1
        )
        val coordinator = ProductionCoordinator(
            repository = repository,
            transactionManager = mock<ProductionTransactionManager>()
        )

        coordinator.clearDisciplesFromRepository(listOf(DISCIPLE_A, DISCIPLE_B))

        // A/B 全部槽位清空
        assertNull("A 锻造槽应清空", repository.getSlotsByType(BuildingType.FORGE).first().assignedDiscipleId)
        assertNull("A 炼丹槽应清空", repository.getSlotsByType(BuildingType.ALCHEMY).first().assignedDiscipleId)
        val alchemySlots = repository.getSlotsByType(BuildingType.ALCHEMY)
        assertNull("B 炼丹槽应清空", alchemySlots[1].assignedDiscipleId)
        // C 保留
        assertEquals("C 灵田槽应保留", DISCIPLE_C,
            repository.getSlotsByType(BuildingType.HERB_GARDEN).first().assignedDiscipleId)

        // 聚合断言：单次 dao.updateAll（3 个槽位），无逐槽 dao.update
        verify(dao, times(1)).updateAll(org.mockito.kotlin.any())
        verify(dao, never()).update(org.mockito.kotlin.any())
    }

    @Test
    fun `clearDisciplesFromRepository - 空列表不触发任何 DAO 写`() = runTest {
        val dao = mock<ProductionSlotDataPort>()
        val repository = newRepository(dao)
        repository.restoreSlots(
            listOf(workingSlot(0, BuildingType.FORGE, DISCIPLE_A, "弟子A")),
            slotId = 1
        )
        val coordinator = ProductionCoordinator(
            repository = repository,
            transactionManager = mock<ProductionTransactionManager>()
        )

        coordinator.clearDisciplesFromRepository(emptyList())

        verify(dao, never()).updateAll(org.mockito.kotlin.any())
        verify(dao, never()).update(org.mockito.kotlin.any())
    }

    @Test
    fun `clearDisciplesFromRepository - 无匹配弟子时不触发 DAO 写`() = runTest {
        val dao = mock<ProductionSlotDataPort>()
        val repository = newRepository(dao)
        repository.restoreSlots(
            listOf(workingSlot(0, BuildingType.FORGE, DISCIPLE_A, "弟子A")),
            slotId = 1
        )
        val coordinator = ProductionCoordinator(
            repository = repository,
            transactionManager = mock<ProductionTransactionManager>()
        )

        coordinator.clearDisciplesFromRepository(listOf("999"))

        assertEquals("无关弟子槽位原样保留", DISCIPLE_A,
            repository.getSlotsByType(BuildingType.FORGE).first().assignedDiscipleId)
        verify(dao, never()).updateAll(org.mockito.kotlin.any())
        verify(dao, never()).update(org.mockito.kotlin.any())
    }
}
