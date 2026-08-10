package com.xianxia.sect.ui.game

import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.util.GridSnapHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `buildBuildingDataArray 按地面接触点排序而非gridY`() {
        // 仓库(fpH=5)在 gridY=0 → 视觉底部=5
        // 问道塔(fpH=3)在 gridY=1 → 视觉底部=4
        // 按 gridY: 仓库(0)→问道塔(1) — 错误！问道塔最后绘制，覆盖仓库
        // 按底部: 问道塔(4)→仓库(5) — 正确！仓库最后绘制，覆盖问道塔
        val buildings = listOf(
            GridBuildingData(gridX = 0, gridY = 0, width = 6, height = 5, displayName = "仓库"),
            GridBuildingData(gridX = 0, gridY = 1, width = 4, height = 3, displayName = "问道塔")
        )
        val spriteSizes = mapOf(
            "仓库" to GridSnapHelper.BuildingSize(6, 6),
            "问道塔" to GridSnapHelper.BuildingSize(4, 8)
        )

        val result = buildBuildingDataArray(buildings, spriteSizes)

        // 验证：问道塔(gridY=1, 底部=4) 应排在 仓库(gridY=0, 底部=5) 之前
        assertEquals("第1个建筑gridY应为1(问道塔)", 1f, result[1], 0.001f)
        assertEquals("第2个建筑gridY应为0(仓库)", 0f, result[6], 0.001f)
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

    // ============================================================
    // buildSpiritCropData（WP6）— 灵田作物渲染数据装配
    // ============================================================

    /** 灵田建筑（gridX, gridY, instanceId） */
    private fun field(x: Int, y: Int, id: String) =
        GridBuildingData(gridX = x, gridY = y, displayName = "灵田", instanceId = id)

    /** 非灵田建筑 */
    private fun mine(id: String) =
        GridBuildingData(gridX = 0, gridY = 0, displayName = "灵矿场", instanceId = id)

    /** 种植记录：year=1 month=1 播种，growTime=12 月 */
    private fun plant(id: String, sectId: String = "s1", seedId: String = "s1Seed") =
        SpiritFieldPlant(
            buildingInstanceId = id,
            seedId = seedId,
            seedName = "聚气草种子",
            growTime = 12,
            plantYear = 1,
            plantMonth = 1,
            sectId = sectId
        )

    @Test
    fun `buildSpiritCropData 输出种植中的灵田三元组`() {
        val buildings = listOf(field(3, 5, "f1"))
        val result = buildSpiritCropData(
            buildings, listOf(plant("f1")),
            currentYear = 1, currentMonth = 7, sectId = "s1"
        )
        assertNotNull("有种植记录应输出数据", result)
        // elapsed = 6 月 / 12 = 0.5
        assertEquals("数据条数应为 3（1 条三元组）", 3, result!!.size)
        assertEquals("gx 应为 3", 3f, result[0], 0.001f)
        assertEquals("gy 应为 5", 5f, result[1], 0.001f)
        assertEquals("progress 应为 0.5", 0.5f, result[2], 0.001f)
    }

    @Test
    fun `buildSpiritCropData 未种植或无记录返回null`() {
        val buildings = listOf(field(0, 0, "f1"))
        // 有田无任何种植记录
        assertNull(
            "无种植记录应返回 null",
            buildSpiritCropData(buildings, emptyList(), 1, 7, "s1")
        )
        // 种植记录存在但 seedId 为空（未播种的田）
        assertNull(
            "seedId 为空应返回 null",
            buildSpiritCropData(
                buildings, listOf(plant("f1", seedId = "")),
                1, 7, "s1"
            )
        )
        // 种植记录对应其他田
        assertNull(
            "记录不匹配该田应返回 null",
            buildSpiritCropData(buildings, listOf(plant("f2")), 1, 7, "s1")
        )
    }

    @Test
    fun `buildSpiritCropData 非灵田建筑跳过`() {
        val buildings = listOf(mine("m1"), field(1, 1, "f1"))
        val result = buildSpiritCropData(
            buildings, listOf(plant("f1")),
            currentYear = 1, currentMonth = 1, sectId = "s1"
        )
        assertNotNull(result)
        // 只有灵田输出 1 条；灵矿场被跳过（count*3=3）
        assertEquals(3, result!!.size)
        assertEquals("gx 应为灵田的 1", 1f, result[0], 0.001f)
    }

    @Test
    fun `buildSpiritCropData 跨宗门种植记录跳过`() {
        val buildings = listOf(field(0, 0, "f1"))
        // 种植记录属其他宗门 → 防御性跳过（不渲染他宗作物）
        assertNull(
            "跨宗门记录应跳过",
            buildSpiritCropData(
                buildings, listOf(plant("f1", sectId = "s2")),
                1, 7, "s1"
            )
        )
    }

    @Test
    fun `buildSpiritCropData 部分灵田有种植时只输出有种植的`() {
        val buildings = listOf(field(0, 0, "f1"), field(2, 2, "f2"), field(4, 4, "f3"))
        val result = buildSpiritCropData(
            buildings, listOf(plant("f2"), plant("f3")),
            currentYear = 1, currentMonth = 1, sectId = "s1"
        )
        assertNotNull(result)
        // 2 条记录 × 3 = 6（f1 无种植被排除）
        assertEquals(6, result!!.size)
        // 数组按输入顺序紧凑排列
        assertEquals("第1条应为 f2 的 x", 2f, result[0], 0.001f)
        assertEquals("第2条应为 f3 的 x", 4f, result[3], 0.001f)
    }

    @Test
    fun `buildSpiritCropData 不排序保持输入顺序`() {
        // 与 buildBuildingDataArray 的 Y 排序不同：作物层数据与建筑数组无索引关联，
        // 后端按三元组独立解析——顺序无关，保持输入顺序即可
        val buildings = listOf(field(9, 9, "f2"), field(1, 1, "f1"))
        val result = buildSpiritCropData(
            buildings, listOf(plant("f1"), plant("f2")),
            currentYear = 1, currentMonth = 1, sectId = "s1"
        )
        assertNotNull(result)
        assertEquals(6, result!!.size)
        assertEquals("保持输入顺序 f2 在前", 9f, result[0], 0.001f)
        assertEquals("f1 在后", 1f, result[3], 0.001f)
    }

    @Test
    fun `buildSpiritCropData 进度随时间推进`() {
        val buildings = listOf(field(0, 0, "f1"))
        val early = buildSpiritCropData(
            buildings, listOf(plant("f1")),
            currentYear = 1, currentMonth = 4, sectId = "s1"
        )
        val late = buildSpiritCropData(
            buildings, listOf(plant("f1")),
            currentYear = 1, currentMonth = 10, sectId = "s1"
        )
        assertNotNull(early)
        assertNotNull(late)
        // elapsed 3/12=0.25 vs 9/12=0.75
        assertEquals("早期进度应为 0.25", 0.25f, early!![2], 0.001f)
        assertEquals("晚期进度应为 0.75", 0.75f, late!![2], 0.001f)
        // 超过 growTime → clamp 1.0（后端 crossfade 仍显示成熟阶段，不翻转）
        val overdue = buildSpiritCropData(
            buildings, listOf(plant("f1")),
            currentYear = 5, currentMonth = 1, sectId = "s1"
        )
        assertEquals("超期进度应 clamp 到 1.0", 1f, overdue!![2], 0.001f)
    }
}
