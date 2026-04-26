package com.xianxia.sect.core.registry

/**
 * 丹药模板注册表
 *
 * 管理所有丹药的**效果定义**（不含配方信息）。
 * 这是纯效果数据源，与 [PillRecipeRegistry] 配合使用。
 *
 * ## 职责分离
 * - **PillTemplateRegistry**（本类）：定义丹药的效果属性（突破概率、修炼速度、属性加成等）
 * - **PillRecipeRegistry**：定义如何炼制丹药（所需材料、炼制时间、成功率等）
 *
 * ## 数据来源
 * 复用原 ItemDatabase 中的 PillTemplate 定义。
 */
class PillTemplateRegistry : BaseTemplateRegistry<ItemDatabase.PillTemplate>() {

    // ==================== BaseTemplateRegistry 实现 ====================

    override fun loadTemplates(): Map<String, ItemDatabase.PillTemplate> {
        return ItemDatabase.allPills
    }

    override fun extractRarity(template: ItemDatabase.PillTemplate): Int = template.rarity

    // ==================== 扩展查询方法 ====================

    /**
     * 根据分类获取丹药列表
     *
     * @param category 丹药分类
     * @return 对应分类的所有丹药
     */
    fun getByCategory(category: com.xianxia.sect.core.model.PillCategory): List<ItemDatabase.PillTemplate> =
        allTemplates.values.filter { it.category == category }

    /**
     * 根据名称查找丹药
     *
     * @param name 丹药名称
     * @return 匹配的丹药模板，不存在则返回 null
     */
    fun getByName(name: String): ItemDatabase.PillTemplate? =
        allTemplates.values.find { it.name == name }

    /**
     * 从模板创建丹药实例
     *
     * @param template 丹药模板
     * @param quantity 数量（默认1）
     * @return 新生成的丹药实例
     */
    fun createFromTemplate(template: ItemDatabase.PillTemplate, quantity: Int = 1): com.xianxia.sect.core.model.Pill {
        return ItemDatabase.createPillFromTemplate(template, quantity)
    }

    /**
     * 随机生成一个丹药实例
     *
     * @param minRarity 最低稀有度（默认1）
     * @param maxRarity 最高稀有度（默认6）
     * @return 随机生成的丹药实例
     */
    fun generateRandom(minRarity: Int = 1, maxRarity: Int = 6): com.xianxia.sect.core.model.Pill {
        val template = getRandom(minRarity, maxRarity)
        return createFromTemplate(template)
    }
}
