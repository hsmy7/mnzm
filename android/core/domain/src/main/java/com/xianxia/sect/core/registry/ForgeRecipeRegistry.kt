package com.xianxia.sect.core.registry

/**
 * 锻造配方注册表（B3 套装部件制、四部位化 F3：24 条配方 = 6 套 × 4 部位，按品阶产出）
 */
class ForgeRecipeRegistry : BaseTemplateRegistry<ForgeRecipeDatabase.ForgeRecipe>() {

    // ==================== BaseTemplateRegistry 实现 ====================

    override fun loadTemplates(): Map<String, ForgeRecipeDatabase.ForgeRecipe> {
        return ForgeRecipeDatabase.getAllRecipes().associateBy { it.id }
    }

    override fun extractRarity(template: ForgeRecipeDatabase.ForgeRecipe): Int = 1
    // 配方不分品阶（按品阶产出，方案 §3.8）：随机抽取面恒 1，品阶由锻造槽位 tier 决定

    // ==================== 扩展查询方法 ====================

    /** 根据装备部位获取配方列表 */
    fun getByType(part: com.xianxia.sect.core.model.EquipmentSlot): List<ForgeRecipeDatabase.ForgeRecipe> =
        allTemplates.values.filter { it.part == part }

    /** 根据套装获取配方列表 */
    fun getBySet(setId: String): List<ForgeRecipeDatabase.ForgeRecipe> =
        allTemplates.values.filter { it.setId == setId }

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
     * @param name 配方/部件名称
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
     * 获取配方对应的套装部件模板
     *
     * @param recipeId 配方 ID（`forge_{pieceId}`）
     * @return 套装部件模板，不存在则返回 null
     */
    fun getPieceTemplate(recipeId: String): EquipmentDatabase.SetPieceTemplate? =
        getById(recipeId)?.let { EquipmentDatabase.getPieceById(it.pieceId) }

    /**
     * 获取完整的锻造信息（配方 + 套装部件模板）
     *
     * @param recipeId 配方 ID
     * @return 包含配方和部件模板的信息对，任一缺失则返回 null
     */
    fun getFullForgeInfo(recipeId: String): Pair<ForgeRecipeDatabase.ForgeRecipe,
        EquipmentDatabase.SetPieceTemplate>? {
        val recipe = getById(recipeId) ?: return null
        val template = EquipmentDatabase.getPieceById(recipe.pieceId) ?: return null
        return Pair(recipe, template)
    }
}
