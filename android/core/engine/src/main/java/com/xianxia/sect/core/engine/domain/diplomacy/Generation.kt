package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.asKotlinRandom

// ── AISectDiscipleManager 拆分域（行为零变更） ──
internal fun AISectDiscipleManager.generateSpiritRoot(): String = SpiritRootGenerator.generate(rng.asKotlinRandom())

/** 初始装备孕养数据（AI 装备从 0 级 0 进度起步，由月度增长温养）。 */

internal fun AISectDiscipleManager.generateInitialNurture(equipmentId: String): EquipmentNurtureData {
    val template = EquipmentDatabase.getById(equipmentId) ?: return EquipmentNurtureData("", 0)
    return EquipmentNurtureData(
        equipmentId = equipmentId,
        rarity = template.rarity,
        nurtureLevel = 0,
        nurtureProgress = 0.0
    )
}

/** 按权重分配境界分布（炼气3/筑基2/金丹2/其余1），余数从高权重境界逐个补足。 */
internal fun AISectDiscipleManager.generateRealmDistribution(total: Int, maxRealm: Int): Map<Int, Int> {
    val distribution = mutableMapOf<Int, Int>()

    val realmRange = (maxRealm + 1)..9
    if (realmRange.isEmpty()) return distribution

    val weights = realmRange.associateWith { realm ->
        when (realm) {
            9 -> 3
            8 -> 2
            7 -> 2
            else -> 1
        }
    }
    val totalWeight = weights.values.sum()

    var assigned = 0
    for (realm in realmRange) {
        val weight = weights[realm] ?: 1
        /** 结构数量（与 [SpriteAtlasDef.STRUCTURES] 同序同量）。 */
        val count = (total * weight / totalWeight)
        distribution[realm] = count
        assigned += count
    }

    var remaining = total - assigned
    if (remaining > 0) {
        val sortedRealms = realmRange.sortedByDescending { weights[it] ?: 1 }
        for (realm in sortedRealms) {
            if (remaining <= 0) break
            distribution[realm] = (distribution[realm] ?: 0) + 1
            remaining--
        }
    }

    return distribution
}


internal fun AISectDiscipleManager.adjustDiscipleRealm(disciple: Disciple, targetRealm: Int): Disciple {
    if (targetRealm == 9) return disciple

    val maxLayer = GameConfig.Realm.get(targetRealm).maxLayers

    return disciple.copy(
        realm = targetRealm,
        realmLayer = 1 + rng.nextInt(maxLayer),
        cultivation = rng.nextDouble() * 0.8 * GameConfig.Realm.get(targetRealm).cultivationBase
    )
}
