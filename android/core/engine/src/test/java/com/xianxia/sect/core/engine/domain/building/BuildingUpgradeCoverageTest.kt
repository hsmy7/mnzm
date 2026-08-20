package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.domain.building.registerTestFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 建筑升级配置覆盖守卫测试（CLAUDE.md 9.5 守卫三要素）。
 *
 * 锚点：BuildingUpgradeRegistry.all 全部配置行 + 注册表全部住所建筑。
 * 新增住所建筑或升级链时若忘记同步升级配置，本测试失败并给出指引。
 */
class BuildingUpgradeCoverageTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun initRegistry() {
            BuildingFeatureRegistry.registerTestFeatures()
        }
    }

    @Test
    fun `所有升级配置的源与目标均已注册且满足约束`() {
        for (def in BuildingUpgradeRegistry.all) {
            val source = checkNotNull(BuildingFeatureRegistry.findByKey(def.sourceKey)) {
                "升级配置源建筑未注册：${def.sourceKey}——" +
                    "请检查 BuildingUpgradeRegistry 与 BuildingFeatureBoot/registerTestFeatures"
            }
            val target = checkNotNull(BuildingFeatureRegistry.findByKey(def.targetKey)) {
                "升级配置目标建筑未注册：${def.targetKey}——请先注册目标建筑再添加升级配置"
            }
            assertTrue("源建筑必须是住所类型：${def.sourceKey}", source.isResidence)
            assertTrue(
                "目标建筑宗门等级要求应为中型（SectLevel.MEDIUM）：${def.targetKey}",
                target.requiredSectLevel == SectLevel.MEDIUM
            )
            assertTrue(
                "目标造价必须高于源造价：${def.sourceKey}(${source.cost}) → ${def.targetKey}(${target.cost})",
                target.cost > source.cost
            )
        }
    }

    @Test
    fun `所有住所建筑要么是升级源要么是升级目标`() {
        val sources = BuildingUpgradeRegistry.all.map { it.sourceKey }.toSet()
        val targets = BuildingUpgradeRegistry.all.map { it.targetKey }.toSet()
        val residences = BuildingFeatureRegistry.all.filter { it.isResidence }.map { it.key }
        val uncovered = residences.filter { it !in sources && it !in targets }
        assertTrue(
            "以下住所建筑未纳入升级配置（新增住所时需在 BuildingUpgradeRegistry 补充升级链，" +
                "或显式声明故意排除）：$uncovered",
            uncovered.isEmpty()
        )
    }

    @Test
    fun `住所显示名与精灵名解耦配置一致`() {
        // 显示名补全分级前缀（初级…），精灵图集名保持历史名称（单人住所/多人住所）——
        // 若注册表/旧档迁移（BuildingLoadSelfHeal.normalizeResidenceDisplayNames）任一侧漂移，
        // 旧档迁移后名称仍不匹配注册表或渲染查不到图集索引，本守卫变红
        val single = checkNotNull(BuildingFeatureRegistry.findByKey("single_residence")) { "single_residence 未注册" }
        val multi = checkNotNull(BuildingFeatureRegistry.findByKey("multi_residence")) { "multi_residence 未注册" }
        assertEquals("初级单人住所", single.displayName)
        assertEquals("初级多人住所", multi.displayName)
        assertEquals("初级住所精灵名应保持图集历史名称", "单人住所", single.effectiveSpriteName())
        assertEquals("初级多人住所精灵名应保持图集历史名称", "多人住所", multi.effectiveSpriteName())
        // 中级住所显示名即精灵名（无前缀解耦）
        val singleUp = checkNotNull(BuildingFeatureRegistry.findByKey("single_residence_upgraded"))
        assertEquals("中级单人住所", singleUp.effectiveSpriteName())
        // 非住所建筑显示名即精灵名
        val mine = checkNotNull(BuildingFeatureRegistry.findByKey("spirit_mine"))
        assertEquals(mine.displayName, mine.effectiveSpriteName())
    }
}
