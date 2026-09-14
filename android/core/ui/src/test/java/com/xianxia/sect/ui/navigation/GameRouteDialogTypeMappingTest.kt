package com.xianxia.sect.ui.navigation

import com.xianxia.sect.core.domain.dialog.DialogType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * toDialogType 穷举性守卫（toDialogType 为"带参路由 when + 1:1 路由查表"，
 * sealed when 的编译期穷尽检查不覆盖查表分支，由本测试以
 * [GameRoute.sealedSubclasses] 全量补位：新增路由漏登记映射即此处红）。
 */
class GameRouteDialogTypeMappingTest {

    @Test
    fun `every sealed subclass maps to a dialog type`() {
        val routes = GameRoute::class.sealedSubclasses.map { it.objectInstance }
            .filterNotNull()
        assertEquals("路由总数漂移请同步本测试语义", 28, routes.size)
        routes.forEach { route ->
            val dialogType = route.toDialogType("building-1")
            // DialogType 与路由同类同名（唯一例外：BattleResult 归 None）
            val expectedKey = if (route is GameRoute.BattleResult) "None" else route::class.simpleName
            assertEquals("路由 ${route.route} 的映射漂移", expectedKey, dialogType.routeKey())
        }
    }

    @Test
    fun `parameterized routes carry building instance id`() {
        assertEquals("building-1", GameRoute.SpiritMine.toDialogType("building-1").buildingIdOrNull())
        assertEquals("building-1", GameRoute.Alchemy.toDialogType("building-1").buildingIdOrNull())
        assertEquals("building-1", GameRoute.Forge.toDialogType("building-1").buildingIdOrNull())
        assertEquals("building-1", GameRoute.PatrolTower.toDialogType("building-1").buildingIdOrNull())
        assertEquals("building-1", GameRoute.BloodRefiningPool.toDialogType("building-1").buildingIdOrNull())
        assertEquals("building-1", GameRoute.Residence.toDialogType("building-1").buildingIdOrNull())
        assertEquals("building-1", GameRoute.WarehouseBuilding.toDialogType("building-1").buildingIdOrNull())
    }

    /** DialogType 的路由身份键（object/data class 均取类名） */
    private fun DialogType.routeKey(): String = this::class.simpleName ?: ""

    private fun DialogType.buildingIdOrNull(): String? =
        (this as? DialogType.SpiritMine)?.buildingInstanceId
            ?: (this as? DialogType.Alchemy)?.buildingInstanceId
            ?: (this as? DialogType.Forge)?.buildingInstanceId
            ?: (this as? DialogType.PatrolTower)?.buildingInstanceId
            ?: (this as? DialogType.BloodRefiningPool)?.buildingInstanceId
            ?: (this as? DialogType.Residence)?.buildingInstanceId
            ?: (this as? DialogType.WarehouseBuilding)?.buildingInstanceId
}
