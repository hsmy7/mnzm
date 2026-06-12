package com.xianxia.sect.core.config

/**
 * 送礼系统配置
 * 包含灵石送礼档位、稀有度好感度、宗门拒绝概率等配置
 */
object GiftConfig {

    /**
     * 灵石送礼档位配置
     * @param tier 档位等级 (1=薄礼, 2=厚礼, 3=重礼, 4=大礼)
     * @param name 档位名称
     * @param spiritStones 所需灵石数量
     */
    data class SpiritStoneGiftTier(
        val tier: Int,
        val name: String,
        val spiritStones: Long,
        val baseFavor: Int = 0
    )

    /**
     * 灵石送礼档位配置列表
     */
    object SpiritStoneGiftConfig {
        val TIERS = mapOf(
            1 to SpiritStoneGiftTier(1, "薄礼", 20000L, 2),
            2 to SpiritStoneGiftTier(2, "厚礼", 200000L, 5),
            3 to SpiritStoneGiftTier(3, "重礼", 800000L, 10),
            4 to SpiritStoneGiftTier(4, "大礼", 4000000L, 15)
        )

        fun getTier(tier: Int): SpiritStoneGiftTier? = TIERS[tier]

        fun getAllTiers(): List<SpiritStoneGiftTier> = TIERS.values.toList()
    }

    /**
     * 好感度百分比配置
     * 根据宗门等级和送礼档位计算好感度增长百分比
     */
    object FavorPercentageConfig {
        private val PERCENTAGE_MATRIX = mapOf(
            0 to mapOf(
                1 to 20,
                2 to 40,
                3 to 100,
                4 to 200
            ),
            1 to mapOf(
                1 to 10,
                2 to 30,
                3 to 70,
                4 to 150
            ),
            2 to mapOf(
                3 to 40,
                4 to 70
            ),
            3 to mapOf(
                3 to 30,
                4 to 50
            )
        )

        fun getFavorPercentage(sectLevel: Int, tier: Int): Int? {
            return PERCENTAGE_MATRIX[sectLevel]?.get(tier)
        }

        fun isTierAvailableForSect(sectLevel: Int, tier: Int): Boolean {
            return PERCENTAGE_MATRIX[sectLevel]?.containsKey(tier) == true
        }
    }

    /**
     * 稀有度基础好感度配置
     * @param rarity 稀有度等级 (1=凡品, 2=灵品, 3=宝品, 4=玄品, 5=地品, 6=天品)
     * @param baseFavor 基础好感度
     */
    data class RarityFavor(
        val rarity: Int,
        val baseFavor: Int = 0
    )

    /**
     * 稀有度好感度配置
     */
    object RarityFavorConfig {
        val CONFIGS = mapOf(
            1 to RarityFavor(1, 1),
            2 to RarityFavor(2, 2),
            3 to RarityFavor(3, 5),
            4 to RarityFavor(4, 8),
            5 to RarityFavor(5, 12),
            6 to RarityFavor(6, 15)
        )

        fun getBaseFavor(rarity: Int): Int = CONFIGS[rarity]?.baseFavor ?: 1
    }

    /**
     * 物品送礼好感度百分比配置
     * 根据宗门等级和物品稀有度计算好感度百分比
     */
    object ItemFavorPercentageConfig {
        private val PERCENTAGE_MATRIX = mapOf(
            0 to mapOf(
                1 to 20,
                2 to 40,
                3 to 70,
                4 to 140,
                5 to 200,
                6 to 300
            ),
            1 to mapOf(
                1 to 10,
                2 to 20,
                3 to 40,
                4 to 80,
                5 to 150,
                6 to 220
            ),
            2 to mapOf(
                4 to 30,
                5 to 60,
                6 to 130
            ),
            3 to mapOf(
                4 to 20,
                5 to 50,
                6 to 80
            )
        )

        /**
         * 获取好感度百分比
         * @param sectLevel 宗门等级 (0-3)
         * @param rarity 物品稀有度 (1-6)
         * @return 好感度百分比，如果配置不存在则返回null（表示该稀有度会被拒绝）
         */
        fun getFavorPercentage(sectLevel: Int, rarity: Int): Int? {
            val levelConfig = PERCENTAGE_MATRIX[sectLevel] ?: return null
            return levelConfig[rarity]
        }
    }

    /**
     * 宗门拒绝概率配置
     * @param sectLevel 宗门等级 (0=小型, 1=中型, 2=大型, 3=顶级)
     * @param rarity 物品稀有度
     * @param rejectProbability 拒绝概率 (0-100)
     */
    data class SectRejectProbability(
        val sectLevel: Int,
        val rarity: Int,
        val rejectProbability: Int
    )

    /**
     * 宗门拒绝概率配置
     * 根据宗门等级和物品稀有度决定拒绝概率
     */
    object SectRejectConfig {
        // 拒绝概率矩阵：key为宗门等级，value为稀有度到拒绝概率的映射
        private val REJECT_MATRIX = mapOf(
            // 小型宗门(level=0): 凡品50%, 灵品20%, 宝品及以上0%
            0 to mapOf(
                1 to 50,  // 凡品
                2 to 20,  // 灵品
                3 to 0,   // 宝品
                4 to 0,   // 玄品
                5 to 0,   // 地品
                6 to 0    // 天品
            ),
            // 中型宗门(level=1): 凡品70%, 灵品50%, 宝品30%, 玄品及以上0%
            1 to mapOf(
                1 to 70,  // 凡品
                2 to 50,  // 灵品
                3 to 30,  // 宝品
                4 to 0,   // 玄品
                5 to 0,   // 地品
                6 to 0    // 天品
            ),
            // 大型宗门(level=2): 凡品/灵品/宝品100%, 玄品30%, 地品10%, 天品0%
            2 to mapOf(
                1 to 100, // 凡品
                2 to 100, // 灵品
                3 to 100, // 宝品
                4 to 30,  // 玄品
                5 to 10,  // 地品
                6 to 0    // 天品
            ),
            // 顶级宗门(level=3): 凡品/灵品/宝品100%, 玄品50%, 地品20%, 天品0%
            3 to mapOf(
                1 to 100, // 凡品
                2 to 100, // 灵品
                3 to 100, // 宝品
                4 to 50,  // 玄品
                5 to 20,  // 地品
                6 to 0    // 天品
            )
        )

        /**
         * 获取拒绝概率
         * @param sectLevel 宗门等级 (0-3)
         * @param rarity 物品稀有度 (1-6)
         * @return 拒绝概率 (0-100)
         */
        fun getRejectProbability(sectLevel: Int, rarity: Int): Int {
            val levelConfig = REJECT_MATRIX[sectLevel] ?: REJECT_MATRIX[0] ?: emptyMap()
            return levelConfig[rarity] ?: 0
        }

        /**
         * 获取宗门等级名称
         */
        fun getSectLevelName(sectLevel: Int): String = when (sectLevel) {
            0 -> "小型宗门"
            1 -> "中型宗门"
            2 -> "大型宗门"
            3 -> "顶级宗门"
            else -> "未知宗门"
        }
    }

    /**
     * 物品类型常量
     */
    object ItemType {
        const val MANUAL = "manual"      // 功法
        const val EQUIPMENT = "equipment" // 装备
        const val PILL = "pill"          // 丹药
    }
}
