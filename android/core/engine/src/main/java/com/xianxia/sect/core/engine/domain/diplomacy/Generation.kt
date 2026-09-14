package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.asKotlinRandom
import com.xianxia.sect.core.engine.avoidSentinel50

// ── AISectDiscipleManager 拆分域（行为零变更） ──
internal fun AISectDiscipleManager.generateSpiritRoot(): String = SpiritRootGenerator.generate(rng.asKotlinRandom())

/** 资质按灵根阶梯生成（与悟性阶梯一致，上界 200），并避开哨兵值 50 防自愈误判 */

internal fun AISectDiscipleManager.rollAptitudeByRootCount(spiritRootCount: Int): Int =
    avoidSentinel50(
        when (spiritRootCount) {
            1 -> 80 + rng.nextInt(21)
            2 -> 60 + rng.nextInt(21)
            3 -> 40 + rng.nextInt(21)
            4 -> 20 + rng.nextInt(21)
            else -> 1 + rng.nextInt(20)
        }
    )

/** 资质生成避开哨兵值 50（==50 强制 +1）：与 [DiscipleTables.healDefaultAptitudes] 收敛逻辑一致，防自愈误判 */

internal fun AISectDiscipleManager.avoidSentinel50(roll: Int): Int =
    if (roll == DiscipleTables.DEFAULT_APTITUDE) DiscipleTables.DEFAULT_APTITUDE + 1 else roll

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

internal fun AISectDiscipleManager.generateQiRefiningDisciple(
    sectName: String,
    existingNames: Set<String>,
    sectLevel: Int
): Disciple {
    return applyGearToDisciple(generateRandomDisciple(sectName, existingNames), sectLevel)
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

    val baseLifespan = GameConfig.Realm.get(targetRealm).maxAge
    val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
    val lifespanBonus = talentEffects["lifespan"] ?: 0.0
    val newLifespan = (baseLifespan * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)
    val maxLayer = GameConfig.Realm.get(targetRealm).maxLayers

    return disciple.copy(
        realm = targetRealm,
        realmLayer = 1 + rng.nextInt(maxLayer),
        cultivation = rng.nextDouble() * 0.8 * GameConfig.Realm.get(targetRealm).cultivationBase,
        lifespan = newLifespan,
        // 高境界配合理年龄（防"38岁炼虚"类数据；炼气 realm=9 不调整）
        age = maxOf(disciple.age, GameConfig.Realm.minReasonableAge(targetRealm))
    )
}
