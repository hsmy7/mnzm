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
     * 天赋原始数据（包含额外的元信息）
     */
    private val allTalentData: Map<String, TalentDatabase.TalentData> by lazy {
        // 从 TalentDatabase 的 talents 映射构建 TalentData
        val talentDataMap = mutableMapOf<String, TalentDatabase.TalentData>()

        TalentDatabase.talents.forEach { (id, talent) ->
            val type = inferTalentType(talent.effects, talent.isNegative)
            talentDataMap[id] = TalentDatabase.TalentData(
                id = talent.id,
                name = talent.name,
                description = talent.description,
                rarity = talent.rarity,
                effects = talent.effects,
                isNegative = talent.isNegative,
                type = type,
                template = id
            )
        }

        talentDataMap
    }

    /**
     * 从天赋效果推断天赋类型
     */
    private fun inferTalentType(effects: Map<String, Double>, isNegative: Boolean): TalentDatabase.TalentType {
        return when {
            effects.containsKey("cultivationSpeed") -> TalentDatabase.TalentType.CULT_SPEED
            effects.containsKey("breakthroughChance") -> TalentDatabase.TalentType.BREAK_CHANCE
            effects.containsKey("lifespan") -> TalentDatabase.TalentType.LIFESPAN
            effects.containsKey("physicalAttack") -> TalentDatabase.TalentType.BAT_PHY_ATK
            effects.containsKey("magicAttack") -> TalentDatabase.TalentType.BAT_MAG_ATK
            effects.containsKey("physicalDefense") -> TalentDatabase.TalentType.BAT_PHY_DEF
            effects.containsKey("magicDefense") -> TalentDatabase.TalentType.BAT_MAG_DEF
            effects.containsKey("maxHp") -> TalentDatabase.TalentType.BAT_HP
            effects.containsKey("maxMp") -> TalentDatabase.TalentType.BAT_MP
            effects.containsKey("speed") -> TalentDatabase.TalentType.BAT_SPEED
            effects.containsKey("critRate") -> TalentDatabase.TalentType.BAT_CRIT
            effects.containsKey("manualSlot") -> TalentDatabase.TalentType.MANUAL_SLOT
            effects.containsKey("winBattleRandomAttrPlus") -> TalentDatabase.TalentType.WIN_GROWTH
            effects.containsKey("intelligenceFlat") -> TalentDatabase.TalentType.BASE_INT
            effects.containsKey("charmFlat") -> TalentDatabase.TalentType.BASE_CHARM
            effects.containsKey("loyaltyFlat") -> TalentDatabase.TalentType.BASE_LOYAL
            effects.containsKey("comprehensionFlat") -> TalentDatabase.TalentType.BASE_COMP
            effects.containsKey("artifactRefiningFlat") -> TalentDatabase.TalentType.BASE_ARTI
            effects.containsKey("pillRefiningFlat") -> TalentDatabase.TalentType.BASE_PILL
            effects.containsKey("spiritPlantingFlat") -> TalentDatabase.TalentType.BASE_PLANT
            effects.containsKey("teachingFlat") -> TalentDatabase.TalentType.BASE_TEACH
            effects.containsKey("moralityFlat") -> TalentDatabase.TalentType.BASE_MORAL
            else -> TalentDatabase.TalentType.CULT_SPEED // 默认
        }
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

}
