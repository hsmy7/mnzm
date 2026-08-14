package com.xianxia.sect.core.domain.building

import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.production.BuildingType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 自动守卫：新增 [BuildingType] 枚举值时，若忘记同步更新注册表，测试将失败。
 *
 * ## 新增建筑类型的必改清单
 *
 * 当你在 [BuildingType] 添加了新的枚举值，测试会在此文件中报错。
 * 请同步更新以下 4 处：
 *
 * 1. [BuildingType.displayName] — 添加显示名称映射
 * 2. [BuildingType.toSlotType] — 添加槽位类型映射
 * 3. [BuildingFeatureRegistry] — 在启动注册中添加新建筑的 BuildingFeature（参考 BuildingFeatureBoot.kt）
 * 4. 本测试文件 — 将新 [BuildingType] 加入 coveredByRegistry 集合
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class BuildingTypeCoverageTest {

    @Before
    fun setup() {
        BuildingFeatureRegistry.registerTestFeatures()
    }

    /**
     * BuildingFeatureRegistry 中预期注册的建筑类型。
     * 一种 BuildingType 可能对应多个 BuildingFeature（如 SINGLE_RESIDENCE 有升级版本），
     * 只要 findByBuildingType 返回 non-null 即视为已覆盖。
     */
    private val coveredByRegistry = setOf(
        BuildingType.ALCHEMY,
        BuildingType.FORGE,
        BuildingType.MINING,
        BuildingType.SPIRIT_FIELD,
        BuildingType.HERB_GARDEN,
        BuildingType.ADMINISTRATION,
        BuildingType.LIBRARY,
        BuildingType.WEN_DAO_PEAK,
        BuildingType.QINGYUN_PEAK,
        BuildingType.LAW_ENFORCEMENT_HALL,
        BuildingType.MISSION_HALL,
        BuildingType.REFLECTION_CLIFF,
        BuildingType.SINGLE_RESIDENCE,
        BuildingType.MULTI_RESIDENCE,
        BuildingType.WAREHOUSE,
        BuildingType.PATROL,
        BuildingType.BLOOD_REFINING_POOL,
    )

    @Test
    fun `all BuildingType values are registered in BuildingFeatureRegistry`() {
        // 验证 coveredByRegistry 的每个值确实在注册表中
        coveredByRegistry.forEach { type ->
            assertNotNull(
                "BuildingType.${type.name} 在 coveredByRegistry 列表中但未注册到 BuildingFeatureRegistry！" +
                "\n请在 registerForTest() 中添加对应的 BuildingFeature 条目",
                BuildingFeatureRegistry.findByBuildingType(type)
            )
        }

        // 检查是否有新增的 BuildingType 值未被 coveredByRegistry 覆盖
        val allTypes = BuildingType.values().toSet()
        val covered = coveredByRegistry
        val missing = allTypes - covered

        assertTrue(
            """
            |新增 BuildingType 未在 coveredByRegistry 中覆盖！
            |
            |以下类型需要添加到本测试的 coveredByRegistry 集合：
            |  $missing
            |
            |操作指引：
            |  1. 在 registerForTest() 中添加新的 BuildingFeature 条目
            |  2. 如果该类型不应注册（如仅用于内部标识）→ 加入 coveredByRegistry 集合并注明原因
            """.trimMargin(),
            missing.isEmpty()
        )
    }

    @Test
    fun `all BuildingType values have non-empty displayName`() {
        BuildingType.values().forEach { type ->
            assertTrue(
                "BuildingType.${type.name}.displayName 返回了空字符串！请在 ProductionSlot.kt 中添加 displayName 映射",
                type.displayName.isNotEmpty()
            )
        }
}

}
