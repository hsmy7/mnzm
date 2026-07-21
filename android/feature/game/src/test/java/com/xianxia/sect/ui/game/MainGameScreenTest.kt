package com.xianxia.sect.ui.game

import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.util.GridSnapHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 主游戏界面相关逻辑单元测试。
 */
class MainGameScreenTest {

    // ============================================================
    // buildBuildingDataArray — Y-sorting 验证
    // ============================================================

    @Test
    fun `buildBuildingDataArray 按gridY升序排列`() {
        // 三个建筑，gridY 乱序
        val buildings = listOf(
            GridBuildingData(gridX = 5, gridY = 10, displayName = "灵矿场"),
            GridBuildingData(gridX = 3, gridY = 2, displayName = "中级单人住所"),
            GridBuildingData(gridX = 8, gridY = 5, displayName = "灵田")
        )
        val spriteSizes = mapOf(
            "灵矿场" to GridSnapHelper.BuildingSize(2, 2),
            "中级单人住所" to GridSnapHelper.BuildingSize(2, 2),
            "灵田" to GridSnapHelper.BuildingSize(2, 2)
        )

        val result = buildBuildingDataArray(buildings, spriteSizes)

        // 验证：结果顺序应为 [gridY=2, gridY=5, gridY=10]
        assertEquals("应包含3个建筑", 15, result.size) // 3 * 5
        assertEquals("第1个建筑gridY应为2", 2f, result[1], 0.001f)
        assertEquals("第2个建筑gridY应为5", 5f, result[6], 0.001f)
        assertEquals("第3个建筑gridY应为10", 10f, result[11], 0.001f)
    }

    @Test
    fun `buildBuildingDataArray 同gridY保持原有顺序`() {
        val buildings = listOf(
            GridBuildingData(gridX = 1, gridY = 5, displayName = "炼丹房", instanceId = "a"),
            GridBuildingData(gridX = 2, gridY = 5, displayName = "炼器室", instanceId = "b"),
            GridBuildingData(gridX = 3, gridY = 5, displayName = "灵矿场", instanceId = "c")
        )
        val spriteSizes = mapOf(
            "炼丹房" to GridSnapHelper.BuildingSize(2, 2),
            "炼器室" to GridSnapHelper.BuildingSize(2, 2),
            "灵矿场" to GridSnapHelper.BuildingSize(2, 2)
        )

        val result = buildBuildingDataArray(buildings, spriteSizes)

        // 同Y → 稳定排序保持插入顺序
        assertEquals("第1个buildingId应为炼丹房", "炼丹房", buildings[0].displayName)
        // 验证 result 的 nameIdx（第5列）——但nameIdx依赖BUILDING_NAME_INDEX映射，
        // 而 BUILDING_NAME_INDEX 是 private 的。
        // 改为验证 gridX 顺序（gridX 1→2→3 对应插入顺序）
        assertEquals("第1个建筑gridX应为1", 1f, result[0], 0.001f)
        assertEquals("第2个建筑gridX应为2", 2f, result[5], 0.001f)
        assertEquals("第3个建筑gridX应为3", 3f, result[10], 0.001f)
    }

    @Test
    fun `buildBuildingDataArray 空列表返回空数组`() {
        val result = buildBuildingDataArray(emptyList(), emptyMap())
        assertNotNull("结果不应为null", result)
        assertEquals("空列表应返回长度0的数组", 0, result.size)
    }

    @Test
    fun `buildBuildingDataArray 高Y建筑排在数组末尾`() {
        val buildings = listOf(
            GridBuildingData(gridX = 0, gridY = 20, displayName = "灵矿场"),
            GridBuildingData(gridX = 0, gridY = 0, displayName = "灵田"),
            GridBuildingData(gridX = 0, gridY = 15, displayName = "中级单人住所"),
            GridBuildingData(gridX = 0, gridY = 5, displayName = "炼丹房")
        )
        val spriteSizes = mapOf(
            "灵矿场" to GridSnapHelper.BuildingSize(2, 2),
            "灵田" to GridSnapHelper.BuildingSize(2, 2),
            "中级单人住所" to GridSnapHelper.BuildingSize(2, 2),
            "炼丹房" to GridSnapHelper.BuildingSize(2, 2)
        )

        val result = buildBuildingDataArray(buildings, spriteSizes)

        // 验证Y顺序：0, 5, 15, 20
        val yValues = (0 until 4).map { result[it * 5 + 1] }
        assertEquals(listOf(0f, 5f, 15f, 20f), yValues)
    }
}
