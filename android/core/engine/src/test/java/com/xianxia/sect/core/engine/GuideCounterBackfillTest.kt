package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 引导累计建造计数回填（computeBuildingCounterBackfill）纯函数单元测试。
 *
 * 语义：max(现有计数, 当前存量)——旧档无计数时按存量回填，已有更高计数不被覆盖。
 */
class GuideCounterBackfillTest {

    private fun building(name: String, instanceId: String, sectId: String = "main") =
        GridBuildingData(
            buildingId = name, displayName = name,
            gridX = 0, gridY = 0, width = 1, height = 1,
            instanceId = instanceId, sectId = sectId
        )

    @Test
    fun `回填 - 旧档无计数时按当前存量回填`() {
        val buildings = listOf(
            building("单人住所", "s1"), building("单人住所", "s2"), building("单人住所", "s3"),
            building("多人住所", "m1")
        )
        val result = computeBuildingCounterBackfill(buildings, emptyMap())
        assertEquals(
            "单人住所应回填 3",
            3L, result[GuideCounterKeys.buildingBuiltKey("单人住所")]
        )
        assertEquals(
            "多人住所应回填 1",
            1L, result[GuideCounterKeys.buildingBuiltKey("多人住所")]
        )
    }

    @Test
    fun `回填 - 不覆盖已有更高计数`() {
        val buildings = listOf(building("单人住所", "s1"), building("单人住所", "s2"))
        val existing = mapOf(GuideCounterKeys.buildingBuiltKey("单人住所") to 5L)
        val result = computeBuildingCounterBackfill(buildings, existing)
        assertEquals("已有计数 5 应保留", 5L, result[GuideCounterKeys.buildingBuiltKey("单人住所")])
    }

    @Test
    fun `回填 - 现有计数低于存量时取存量`() {
        val buildings = (1..7).map { building("灵矿场", "m$it") }
        val existing = mapOf(GuideCounterKeys.buildingBuiltKey("灵矿场") to 3L)
        val result = computeBuildingCounterBackfill(buildings, existing)
        assertEquals("存量 7 高于计数 3 应取 7", 7L, result[GuideCounterKeys.buildingBuiltKey("灵矿场")])
    }

    @Test
    fun `回填 - 空建筑与空计数幂等`() {
        assertEquals(emptyMap<String, Long>(), computeBuildingCounterBackfill(emptyList(), emptyMap()))
        val existing = mapOf("other" to 1L)
        assertEquals("无关计数保留", existing, computeBuildingCounterBackfill(emptyList(), existing))
    }

    @Test
    fun `回填 - 各建筑名独立回填互不干扰`() {
        val buildings = listOf(
            building("炼丹炉", "a1"), building("炼丹炉", "a2"),
            building("锻造坊", "f1"), building("锻造坊", "f2"), building("锻造坊", "f3")
        )
        val result = computeBuildingCounterBackfill(buildings, emptyMap())
        assertEquals(2L, result[GuideCounterKeys.buildingBuiltKey("炼丹炉")])
        assertEquals(3L, result[GuideCounterKeys.buildingBuiltKey("锻造坊")])
        assertEquals("仅两个建筑名各自计一条", 2, result.size)
    }
}
