package com.xianxia.sect.core.domain.building

import com.xianxia.sect.core.engine.domain.building.BuildingFeature
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.engine.domain.building.SlotGroup
import com.xianxia.sect.core.model.production.BuildingType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BuildingFeatureRegistryTest {

    @Before
    fun setup() {
        // 注册所有建筑（不依赖 R.drawable，使用占位值 0）
        BuildingFeatureRegistry.registerForTest()
    }

    @Test
    fun `所有 18 种建筑已注册`() {
        assertEquals(18, BuildingFeatureRegistry.all.size)
    }

    @Test
    fun `所有 key 唯一无重复`() {
        val keys = BuildingFeatureRegistry.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `每种建筑类型最多注册两个（SINGLE_RESIDENCE 含升级版本）`() {
        val byTypeCounts = BuildingFeatureRegistry.all.groupBy { it.buildingType }.mapValues { it.value.size }
        val duplicates = byTypeCounts.filter { it.value > 2 }
        assertTrue("Types with >2 registration: $duplicates", duplicates.isEmpty())
        // SINGLE_RESIDENCE 有 2 个（单人住所 + 中级单人住所），这是预期行为
        assertEquals(2, byTypeCounts[BuildingType.SINGLE_RESIDENCE])
    }

    @Test
    fun `住宅速度加成非空`() {
        val residences = BuildingFeatureRegistry.all.filter { it.isResidence }
        assertTrue(residences.isNotEmpty())
        residences.forEach { assertTrue(it.residenceSpeedBonus.isNotBlank()) }
    }

    @Test
    fun `single_residence_upgraded 不可直接建造`() {
        val feature = BuildingFeatureRegistry.findByKey("single_residence_upgraded")
        assertNotNull(feature)
        assertFalse(feature!!.isConstructible)
    }

    @Test
    fun `constructible 列表中不含升级版建筑`() {
        val keys = BuildingFeatureRegistry.constructible.map { it.key }
        assertFalse("升级版建筑不应在 constructible 中", "single_residence_upgraded" in keys)
    }

    @Test
    fun `constructible 包含所有可建造建筑`() {
        val keys = BuildingFeatureRegistry.constructible.map { it.key }
        assertTrue("灵矿场可建造", "spirit_mine" in keys)
        assertTrue("炼丹炉可建造", "alchemy" in keys)
        assertTrue("锻造坊可建造", "forge" in keys)
        assertTrue("单人住所可建造（可升级）", "single_residence" in keys)
    }

    @Test
    fun `findByKey 查询正确`() {
        val mine = BuildingFeatureRegistry.findByKey("spirit_mine")
        assertNotNull(mine)
        assertEquals("灵矿场", mine!!.displayName)
        assertEquals(BuildingType.MINING, mine.buildingType)
    }

    @Test
    fun `findByDisplayName 查询正确`() {
        val alchemy = BuildingFeatureRegistry.findByDisplayName("炼丹炉")
        assertNotNull(alchemy)
        assertEquals("alchemy", alchemy!!.key)
    }

    @Test
    fun `findByBuildingType 查询正确`() {
        val forge = BuildingFeatureRegistry.findByBuildingType(BuildingType.FORGE)
        assertNotNull(forge)
        assertEquals("锻造坊", forge!!.displayName)
    }

    @Test
    fun `residenceSpeedMultiplier 解析正确`() {
        assertEquals(1.20, BuildingFeatureRegistry.residenceSpeedMultiplier("单人住所"), 0.001)
        assertEquals(1.40, BuildingFeatureRegistry.residenceSpeedMultiplier("中级单人住所"), 0.001)
        assertEquals(1.10, BuildingFeatureRegistry.residenceSpeedMultiplier("多人住所"), 0.001)
        assertEquals(1.0, BuildingFeatureRegistry.residenceSpeedMultiplier("灵矿场"), 0.001)
        assertEquals(1.0, BuildingFeatureRegistry.residenceSpeedMultiplier("未知建筑"), 0.001)
    }

    @Test
    fun `isResidence 判断正确`() {
        assertTrue(BuildingFeatureRegistry.isResidence("单人住所"))
        assertTrue(BuildingFeatureRegistry.isResidence("中级单人住所"))
        assertTrue(BuildingFeatureRegistry.isResidence("多人住所"))
        assertFalse(BuildingFeatureRegistry.isResidence("灵矿场"))
        assertFalse(BuildingFeatureRegistry.isResidence("炼丹炉"))
        assertFalse(BuildingFeatureRegistry.isResidence(""))
    }

    @Test
    fun `hasNoLimit 判断正确`() {
        assertTrue(BuildingFeatureRegistry.hasNoLimit("灵矿场"))
        assertTrue(BuildingFeatureRegistry.hasNoLimit("炼丹炉"))
        assertFalse(BuildingFeatureRegistry.hasNoLimit("藏经阁"))
        assertFalse(BuildingFeatureRegistry.hasNoLimit(""))
    }
}

/**
 * 测试用注册：不依赖 R.drawable，使用占位值 0。
 */
private fun BuildingFeatureRegistry.registerForTest() {
    listOf(
        BuildingFeature("spirit_mine", "灵矿场", BuildingType.MINING,
            listOf(SlotGroup.SpiritMine()), unlimitedBuild = true,
            cost = 1500, gridWidth = 4, gridHeight = 4),
        BuildingFeature("spirit_field", "灵田", BuildingType.SPIRIT_FIELD,
            listOf(SlotGroup.SpiritField()), unlimitedBuild = true,
            cost = 200, gridWidth = 1, gridHeight = 1),
        BuildingFeature("herb_garden", "灵植阁", BuildingType.HERB_GARDEN,
            listOf(SlotGroup.ProductionSlotGroup()), unlimitedBuild = true,
            cost = 3000, gridWidth = 4, gridHeight = 3),
        BuildingFeature("alchemy", "炼丹炉", BuildingType.ALCHEMY,
            listOf(SlotGroup.ProductionSlotGroup()), unlimitedBuild = true,
            cost = 4000, gridWidth = 4, gridHeight = 3,
            baseSuccessRate = 0.7, autoRestartEnabled = true),
        BuildingFeature("forge", "锻造坊", BuildingType.FORGE,
            listOf(SlotGroup.ProductionSlotGroup()), unlimitedBuild = true,
            cost = 4000, gridWidth = 5, gridHeight = 3,
            baseSuccessRate = 0.7, autoRestartEnabled = true),
        BuildingFeature("warehouse", "仓库", BuildingType.WAREHOUSE,
            listOf(SlotGroup.Warehouse()), unlimitedBuild = true,
            cost = 1500, gridWidth = 6, gridHeight = 5),
        BuildingFeature("library", "藏经阁", BuildingType.LIBRARY,
            listOf(SlotGroup.Library(slotsPerInstance = 3)),
            cost = 8000, gridWidth = 6, gridHeight = 3),
        BuildingFeature("wen_dao_peak", "问道塔", BuildingType.WEN_DAO_PEAK,
            emptyList(), cost = 8000, gridWidth = 4, gridHeight = 3),
        BuildingFeature("qingyun_peak", "青云塔", BuildingType.QINGYUN_PEAK,
            emptyList(), cost = 8000, gridWidth = 4, gridHeight = 3),
        BuildingFeature("tianshu_hall", "天枢殿", BuildingType.ADMINISTRATION,
            emptyList(), cost = 15000, gridWidth = 6, gridHeight = 3),
        BuildingFeature("law_enforcement_hall", "执法堂", BuildingType.LAW_ENFORCEMENT_HALL,
            emptyList(), cost = 6000, gridWidth = 6, gridHeight = 3),
        BuildingFeature("mission_hall", "任务阁", BuildingType.MISSION_HALL,
            emptyList(), cost = 6000, gridWidth = 4, gridHeight = 3),
        BuildingFeature("patrol_tower", "巡视楼", BuildingType.PATROL,
            listOf(SlotGroup.PatrolTower()), unlimitedBuild = true,
            cost = 35000, gridWidth = 4, gridHeight = 4),
        BuildingFeature("reflection_cliff", "监牢", BuildingType.REFLECTION_CLIFF,
            emptyList(), cost = 5000, gridWidth = 4, gridHeight = 4),
        BuildingFeature("single_residence", "单人住所", BuildingType.SINGLE_RESIDENCE,
            listOf(SlotGroup.Residence(1)), isResidence = true, unlimitedBuild = true,
            upgradeTo = "single_residence_upgraded", upgradeCost = 50000,
            cost = 12000, gridWidth = 4, gridHeight = 4,
            residenceSpeedBonus = "修炼速度+20%"),
        BuildingFeature("single_residence_upgraded", "中级单人住所", BuildingType.SINGLE_RESIDENCE,
            listOf(SlotGroup.Residence(1)), isResidence = true, isConstructible = false, unlimitedBuild = true,
            cost = 30000, gridWidth = 6, gridHeight = 6,
            residenceSpeedBonus = "修炼速度+40%"),
        BuildingFeature("multi_residence", "多人住所", BuildingType.MULTI_RESIDENCE,
            listOf(SlotGroup.Residence(4)), isResidence = true, unlimitedBuild = true,
            cost = 24000, gridWidth = 6, gridHeight = 4,
            residenceSpeedBonus = "修炼速度+10%"),
        BuildingFeature("blood_refining_pool", "血炼池", BuildingType.BLOOD_REFINING_POOL,
            listOf(SlotGroup.BloodRefining()), unlimitedBuild = true,
            cost = 40000, gridWidth = 2, gridHeight = 2),
    ).forEach { register(it) }
}
