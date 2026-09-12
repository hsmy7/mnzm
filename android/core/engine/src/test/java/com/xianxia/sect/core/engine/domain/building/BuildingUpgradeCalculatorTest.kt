package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.domain.building.registerTestFeatures
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.WorldSect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 建筑升级资格/差价/空间纯逻辑单元测试（BuildingUpgradeCalculator / BuildingUpgradeRegistry）。
 *
 * 测试注册表造价：单人住所 12000 → 中级单人住所 50000（差价 38000）；
 * 多人住所 24000 → 中级多人住所 80000（差价 56000）。
 */
class BuildingUpgradeCalculatorTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun initRegistry() {
            BuildingFeatureRegistry.registerTestFeatures()
        }
    }

    // ── 辅助构造 ──────────────────────────────────────────────

    private fun data(
        stones: Long = 1_000_000,
        level: Int = SectLevel.MEDIUM,
        buildings: List<GridBuildingData> = emptyList(),
        activeSectId: String = "main"
    ) = GameData(
        spiritStones = stones,
        worldMapSects = listOf(WorldSect(id = "main", level = level, isPlayerSect = true)),
        activeSectId = activeSectId,
        placedBuildings = buildings
    )

    private fun singleResidence(
        instanceId: String = "s1",
        gridX: Int = 20,
        gridY: Int = 20,
        sectId: String = "main"
    ) = GridBuildingData(
        buildingId = "single_residence", displayName = "初级单人住所",
        gridX = gridX, gridY = gridY, width = 4, height = 4,
        instanceId = instanceId, sectId = sectId
    )

    private fun multiResidence(
        instanceId: String = "m1",
        gridX: Int = 20,
        gridY: Int = 20,
        sectId: String = "main"
    ) = GridBuildingData(
        buildingId = "multi_residence", displayName = "初级多人住所",
        gridX = gridX, gridY = gridY, width = 6, height = 4,
        instanceId = instanceId, sectId = sectId
    )

    private fun singleDef(): BuildingUpgradeDef =
        checkNotNull(BuildingUpgradeRegistry.findUpgrade("single_residence")) { "升级配置缺失：single_residence" }
    private fun multiDef(): BuildingUpgradeDef =
        checkNotNull(BuildingUpgradeRegistry.findUpgrade("multi_residence")) { "升级配置缺失：multi_residence" }

    // ── 差价 ──────────────────────────────────────────────────

    @Test
    fun `升级差价 - 单人住所为测试注册表差价38000`() {
        assertEquals(38000L, BuildingUpgradeRegistry.upgradeCost(singleDef()))
    }

    @Test
    fun `升级差价 - 多人住所为测试注册表差价56000`() {
        assertEquals(56000L, BuildingUpgradeRegistry.upgradeCost(multiDef()))
    }

    @Test
    fun `升级差价 - 等于目标造价减源造价且为正数`() {
        for (def in BuildingUpgradeRegistry.all) {
            val source = checkNotNull(BuildingFeatureRegistry.findByKey(def.sourceKey)) { "源建筑未注册：${def.sourceKey}" }
            val target = checkNotNull(BuildingFeatureRegistry.findByKey(def.targetKey)) { "目标建筑未注册：${def.targetKey}" }
            assertEquals(
                "差价应与注册表造价差一致",
                target.cost - source.cost,
                BuildingUpgradeRegistry.upgradeCost(def)
            )
            assertTrue("目标造价应高于源造价", target.cost > source.cost)
        }
    }

    @Test
    fun `升级差价 - 未注册源或目标时防御返回0`() {
        val bad = BuildingUpgradeDef("missing_source", "single_residence_upgraded")
        assertEquals(0L, BuildingUpgradeRegistry.upgradeCost(bad))
        val badTarget = BuildingUpgradeDef("single_residence", "missing_target")
        assertEquals(0L, BuildingUpgradeRegistry.upgradeCost(badTarget))
    }

    // ── 资格判定：正常路径 ────────────────────────────────────

    @Test
    fun `checkUpgrade - 等级灵石空间全满足时返回Upgradeable`() {
        val b = singleResidence()
        val result = BuildingUpgradeCalculator.checkUpgrade(data(buildings = listOf(b)), b.instanceId)
        assertEquals(UpgradeCheckResult.Upgradeable, result)
    }

    @Test
    fun `checkUpgrade - 多人住所全满足时返回Upgradeable`() {
        val b = multiResidence()
        val result = BuildingUpgradeCalculator.checkUpgrade(data(buildings = listOf(b)), b.instanceId)
        assertEquals(UpgradeCheckResult.Upgradeable, result)
    }

    // ── 资格判定：灵石不足 ────────────────────────────────────

    @Test
    fun `checkUpgrade - 灵石不足返回灵石不足原因`() {
        val b = singleResidence()
        val result = BuildingUpgradeCalculator.checkUpgrade(
            data(stones = 37999, buildings = listOf(b)), b.instanceId
        )
        val reasons = (result as UpgradeCheckResult.ConditionsUnmet).reasons
        assertTrue("应包含灵石不足原因，实际：$reasons", reasons.any { it.contains("灵石不足") })
        assertFalse("灵石足时不应出现宗门等级原因", reasons.any { it.contains("宗门等级") })
    }

    // ── 资格判定：宗门等级不足 ────────────────────────────────

    @Test
    fun `checkUpgrade - 小型宗门返回宗门等级原因`() {
        val b = singleResidence()
        val result = BuildingUpgradeCalculator.checkUpgrade(
            data(level = SectLevel.SMALL, buildings = listOf(b)), b.instanceId
        )
        val reasons = (result as UpgradeCheckResult.ConditionsUnmet).reasons
        assertTrue("应包含宗门等级原因，实际：$reasons", reasons.any { it.contains("宗门等级") })
    }

    @Test
    fun `checkUpgrade - 等级不足且灵石不足时同时列出两条原因`() {
        val b = singleResidence()
        val result = BuildingUpgradeCalculator.checkUpgrade(
            data(stones = 100, level = SectLevel.SMALL, buildings = listOf(b)), b.instanceId
        )
        val reasons = (result as UpgradeCheckResult.ConditionsUnmet).reasons
        assertTrue("应同时列出灵石不足与宗门等级原因，实际：$reasons",
            reasons.any { it.contains("灵石不足") } && reasons.any { it.contains("宗门等级") })
    }

    // ── 资格判定：空间不足 ────────────────────────────────────

    @Test
    fun `checkUpgrade - 相邻建筑阻挡升级占地时返回空间不足`() {
        // 单人住所 4×4 → 中级单人住所 6×6：右侧紧贴 4×4 建筑会阻挡扩地
        val target = singleResidence(instanceId = "s1", gridX = 20, gridY = 20)
        val blocker = singleResidence(instanceId = "s2", gridX = 24, gridY = 20)
        val result = BuildingUpgradeCalculator.checkUpgrade(
            data(buildings = listOf(target, blocker)), target.instanceId
        )
        val reasons = (result as UpgradeCheckResult.ConditionsUnmet).reasons
        assertTrue("应包含空间不足原因，实际：$reasons", reasons.any { it.contains("空间不足") })
    }

    @Test
    fun `checkUpgrade - 升级占地越出可建边界时返回空间不足`() {
        // gridX=123：4×4 占地合法（123+4=127 ≤ 128-3），升级 6×6 越界（123+6=129 > 125）
        val b = singleResidence(gridX = 123, gridY = 20)
        val result = BuildingUpgradeCalculator.checkUpgrade(data(buildings = listOf(b)), b.instanceId)
        val reasons = (result as UpgradeCheckResult.ConditionsUnmet).reasons
        assertTrue("贴边建筑升级应因空间不足被拒，实际：$reasons", reasons.any { it.contains("空间不足") })
    }

    // ── 资格判定：不可升级建筑 ────────────────────────────────

    @Test
    fun `checkUpgrade - 非住所建筑返回NotUpgradeable`() {
        val mine = GridBuildingData(
            buildingId = "spirit_mine", displayName = "灵矿场",
            gridX = 20, gridY = 20, width = 4, height = 4,
            instanceId = "mine1", sectId = "main"
        )
        assertEquals(
            UpgradeCheckResult.NotUpgradeable,
            BuildingUpgradeCalculator.checkUpgrade(data(buildings = listOf(mine)), "mine1")
        )
    }

    @Test
    fun `checkUpgrade - 未知实例返回NotUpgradeable`() {
        assertEquals(
            UpgradeCheckResult.NotUpgradeable,
            BuildingUpgradeCalculator.checkUpgrade(data(), "not-exist")
        )
    }

    // ── 空间判定 canFitUpgrade ────────────────────────────────

    @Test
    fun `canFitUpgrade - 独立建筑可容纳升级占地`() {
        val b = singleResidence()
        assertTrue(BuildingUpgradeCalculator.canFitUpgrade(listOf(b), b, singleDef()))
    }

    @Test
    fun `canFitUpgrade - 他宗门建筑不阻挡本宗门升级`() {
        val b = singleResidence(sectId = "main")
        val otherSect = singleResidence(instanceId = "o1", gridX = 24, gridY = 20, sectId = "other")
        assertTrue(
            BuildingUpgradeCalculator.canFitUpgrade(listOf(b, otherSect), b, singleDef())
        )
    }

    @Test
    fun `canFitUpgrade - 同宗门重叠时拒绝`() {
        val b = singleResidence()
        val blocker = singleResidence(instanceId = "s2", gridX = 24, gridY = 20, sectId = "main")
        assertFalse(
            BuildingUpgradeCalculator.canFitUpgrade(listOf(b, blocker), b, singleDef())
        )
    }

    @Test
    fun `canFitUpgrade - 边界内恰好放下时允许`() {
        // 地图 128 格，BORDER_TREE_RING=3，可建区 [3,125)；6×6 最大左上角 X=119
        val b = singleResidence(gridX = 119, gridY = 119)
        assertTrue(BuildingUpgradeCalculator.canFitUpgrade(listOf(b), b, singleDef()))
    }

    @Test
    fun `canFitUpgrade - 边界外拒绝`() {
        val b = singleResidence(gridX = 120, gridY = 20)
        assertFalse(BuildingUpgradeCalculator.canFitUpgrade(listOf(b), b, singleDef()))
    }

    @Test
    fun `canFitUpgrade - 多人住所扩地一格同样校验`() {
        // 多人住所 6×4 → 中级多人住所 6×5：下方紧贴 1×1 灵田会阻挡向下扩地
        val b = multiResidence(gridY = 20)
        val blocker = GridBuildingData(
            buildingId = "spirit_field", displayName = "灵田",
            gridX = 20, gridY = 24, width = 1, height = 1,
            instanceId = "f1", sectId = "main"
        )
        assertFalse(BuildingUpgradeCalculator.canFitUpgrade(listOf(b, blocker), b, multiDef()))
    }

    @Test
    fun `GridRect overlaps - 边界相接不算重叠`() {
        assertFalse(GridRect(0, 0, 4, 4).overlaps(GridRect(4, 0, 4, 4)))
        assertFalse(GridRect(0, 0, 4, 4).overlaps(GridRect(0, 4, 4, 4)))
    }

    @Test
    fun `GridRect overlaps - 部分与完全重叠判定正确`() {
        assertTrue(GridRect(0, 0, 4, 4).overlaps(GridRect(3, 0, 4, 4)))
        assertTrue(GridRect(0, 0, 6, 6).overlaps(GridRect(1, 1, 2, 2)))
    }
}
