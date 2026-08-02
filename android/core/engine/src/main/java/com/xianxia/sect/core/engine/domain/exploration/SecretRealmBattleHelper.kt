package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
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

        // 随机选取 lostItemCount 件物品（索引空间 = 五类列表拼接）
        val lostIndices = (0 until totalItems)
            .shuffled(rng.asKotlinRandom())
            .take(lostItemCount)
            .toSet()

        var cursor = 0
        var lost = 0
        val keptEquipment = mutableListOf<EquipmentStack>()
        val keptManuals = mutableListOf<ManualStack>()
        val keptPills = mutableListOf<Pill>()
        val keptMaterials = mutableListOf<Material>()
        val keptHerbs = mutableListOf<Herb>()

        for (item in backpack.equipment) {
            if (cursor++ in lostIndices) lost++ else keptEquipment.add(item)
        }
        for (item in backpack.manuals) {
            if (cursor++ in lostIndices) lost++ else keptManuals.add(item)
        }
        for (item in backpack.pills) {
            if (cursor++ in lostIndices) lost++ else keptPills.add(item)
        }
        for (item in backpack.materials) {
            if (cursor++ in lostIndices) lost++ else keptMaterials.add(item)
        }
        for (item in backpack.herbs) {
            if (cursor++ in lostIndices) lost++ else keptHerbs.add(item)
        }

        return LootLossResult(
            backpack = SecretRealmBackpack(
                spiritStones = backpack.spiritStones - lostSpiritStones,
                equipment = keptEquipment,
                manuals = keptManuals,
                pills = keptPills,
                materials = keptMaterials,
                herbs = keptHerbs
            ),
            lostItemCount = lost,
            lostSpiritStones = lostSpiritStones
        )
    }
}
