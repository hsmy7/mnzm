package com.xianxia.sect.ui.game.dialogs

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.domain.building.BuildingUpgradeRegistry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.ui.game.building.registerDefaults
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

/**
 * 一键升级列表派生（buildUpgradeRows）+ 生产注册表升级配置单元测试。
 *
 * 使用生产注册表（BuildingFeatureBoot.registerDefaults）：
 * 单人住所 20000 → 中级单人住所 50000（差价 30000）；
 * 多人住所 30000 → 中级多人住所 80000（差价 50000），目标均要求中型宗门。
 */
class BuildingUpgradeRowsTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun initRegistry() {
            BuildingFeatureRegistry.registerDefaults()
        }
    }

    private fun residence(
        key: String,
        name: String,
        instanceId: String,
        gridX: Int = 10,
        gridY: Int = 10,
        sectId: String = "main",
        width: Int = 4,
        height: Int = 4
    ) = GridBuildingData(
        buildingId = key, displayName = name,
        gridX = gridX, gridY = gridY, width = width, height = height,
        instanceId = instanceId, sectId = sectId
    )

    private fun data(buildings: List<GridBuildingData>) = GameData(
        spiritStones = 1_000_000,
        worldMapSects = listOf(WorldSect(id = "main", level = SectLevel.MEDIUM, isPlayerSect = true)),
        activeSectId = "main",
        placedBuildings = buildings
    )

    @Test
    fun `buildUpgradeRows - 无已建造可升级建筑时返回空列表`() {
        assertEquals(emptyList<BuildingUpgradeRow>(), buildUpgradeRows(data(emptyList())))
        // 只建了中级住所（无升级目标）也不应出现
        val onlyUpgraded = listOf(
            residence("single_residence_upgraded", "中级单人住所", "u1")
        )
        assertEquals(emptyList<BuildingUpgradeRow>(), buildUpgradeRows(data(onlyUpgraded)))
    }

    @Test
    fun `buildUpgradeRows - 按作用域宗门统计数量`() {
        val buildings = listOf(
            residence("single_residence", "单人住所", "s1", gridX = 10),
            residence("single_residence", "单人住所", "s2", gridX = 20),
            residence("multi_residence", "多人住所", "m1", gridX = 30),
            residence("single_residence", "单人住所", "o1", gridX = 40, sectId = "sect_b")
        )
        val rows = buildUpgradeRows(data(buildings))
        assertEquals("应只有两行（他宗门不计入）", 2, rows.size)

        val single = rows.first { it.def.sourceKey == "single_residence" }
        assertEquals("单人住所", single.displayName)
        assertEquals("单人住所可升级 2 座", 2, single.count)

        val multi = rows.first { it.def.sourceKey == "multi_residence" }
        assertEquals("多人住所", multi.displayName)
        assertEquals("多人住所可升级 1 座", 1, multi.count)
    }

    @Test
    fun `buildUpgradeRows - 保持注册表顺序`() {
        val buildings = listOf(
            residence("multi_residence", "多人住所", "m1"),
            residence("single_residence", "单人住所", "s1")
        )
        val rows = buildUpgradeRows(data(buildings))
        assertEquals(
            "顺序应遵循注册表（单人住所在前）",
            listOf("single_residence", "multi_residence"),
            rows.map { it.def.sourceKey }
        )
    }

    @Test
    fun `生产注册表 - 升级差价为目标减源造价（单人30000 多人50000）`() {
        val single = checkNotNull(BuildingUpgradeRegistry.findUpgrade("single_residence")) { "缺少单人住所升级配置" }
        assertEquals("单人住所差价", 30000L, BuildingUpgradeRegistry.upgradeCost(single))
        val multi = checkNotNull(BuildingUpgradeRegistry.findUpgrade("multi_residence")) { "缺少多人住所升级配置" }
        assertEquals("多人住所差价", 50000L, BuildingUpgradeRegistry.upgradeCost(multi))
    }

    @Test
    fun `生产注册表 - 升级目标均要求中型宗门`() {
        for (def in BuildingUpgradeRegistry.all) {
            val target = checkNotNull(BuildingFeatureRegistry.findByKey(def.targetKey)) { "目标未注册：${def.targetKey}" }
            assertEquals("${def.targetKey} 应要求中型宗门", SectLevel.MEDIUM, target.requiredSectLevel)
        }
    }
}
