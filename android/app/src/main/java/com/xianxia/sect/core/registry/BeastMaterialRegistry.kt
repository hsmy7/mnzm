package com.xianxia.sect.core.registry

/**
 * 妖兽材料注册表
 *
 * 管理所有妖兽掉落材料的静态模板数据。
 *
 * ## 与原 BeastMaterialDatabase 的差异
 * - 继承 BaseTemplateRegistry，提供统一接口
 * - **使用 Map 替代 List 存储**，getById 从 O(n) 优化到 O(1)
 * - 保留原有的按妖兽类型、境界等查询方法
 * - 保留权重随机算法
 * - 数据源复用原 BeastMaterialDatabase（避免重复定义288条记录）
 */
class BeastMaterialRegistry : BaseTemplateRegistry<BeastMaterialDatabase.BeastMaterial>() {

    // ==================== 数据缓存 ====================

    /**
     * 所有材料列表（从原数据库获取）
     */
    private val allMaterialsList: List<BeastMaterialDatabase.BeastMaterial> by lazy {
        BeastMaterialDatabase.getAllMaterials()
    }

    /**
     * 按妖兽类型分组的材料映射
     */
    private val materialsByBeastType: Map<String, List<BeastMaterialDatabase.BeastMaterial>> by lazy {
        allMaterialsList.groupBy { extractBeastType(it.id) }
    }

    // ==================== BaseTemplateRegistry 实现 ====================

    override fun loadTemplates(): Map<String, BeastMaterialDatabase.BeastMaterial> {
        // 使用 associateBy 将 List 转换为 Map，实现 O(1) 查找
        return allMaterialsList.associateBy { it.id }
    }

    override fun extractRarity(template: BeastMaterialDatabase.BeastMaterial): Int = template.rarity

    // ==================== 扩展查询方法 ====================

    /**
     * 根据层级获取材料列表
     *
     * @param tier 材料层级（1-6）
     * @return 对应层级的所有材料
     */
    fun getByTier(tier: Int): List<BeastMaterialDatabase.BeastMaterial> =
        allMaterialsList.filter { it.tier == tier }

    /**
     * 根据妖兽类型获取材料列表
     *
     * @param beastType 妖兽类型名称（如"虎妖"、"狼妖"等）
     * @return 对应类型的所有材料
     */
    fun getByBeastType(beastType: String): List<BeastMaterialDatabase.BeastMaterial> =
        materialsByBeastType[beastType] ?: emptyList()

    /**
     * 根据玩家境界获取可掉落的材料列表
     *
     * 境界越高，可获得的材料层级范围越广。
     *
     * @param realm 玩家当前境界
     * @return 可掉落的材料列表
     */
    fun getDropMaterialsByRealm(realm: Int): List<BeastMaterialDatabase.BeastMaterial> {
        val tiers: List<Int> = when {
            realm >= 9 -> listOf(1)
            realm >= 8 -> listOf(1, 2)
            realm >= 7 -> listOf(1, 2, 3)
            realm >= 6 -> listOf(2, 3, 4)
            realm >= 5 -> listOf(3, 4, 5)
            realm >= 3 -> listOf(4, 5, 6)
            else -> listOf(5, 6)
        }
        return allMaterialsList.filter { it.tier in tiers }
    }

    /**
     * 根据名称查找材料
     *
     * @param name 材料名称
     * @return 匹配的材料，不存在则返回 null
     */
    fun getByName(name: String): BeastMaterialDatabase.BeastMaterial? =
        allMaterialsList.find { it.name == name }

    /**
     * 根据境界和运气值随机获取一个材料（带权重）
     *
     * 使用材料的 dropWeight 属性进行加权随机。
     *
     * @param realm 玩家境界
     * @param luck 运气倍数（默认1.0）
     * @return 随机选中的材料，无候选时返回 null
     */
    fun getRandomMaterialByRealm(realm: Int, luck: Double = 1.0): BeastMaterialDatabase.BeastMaterial? {
        val candidates = getDropMaterialsByRealm(realm)
        if (candidates.isEmpty()) return null

        return pickWeightedRandom(candidates) { it.dropWeight * luck }
    }

    /**
     * 根据妖兽类型和层级随机获取材料（带权重）
     *
     * @param beastType 妖兽类型
     * @param tier 目标层级
     * @param luck 运气倍数（默认1.0）
     * @return 随机选中的材料，无候选时返回 null
     */
    fun getRandomMaterialByBeastType(beastType: String, tier: Int, luck: Double = 1.0): BeastMaterialDatabase.BeastMaterial? {
        val materials = getByBeastType(beastType).filter { it.tier == tier }
        if (materials.isEmpty()) return null

        return pickWeightedRandom(materials) { it.dropWeight * luck }
    }

    // ==================== 私有辅助方法 ====================

    companion object {
        /**
         * 从材料 ID 提取妖兽类型前缀
         */
        private fun extractBeastType(id: String): String = when {
            id.startsWith("tiger") -> "虎妖"
            id.startsWith("wolf") -> "狼妖"
            id.startsWith("snake") -> "蛇妖"
            id.startsWith("bear") -> "熊妖"
            id.startsWith("eagle") -> "鹰妖"
            id.startsWith("fox") -> "狐妖"
            id.startsWith("dragon") -> "龙妖"
            id.startsWith("turtle") -> "龟妖"
            else -> "未知"
        }
    }
}
