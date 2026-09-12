package com.xianxia.sect.core.repository

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * ProductionSlotRepository 外部数据源 null 元素净化测试（Bugly #13014）。
 *
 * 读档/旧实现可能向槽位列表注入运行时 null（非空类型上仅反序列化/Java
 * 代码可产生），repository 三个外部进入点（initialize/loadSlots/restoreSlots）
 * 净化后索引与查询均不得崩。
 */
class ProductionSlotRepositorySanitizeTest {

    @Test
    fun `restoreSlots - 净化 null 元素后索引与查询均安全`() = runTest {
        val dao = mock<ProductionSlotDataPort>()
        val scopeProvider = mock<CoroutineScopeProvider>()
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val repository = ProductionSlotRepository(
            dao = dao,
            configService = mock<BuildingConfigService>(),
            scopeProvider = scopeProvider
        )
        val forgeSlot = ProductionSlot.createIdle(
            slotIndex = 0, buildingType = BuildingType.FORGE, buildingId = "forge"
        )
        // 非空类型列表通过 unchecked cast 注入运行时 null（模拟损坏存档反序列化）
        @Suppress("UNCHECKED_CAST")
        val dirtySlots = listOf<ProductionSlot?>(null, forgeSlot) as List<ProductionSlot>

        repository.restoreSlots(dirtySlots, slotId = 1)

        assertEquals(1, repository.getSlots().size)
        assertEquals(1, repository.getSlotsByBuildingId("forge").size)
        assertEquals(0, repository.getSlotsByType(BuildingType.ALCHEMY).size)
    }
}
