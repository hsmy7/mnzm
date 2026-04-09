package com.xianxia.sect.core.data

/**
 * 锻造配方注册表
 *
 * 管理所有装备的锻造配方信息。
 *
 * @param equipmentRegistry 装备模板注册表（用于关联配方与装备）
 */
class ForgeRecipeRegistry(
    private val equipmentRegistry: EquipmentRegistry
) : BaseTemplateRegistry<ForgeRecipeDatabase.ForgeRecipe>() {

    // ==================== BaseTemplateRegistry 实现 ====================

    override fun loadTemplates(): Map<String, ForgeRecipeDatabase.ForgeRecipe> {
        // 从原数据库获取所有配方，转换为 Map 以优化查找性能
        return ForgeRecipeDatabase.getAllRecipes().associateBy { it.id }
    }

    override fun extractRarity(template: ForgeRecipeDatabase.ForgeRecipe): Int = template.rarity

    // ==================== 扩展查询方法 ====================

    /**
     * 根据装备槽位获取配方列表
     *
     * @param slot 装备槽位类型
     * @return 对应槽位的所有锻造配方
     */
    fun getByType(slot: com.xianxia.sect.core.model.EquipmentSlot): List<ForgeRecipeDatabase.ForgeRecipe> =
        allTemplates.values.filter { it.type == slot }

    /**
     * 根据层级获取配方列表
     *
     * @param tier 层级（1-6）
     * @return 对应层级的所有配方
     */
    fun getByTier(tier: Int): List<ForgeRecipeDatabase.ForgeRecipe> =
        ForgeRecipeDatabase.getRecipesByTier(tier)

    /**
     * 根据材料 ID 查找使用该材料的配方列表
     *
     * @param materialId 材料 ID
     * @return 使用该材料的所有锻造配方
     */
    fun getRecipesByMaterial(materialId: String): List<ForgeRecipeDatabase.ForgeRecipe> =
        ForgeRecipeDatabase.getRecipesByMaterial(materialId)

    /**
     * 根据名称查找配方
     *
     * @param name 配方/装备名称
     * @return 匹配的配方，不存在则返回 null
     */
    fun getByName(name: String): ForgeRecipeDatabase.ForgeRecipe? =
        allTemplates.values.find { it.name == name }

    /**
     * 获取层级对应的锻造时长
     *
     * @param tier 层级
     * @return 锻造所需时间单位
     */
    fun getDurationByTier(tier: Int): Int =
        ForgeRecipeDatabase.getDurationByTier(tier)

    // ==================== 联合查询方法 ====================

    /**
     * 获取配方对应的装备模板
     *
     * @param recipeId 配方 ID（通常与装备 ID 相同）
     * @return 装备模板，不存在则返回 null
     */
    fun getEquipmentTemplate(recipeId: String): EquipmentDatabase.EquipmentTemplate? =
        equipmentRegistry.getById(recipeId)

    /**
     * 获取完整的锻造信息（配方 + 装备模板）
     *
     * @param recipeId 配方 ID
     * @return 包含配方和装备模板的信息对，任一缺失则返回 null
     */
    fun getFullForgeInfo(recipeId: String): Pair<ForgeRecipeDatabase.ForgeRecipe, EquipmentDatabase.EquipmentTemplate>? {
        val recipe = getById(recipeId) ?: return null
        val template = equipmentRegistry.getById(recipeId) ?: return null
        return Pair(recipe, template)
    }
}
