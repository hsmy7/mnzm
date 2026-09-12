package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.domain.building.registerTestFeatures
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 拆除清理覆盖守卫测试。
 *
 * 新增建筑时必须注册 [SlotGroup]（如 ElderPositions），
 * 拆除时关联槽位/弟子才能自动清理。本测试在新增建筑漏注册时失败并给出指引。
 *
 * 维护约定：
 * 1. 在 BuildingFeatureBoot.registerDefaults（feature:game 模块）注册建筑及 SlotGroup
 * 2. 同步更新 registerTestFeatures 测试注册表
 * 3. 监牢/任务阁的拆除清理在 BuildingFacadeImpl.cleanupBuildingSlots 的 buildingType
 *    特判分支中（见 intentionallyExcluded），新建此类特殊建筑需同样在 facade 特判
 */
class BuildingRemovalCoverageTest {

    companion object {
        /** 故意豁免：清理逻辑在 BuildingFacadeImpl.cleanupBuildingSlots 特判分支 */
        private val intentionallyExcluded = setOf("reflection_cliff", "mission_hall")

        @BeforeClass
        @JvmStatic
        fun initRegistry() {
            BuildingFeatureRegistry.registerTestFeatures()
        }
    }

    @Test
    fun `all registered buildings have at least one SlotGroup for demolition cleanup`() {
        val missing = BuildingFeatureRegistry.all
            .filter { it.key !in intentionallyExcluded }
            .filter { it.slotGroups.isEmpty() }
            .map { it.key }
            .sorted()

        assertTrue(
            "以下建筑未注册任何 SlotGroup，一键拆除时关联槽位/弟子无法自动清理：$missing\n" +
                "请在 BuildingFeatureBoot.registerDefaults 中为建筑注册 SlotGroup（如 ElderPositions），" +
                "并同步更新 BuildingFeatureTestRegistration.registerTestFeatures。\n" +
                "豁免项 $intentionallyExcluded 的清理在 BuildingFacadeImpl.cleanupBuildingSlots 特判分支中，" +
                "新增此类建筑需同步扩展特判。",
            missing.isEmpty()
        )
    }
}
