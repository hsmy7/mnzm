package com.xianxia.sect.core.domain.building

import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
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
        BuildingFeatureRegistry.registerTestFeatures()
    }

    @Test
    fun `所有建筑类型已在注册表中覆盖`() {
        val registeredTypes = BuildingFeatureRegistry.all.map { it.buildingType }.toSet()
        val allTypes = BuildingType.values().toSet()
        val missing = allTypes - registeredTypes
        assertTrue(
            "以下 BuildingType 未在 BuildingFeatureRegistry 中注册：$missing",
            missing.isEmpty()
        )
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
    fun `single_residence_upgraded 可直接建造`() {
        val feature = BuildingFeatureRegistry.findByKey("single_residence_upgraded")
        assertNotNull(feature)
        assertTrue("中级单人住所现在可直接建造", feature!!.isConstructible)
    }

    @Test
    fun `constructible 包含中级建筑`() {
        val keys = BuildingFeatureRegistry.constructible.map { it.key }
        assertTrue("中级单人住所可建造", "single_residence_upgraded" in keys)
        assertTrue("中级多人住所可建造", "multi_residence_upgraded" in keys)
    }

    @Test
    fun `constructible 包含所有可建造建筑`() {
        val keys = BuildingFeatureRegistry.constructible.map { it.key }
        assertTrue("灵矿场可建造", "spirit_mine" in keys)
        assertTrue("炼丹炉可建造", "alchemy" in keys)
        assertTrue("锻造坊可建造", "forge" in keys)
        assertTrue("单人住所可建造", "single_residence" in keys)
        assertTrue("中级单人住所可建造", "single_residence_upgraded" in keys)
        assertTrue("中级多人住所可建造", "multi_residence_upgraded" in keys)
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
