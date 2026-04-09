package com.xianxia.sect.core.data

/**
 * 草药注册表
 *
 * 管理所有草药和种子的静态模板数据。
 * 支持按层级、稀有度等多维度查询。
 */
class HerbRegistry : BaseTemplateRegistry<HerbDatabase.Herb>() {

    // ==================== 数据缓存 ====================

    /**
     * 所有草药模板
     */
    val allHerbTemplates: Map<String, HerbDatabase.Herb> by lazy {
        HerbDatabase.getAllHerbs().associateBy { it.id }
    }

    /**
     * 所有种子模板
     */
    val allSeedTemplates: Map<String, HerbDatabase.Seed> by lazy {
        HerbDatabase.getAllSeeds().associateBy { it.id }
    }

    /**
     * 种子到草药的 ID 映射
     */
    private val seedToHerbMap: Map<String, String> by lazy {
        HerbDatabase.getAllSeeds().associate { seed ->
            seed.id to seed.id.removeSuffix("Seed")
        }
    }

    // ==================== BaseTemplateRegistry 实现 ====================

    override fun loadTemplates(): Map<String, HerbDatabase.Herb> {
        return allHerbTemplates
    }

    override fun extractRarity(template: HerbDatabase.Herb): Int = template.rarity

    // ==================== 扩展查询方法（草药）====================

    /**
     * 根据层级获取草药列表
     *
     * @param tier 层级（1-6）
     * @return 对应层级的所有草药
     */
    fun getHerbsByTier(tier: Int): List<HerbDatabase.Herb> =
        HerbDatabase.getHerbsByTier(tier)

    /**
     * 根据名称查找草药
     *
     * @param name 草药名称
     * @return 匹配的草药，不存在则返回 null
     */
    fun getHerbByName(name: String): HerbDatabase.Herb? =
        HerbDatabase.getHerbByName(name)

    /**
     * 获取草药总数
     */
    fun getHerbCount(): Int = allHerbTemplates.size

    // ==================== 扩展查询方法（种子）====================

    /**
     * 根据层级获取种子列表
     *
     * @param tier 层级（1-6）
     * @return 对应层级的所有种子
     */
    fun getSeedsByTier(tier: Int): List<HerbDatabase.Seed> =
        HerbDatabase.getSeedsByTier(tier)

    /**
     * 根据 ID 查找种子
     *
     * @param id 种子 ID
     * @return 匹配的种子，不存在则返回 null
     */
    fun getSeedById(id: String): HerbDatabase.Seed? =
        HerbDatabase.getSeedById(id)

    /**
     * 根据名称查找种子
     *
     * @param name 种子名称
     * @return 匹配的种子，不存在则返回 null
     */
    fun getSeedByName(name: String): HerbDatabase.Seed? =
        HerbDatabase.getSeedByName(name)

    /**
     * 获取种子总数
     */
    fun getSeedCount(): Int = allSeedTemplates.size

    // ==================== 关联查询方法 ====================

    /**
     * 根据种子 ID 获取对应的长成草药
     *
     * @param seedId 种子 ID
     * @return 长成的草药，不存在则返回 null
     */
    fun getHerbFromSeed(seedId: String): HerbDatabase.Herb? =
        HerbDatabase.getHerbFromSeed(seedId)

    /**
     * 根据种子 ID 获取草药 ID
     *
     * @param seedId 种子 ID
     * @return 草药 ID，不存在则返回 null
     */
    fun getHerbIdFromSeedId(seedId: String): String? =
        seedToHerbMap[seedId]

    // ==================== 随机生成方法 ====================

    /**
     * 随机获取一个草药
     *
     * @param minRarity 最低稀有度（默认1）
     * @param maxRarity 最高稀有度（默认6）
     * @return 随机选中的草药
     */
    fun getRandomHerb(minRarity: Int = 1, maxRarity: Int = 6): HerbDatabase.Herb {
        return HerbDatabase.generateRandomHerb(minRarity, maxRarity)
    }

    /**
     * 随机获取一个种子
     *
     * @param minRarity 最低稀有度（默认1）
     * @param maxRarity 最高稀有度（默认6）
     * @return 随机选中的种子
     */
    fun getRandomSeed(minRarity: Int = 1, maxRarity: Int = 6): HerbDatabase.Seed {
        return HerbDatabase.generateRandomSeed(minRarity, maxRarity)
    }
}
