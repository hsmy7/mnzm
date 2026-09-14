package com.xianxia.sect.core.registry

/**
 * 天赋注册表
 *
 * 管理所有天赋数据的静态模板。
 * 保留原 TalentDatabase 的复杂生成逻辑（按权重分布、正负天赋等）。
 */
class TalentRegistry : BaseTemplateRegistry<com.xianxia.sect.core.model.Talent>() {

    // ==================== 内部状态 ====================

    /**
     * 从天赋效果推断天赋类型
     */

    // ==================== BaseTemplateRegistry 实现 ====================

    override fun loadTemplates(): Map<String, com.xianxia.sect.core.model.Talent> {
        return TalentDatabase.talents
    }

    override fun extractRarity(template: com.xianxia.sect.core.model.Talent): Int = template.rarity

    // ==================== 扩展查询方法 ====================

    /**
     * 获取所有正天赋
     */
    fun getPositiveTalents(): List<com.xianxia.sect.core.model.Talent> =
        allTemplates.values.filter { !it.isNegative }

    /**
     * 获取所有负天赋
     */
    fun getNegativeTalents(): List<com.xianxia.sect.core.model.Talent> =
        allTemplates.values.filter { it.isNegative }

    /**
     * 根据名称查找天赋
     */
    fun getByName(name: String): com.xianxia.sect.core.model.Talent? =
        allTemplates.values.find { it.name == name }

    /**
     * 随机生成指定数量的天赋（带权重分布）
     *
     * 复用原 TalentDatabase 的生成逻辑，确保行为一致。
     *
     * @param count 生成数量
     * @param maxRarity 最高稀有度限制
     * @return 生成的天赋列表
     */
    fun generateRandomTalents(count: Int, maxRarity: Int = 3): List<com.xianxia.sect.core.model.Talent> {
        return TalentDatabase.generateRandomTalents(count, maxRarity)
    }

    /**
     * 为弟子随机生成天赋（模拟收徒场景）
     *
     * @return 生成的天赋列表
     */
    fun generateTalentsForDisciple(): List<com.xianxia.sect.core.model.Talent> {
        return TalentDatabase.generateTalentsForDisciple()
    }

    /**
     * 根据天赋 ID 列表计算总效果
     *
     * @param talentIds 天赋 ID 列表
     * @return 属性到效果值的映射
     */
    fun calculateTalentEffects(talentIds: List<String>): Map<String, Double> {
        return TalentDatabase.calculateTalentEffects(talentIds)
    }

    /**
     * 获取天赋显示信息（含颜色）
     *
     * @param talentId 天赋 ID
     * @return 显示信息，不存在则返回 null
     */
    fun getDisplayInfo(talentId: String): TalentDisplayInfo? {
        return TalentDatabase.getTalentDisplayInfo(talentId)
    }

}
