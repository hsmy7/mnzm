package com.xianxia.sect.core.engine.domain.exploration

/**
 * 洞府配置：根据洞府主人境界提供奖励品阶范围。
 */
object CaveGenerator {
    private val realmConfigs = listOf(
        CaveRealmConfig(5, "化神", listOf(3, 4, 5)),
        CaveRealmConfig(4, "炼虚", listOf(3, 4, 5)),
        CaveRealmConfig(3, "合体", listOf(4, 5)),
        CaveRealmConfig(2, "大乘", listOf(4, 5, 6)),
        CaveRealmConfig(1, "渡劫", listOf(5, 6))
    )

    /**
     * 根据洞府主人境界返回奖励品阶范围。
     *
     * @param ownerRealm 洞府主人境界（1~5，1=渡劫…5=化神）
     * @return 品阶范围 [minRarity, maxRarity]，未知境界返回默认 [1, 2, 3]
     */
    fun getRarityRangeForCave(ownerRealm: Int): List<Int> {
        return realmConfigs.find { it.realm == ownerRealm }?.rarityRange ?: listOf(1, 2, 3)
    }

    data class CaveRealmConfig(
        val realm: Int,
        val realmName: String,
        val rarityRange: List<Int>
    )
}
