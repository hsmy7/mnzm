package com.xianxia.sect.core.data

/**
 * 丹药配方注册表
 *
 * 管理所有丹药的**炼制配方**信息。
 * 通过 [pillId] 引用 [PillTemplateRegistry] 中的丹药效果定义，避免数据重复。
 *
 * ## 与原 PillRecipeDatabase 的差异
 * - **去除冗余字段**：不再重复存储 name/rarity/description/effect 等效果属性
 * - **通过 pillId 引用**：只保留配方特有字段（materials, duration, successRate）
 * - 提供 [getFullPillRecipe] 方法合并完整信息
 *
 * @param pillRegistry 丹药模板注册表（用于获取丹药效果定义）
 */
class PillRecipeRegistry(
    private val pillRegistry: PillTemplateRegistry
) : BaseTemplateRegistry<PillRecipeDatabase.PillRecipe>() {

    // ==================== 数据结构 ====================

    /**
     * 精简后的配方数据类（只保留配方特有字段）
     *
     * 复用原 PillRecipeDatabase.PillRecipe，但使用时不再依赖其中的冗余效果字段
     */

    /**
     * 完整的丹药配方信息（合并了效果定义和配方信息）
     */
    data class FullPillRecipe(
        val recipe: PillRecipeDatabase.PillRecipe,
        val pillTemplate: ItemDatabase.PillTemplate
    ) {
        /** 配方 ID */
        val id: String get() = recipe.id

        /** 丹药名称（从模板获取） */
        val name: String get() = pillTemplate.name

        /** 稀有度（从模板获取） */
        val rarity: Int get() = pillTemplate.rarity

        /** 分类（从模板获取） */
        val category: com.xianxia.sect.core.model.PillCategory get() = pillTemplate.category

        /** 描述（从模板获取） */
        val description: String get() = pillTemplate.description

        /** 所需材料（配方特有） */
        val materials: Map<String, Int> get() = recipe.materials

        /** 炼制时长（配方特有） */
        val duration: Int get() = recipe.duration

        /** 成功率（配方特有） */
        val successRate: Double get() = recipe.successRate

        /** 配方层级 */
        val tier: Int get() = recipe.tier
    }

    // ==================== BaseTemplateRegistry 实现 ====================

    override fun loadTemplates(): Map<String, PillRecipeDatabase.PillRecipe> {
        // 从原数据库获取所有配方，转换为 Map 以优化查找性能
        return PillRecipeDatabase.getAllRecipes().associateBy { it.id }
    }

    override fun extractRarity(template: PillRecipeDatabase.PillRecipe): Int = template.rarity

    // ==================== 核心查询方法 ====================

    /**
     * 获取完整的丹药配方信息（合并效果定义 + 配方）
     *
     * 这是推荐使用的查询方法，返回包含完整信息的对象。
     *
     * @param id 配方 ID（同时也是丹药模板 ID）
     * @return 完整的配方信息，如果任一数据源缺失则返回 null
     */
    fun getFullPillRecipe(id: String): FullPillRecipe? {
        val recipe = getById(id) ?: return null
        val pillTemplate = pillRegistry.getById(id) ?: return null

        return FullPillRecipe(recipe, pillTemplate)
    }

    /**
     * 根据名称获取完整配方
     *
     * @param name 丹药/配方名称
     * @return 完整的配方信息
     */
    fun getFullByName(name: String): FullPillRecipe? {
        // 先通过名称找到对应的 ID
        val pillTemplate = pillRegistry.getByName(name) ?: return null
        return getFullPillRecipe(pillTemplate.id)
    }

    // ==================== 扩展查询方法 ====================

    /**
     * 根据材料 ID 查找使用该材料的配方列表
     *
     * @param materialId 材料 ID
     * @return 使用该材料的所有配方
     */
    fun getRecipesByMaterial(materialId: String): List<PillRecipeDatabase.PillRecipe> =
        allTemplates.values.filter { it.materials.containsKey(materialId) }

    /**
     * 根据层级获取配方列表
     *
     * @param tier 层级（1-6）
     * @return 对应层级的所有配方
     */
    fun getByTier(tier: Int): List<PillRecipeDatabase.PillRecipe> =
        allTemplates.values.filter { it.tier == tier }

    /**
     * 获取层级对应的炼制时长
     *
     * @param tier 层级
     * @return 炼制所需时间单位
     */
    fun getDurationByTier(tier: Int): Int = when (tier) {
        1 -> 2
        2 -> 5
        3 -> 9
        4 -> 18
        5 -> 30
        6 -> 48
        else -> 2
    }

    /**
     * 获取层级名称
     *
     * @param tier 层级
     * @return 层级的中文名称
     */
    fun getTierName(tier: Int): String = when (tier) {
        1 -> "凡品"
        2 -> "灵品"
        3 -> "宝品"
        4 -> "玄品"
        5 -> "地品"
        6 -> "天品"
        else -> "未知"
    }

    /**
     * 根据分类获取完整配方列表
     *
     * @param category 丹药分类
     * @return 该分类下所有完整配方
     */
    fun getFullByCategory(category: com.xianxia.sect.core.model.PillCategory): List<FullPillRecipe> {
        return allTemplates.values
            .filter { it.category == category }
            .mapNotNull { getFullPillRecipe(it.id) }
    }
}
