package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.asKotlinRandom
import kotlin.math.ceil

/**
 * 远古秘境战斗结算辅助——纯函数（丢失物品选取等）。
 */
object SecretRealmBattleHelper {

    /** 丢失物品结算结果 */
    data class LootLossResult(
        val backpack: SecretRealmBackpack,
        val lostItemCount: Int,
        val lostSpiritStones: Long
    )

    /**
     * 战斗失败丢失背包物品：随机比例 20%~45%，件数取整宁多不少（ceil）。
     * 物品按件数 ceil 随机选取丢弃；灵石按同比例 ceil 丢弃。
     */
    fun applyLootLoss(
        backpack: SecretRealmBackpack,
        rng: DeterministicRng
    ): LootLossResult {
        val ratio = GameConfig.SecretRealm.LOOT_LOSS_MIN +
            rng.nextDouble() * (GameConfig.SecretRealm.LOOT_LOSS_MAX - GameConfig.SecretRealm.LOOT_LOSS_MIN)
        val totalItems = backpack.totalItemCount
        val lostItemCount = ceil(totalItems * ratio).toInt()
        val lostSpiritStones = ceil(backpack.spiritStones * ratio).toLong()
        if (lostItemCount == 0 && lostSpiritStones == 0L) {
            return LootLossResult(backpack, 0, 0L)
        }

        // 随机选取 lostItemCount 件物品（索引空间 = 六类列表拼接，与 totalItemCount 一致）
        val lostIndices = (0 until totalItems)
            .shuffled(rng.asKotlinRandom())
            .take(lostItemCount)
            .toSet()

        val (lostE, keptEquipment, cursor1) = collectKept(backpack.equipment, lostIndices, 0)
        val (lostM, keptManuals, cursor2) = collectKept(backpack.manuals, lostIndices, cursor1)
        val (lostP, keptPills, cursor3) = collectKept(backpack.pills, lostIndices, cursor2)
        val (lostMa, keptMaterials, cursor4) = collectKept(backpack.materials, lostIndices, cursor3)
        val (lostH, keptHerbs, cursor5) = collectKept(backpack.herbs, lostIndices, cursor4)
        // 种子与其余五类同规则参与丢失选取（此前缺失导致 seeds 无条件全丢——
        // 对抗性审查发现，且 totalItemCount 索引空间与遍历空间不一致）
        val (lostS, keptSeeds, _) = collectKept(backpack.seeds, lostIndices, cursor5)

        return LootLossResult(
            backpack = SecretRealmBackpack(
                spiritStones = backpack.spiritStones - lostSpiritStones,
                equipment = keptEquipment,
                manuals = keptManuals,
                pills = keptPills,
                materials = keptMaterials,
                herbs = keptHerbs,
                seeds = keptSeeds
            ),
            lostItemCount = lostE + lostM + lostP + lostMa + lostH + lostS,
            lostSpiritStones = lostSpiritStones
        )
    }

    /**
     * 遍历一类物品：命中丢失索引则计数丢弃，否则保留。
     *
     * @return 丢失数 + 保留列表 + 下一个遍历游标（索引空间 = 六类列表拼接）
     */
    private fun <T> collectKept(
        items: List<T>,
        lostIndices: Set<Int>,
        startCursor: Int
    ): Triple<Int, List<T>, Int> {
        var lost = 0
        var cursor = startCursor
        val kept = mutableListOf<T>()
        for (item in items) {
            if (cursor++ in lostIndices) lost++ else kept.add(item)
        }
        return Triple(lost, kept, cursor)
    }
}
