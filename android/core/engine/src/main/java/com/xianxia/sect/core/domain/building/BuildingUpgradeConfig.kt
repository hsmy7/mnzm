package com.xianxia.sect.core.engine.domain.building

/**
 * 建筑升级关系配置（单一事实源）。
 *
 * 升级差价 = 目标建筑造价 - 源建筑造价，从 [BuildingFeatureRegistry] 动态计算，
 * 不在本配置中硬编码数值——造价变化时差价自动跟随。
 */
data class BuildingUpgradeDef(
    /** 源建筑 key（被升级的建筑） */
    val sourceKey: String,
    /** 目标建筑 key（升级后的建筑） */
    val targetKey: String
)

/**
 * 升级关系注册表：当前支持「初级住所 → 中级住所」两组升级。
 *
 * 新增升级链（如中级→高级、炼丹炉→高级炼丹炉）时只需在此追加一行，
 * 引擎与 UI 层自动生效（界面按注册表驱动）。
 */
object BuildingUpgradeRegistry {

    val all: List<BuildingUpgradeDef> = listOf(
        BuildingUpgradeDef("single_residence", "single_residence_upgraded"),
        BuildingUpgradeDef("multi_residence", "multi_residence_upgraded")
    )

    /**
     * 按源建筑 key 查找升级关系。
     *
     * @param sourceKey 源建筑 key
     * @return 匹配的升级关系，无则 null
     */
    fun findUpgrade(sourceKey: String): BuildingUpgradeDef? = all.find { it.sourceKey == sourceKey }

    /**
     * 升级差价（目标造价 - 源造价）。
     *
     * @param def 升级关系
     * @return 差价灵石数；源/目标任一侧未注册时返回 0（防御，正常流程不会触发）
     */
    fun upgradeCost(def: BuildingUpgradeDef): Long {
        val source = BuildingFeatureRegistry.findByKey(def.sourceKey) ?: return 0
        val target = BuildingFeatureRegistry.findByKey(def.targetKey) ?: return 0
        return (target.cost - source.cost).coerceAtLeast(0)
    }
}
