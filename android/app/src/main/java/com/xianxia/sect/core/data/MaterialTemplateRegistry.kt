package com.xianxia.sect.core.data

/**
 * 材料模板注册表
 *
 * 管理所有通用材料的静态模板数据（含妖兽材料、草药等）。
 *
 * ## 数据来源
 * - 妖兽材料：从 [BeastMaterialRegistry] 转换
 * - 其他材料：可扩展（如矿石、木材等）
 *
 * @param beastMaterialRegistry 妖兽材料注册表（用于转换妖兽材料）
 */
class MaterialTemplateRegistry(
    private val beastMaterialRegistry: BeastMaterialRegistry
) : BaseTemplateRegistry<ItemDatabase.MaterialTemplate>() {

    // ==================== BaseTemplateRegistry 实现 ====================

    override fun loadTemplates(): Map<String, ItemDatabase.MaterialTemplate> {
        // 从 BeastMaterialRegistry 获取所有妖兽材料并转换为 MaterialTemplate
        return beastMaterialRegistry.allTemplates.mapValues { (id, beastMaterial) ->
            ItemDatabase.MaterialTemplate(
                id = id,
                name = beastMaterial.name,
                category = beastMaterial.materialCategory,
                rarity = beastMaterial.rarity,
                description = beastMaterial.description,
                price = beastMaterial.price
            )
        }
    }

    override fun extractRarity(template: ItemDatabase.MaterialTemplate): Int = template.rarity

    // ==================== 扩展查询方法 ====================

    /**
     * 根据分类获取材料列表
     *
     * @param category 材料分类
     * @return 对应分类的所有材料
     */
    fun getByCategory(category: com.xianxia.sect.core.model.MaterialCategory): List<ItemDatabase.MaterialTemplate> =
        allTemplates.values.filter { it.category == category }

    /**
     * 根据名称查找材料
     *
     * @param name 材料名称
     * @return 匹配的材料模板，不存在则返回 null
     */
    fun getByName(name: String): ItemDatabase.MaterialTemplate? =
        allTemplates.values.find { it.name == name }

    /**
     * 从模板创建材料实例
     *
     * @param template 材料模板
     * @param quantity 数量（默认1）
     * @return 新生成的材料实例
     */
    fun createFromTemplate(template: ItemDatabase.MaterialTemplate, quantity: Int = 1): com.xianxia.sect.core.model.Material {
        return ItemDatabase.createMaterialFromTemplate(template, quantity)
    }

    /**
     * 随机生成一个材料实例
     *
     * @param minRarity 最低稀有度（默认1）
     * @param maxRarity 最高稀有度（默认6）
     * @return 随机生成的材料实例
     */
    fun generateRandom(minRarity: Int = 1, maxRarity: Int = 6): com.xianxia.sect.core.model.Material {
        val template = getRandom(minRarity, maxRarity)
        return createFromTemplate(template)
    }
}
