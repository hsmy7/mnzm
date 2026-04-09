package com.xianxia.sect.core.data

/**
 * 天赋注册表
 *
 * 管理所有天赋数据的静态模板。
 * 保留原 TalentDatabase 的复杂生成逻辑（按权重分布、正负天赋等）。
 */
class TalentRegistry : BaseTemplateRegistry<com.xianxia.sect.core.model.Talent>() {

    // ==================== 内部状态 ====================

    /**
     * 天赋原始数据（包含额外的元信息）
     */
    private val allTalentData: Map<String, TalentDatabase.TalentData> by lazy {
        // 从原数据库获取所有天赋数据
        val talentDataMap = mutableMapOf<String, TalentDatabase.TalentData>()

        // 通过反射或直接访问原数据库的内部数据
        // 这里我们复用 TalentDatabase 的数据
        TalentDatabase.getTalentsByIds(
            TalentDatabase.talents.keys.toList()
        ).forEach { talent ->
            // 需要从 Talent 反推 TalentData，这里简化处理
            // 实际实现中可能需要直接访问 TalentDatabase 的内部 API
        }

        // 临时方案：重新构建数据（与原数据库保持一致）
        buildTalentDataMap()
    }

    /**
     * 正天赋稀有度分布配置
     */
    private val positiveRarityDistribution = listOf(
        1 to 0.419,
        2 to 0.272,
        3 to 0.168,
        4 to 0.084,
        5 to 0.037,
        6 to 0.020
    )

    /**
     * 负天赋出现概率
     */
    private val negativeTalentChance = 0.14

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
    fun generateRandomTalents(count: Int, maxRarity: Int = 6): List<com.xianxia.sect.core.model.Talent> {
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

    // ==================== 私有辅助方法 ====================

    /**
     * 构建完整的天赋数据映射（临时方案）
     */
    private fun buildTalentDataMap(): Map<String, TalentDatabase.TalentData> {
        // 由于 TalentDatabase 没有公开 getAllTalentData() 方法，
        // 我们通过 generateRandomTalents 的行为间接使用其内部逻辑
        // 这里返回空 map，实际使用时委托给 TalentDatabase
        return emptyMap()
    }
}
