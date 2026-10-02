package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.domain.disciple.calculateCultivationPerPhase
import com.xianxia.sect.core.engine.domain.disciple.getBreakthroughChance

// ── AISectDiscipleManager 拆分域（行为零变更） ──

/** 按境界上限品阶从槽位条目池选取装备条目（无该品阶时取槽位最高品阶兜底）。 */

internal fun AISectDiscipleManager.pickEquipmentTemplate(
    slot: EquipmentSlot,
    maxRarity: Int
): EquipmentDatabase.EquipPieceEntry? {
    val allSlotTemplates = EquipmentDatabase.getBySlot(slot)
    return if (allSlotTemplates.isEmpty()) {
        null
    } else {
        val exact = allSlotTemplates.filter { it.rarity == maxRarity }
        if (exact.isNotEmpty()) {
            exact[rng.nextInt(exact.size)]
        } else {
            allSlotTemplates.maxByOrNull { it.rarity }
        }
    }
}

/** 随机选取 [count] 个装备槽位并生成境界上限品阶装备条目 id，返回 槽位 → 条目 id 映射。 */

internal fun AISectDiscipleManager.generateEquipmentIds(maxRarity: Int, count: Int): Map<EquipmentSlot, String> {
    if (count <= 0) return emptyMap()
    val slots = EquipmentSlot.values().toList()
        .shuffled(java.util.Random(rng.nextInt().toLong()))
        .take(count)
    return slots.mapNotNull { slot ->
        pickEquipmentTemplate(slot, maxRarity)?.let { Pair(slot, it.id) }
    }.toMap()
}

/**
 * 生成 [count] 本功法（模板 id + 初始熟练度）。
 *
 * 攻+防池随机选取；[includeMind] 为 true 时恒带 1 本心法（其余攻防补足），
 * 品阶过滤恒等于境界上限品阶。初始熟练度恒为 0（与玩家"刚学功法"NOVICE 一致），
 * 由月度增长（[applyMonthlyProficiencyGain]）随时间提升。
 *
 * @param includeMind 是否强制包含 1 本心法（新弟子必带；补全路径传 false 避免已有心法重复）
 */

internal fun AISectDiscipleManager.generateManuals(
    maxRarity: Int, count: Int, includeMind: Boolean = true
): List<Pair<String, Int>> {
    val attackManuals = ManualDatabase.getByType(ManualType.ATTACK)
        .filter { it.rarity == maxRarity }
    val defenseManuals = ManualDatabase.getByType(ManualType.DEFENSE)
        .filter { it.rarity == maxRarity }
    val mindManuals = ManualDatabase.getByType(ManualType.MIND)
        .filter { it.rarity == maxRarity }

    val nonMindManuals = (attackManuals + defenseManuals)
        .shuffled(java.util.Random(rng.nextInt().toLong()))
    val selectedMind = if (includeMind && mindManuals.isNotEmpty()) {
        listOf(mindManuals[rng.nextInt(mindManuals.size)])
    } else emptyList()

    val remainingCount = (count - selectedMind.size).coerceAtLeast(0)
    val selected = selectedMind + nonMindManuals.take(remainingCount)

    return selected.map { manual -> Pair(manual.id, 0) }
}

/** 将装备 id 写入指定槽位（返回新副本，不改原对象）。 */

internal fun EquipmentSet.withEquipped(slot: EquipmentSlot, id: String): EquipmentSet = when (slot) {
    EquipmentSlot.HEAD -> copy(headId = id)
    EquipmentSlot.BODY -> copy(bodyId = id)
    EquipmentSlot.HANDS -> copy(handsId = id)
    EquipmentSlot.FEET -> copy(feetId = id)
}

/**
 * 单月修炼结算：修炼加速（吃功法/体质/词条加成）→ 突破判定（完整乘区）→
 * 大境界突破成功时按新境界刷新装备/功法（永远最高可用品阶）。
 */

internal fun AISectDiscipleManager.settleMonthlyCultivation(disciple: Disciple, sectLevel: Int): Disciple {
    val cultivationSpeed = DiscipleStatCalculator.calculateCultivationPerPhase(
        disciple,
        manuals = emptyMap(),
        manualProficiencies = buildProficiencyDataFromMasteries(disciple),
        buildingBonus = 1.0,
        preachingElderBonus = 0.0,
        preachingMastersBonus = 0.0,
        cultivationSubsidyBonus = 0.0
    )
    // NaN/Infinity/负数防御：损坏存档修为异常时归零，避免永久卡死与存档污染
    val baseCultivation = disciple.cultivation
        .takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    // 速率结果防御：DB 配置损坏（功法 cultivationSpeedPercent 为 NaN 等）时归零
    val safeSpeed = cultivationSpeed.takeIf { it.isFinite() } ?: 0.0
    var working = disciple.copy(
        cultivation = baseCultivation + safeSpeed * PHASES_PER_MONTH
    )

    while (working.cultivation >= working.maxCultivation && working.realm > 0) {
        val breakthroughChance = DiscipleStatCalculator.getBreakthroughChance(working)
        if (rng.nextDouble() >= breakthroughChance) {
            working = applyBreakthroughFailure(working)
            break
        }
        working = applyBreakthroughSuccess(working)
    }

    // 大境界变化且品阶上限提升时才刷新装备/功法——品阶不变的
    // 突破若重刷会清空已有熟练度积累，无收益只有损失
    return if (working.realm != disciple.realm &&
        GameConfig.Realm.getMaxRarity(working.realm) >
        GameConfig.Realm.getMaxRarity(disciple.realm)
    ) {
        applyGearToDisciple(working, sectLevel)
    } else {
        working
    }
}

/** 突破成功：修为清零、层数+1 或大境界+1（对齐玩家 applyBreakthroughSuccess）。 */

internal fun AISectDiscipleManager.applyBreakthroughSuccess(d: Disciple): Disciple {
    var working = d.copy(cultivation = 0.0)
    working = if (working.realmLayer < GameConfig.Realm.get(working.realm).maxLayers) {
        working.copy(realmLayer = working.realmLayer + 1)
    } else {
        working.copy(realm = working.realm - 1, realmLayer = 1)
    }
    return working
}

/**
 * 突破失败：修为清零 + HP/MP 打一折（对齐玩家 applyBreakthroughFailure）。
 *
 * AI 弟子无持续战斗资源状态（currentHp 恒为 -1 满血语义，战斗全恢复且不回写），
 * 此时跳过 HP/MP 惩罚，避免向存档写入 10% 血量的失真值并随俘虏流入玩家池。
 */

internal fun AISectDiscipleManager.applyBreakthroughFailure(d: Disciple): Disciple {
    val hasRealHpState = d.combat.currentHp >= 0 || d.combat.currentMp >= 0
    if (!hasRealHpState) return d.copy(cultivation = 0.0)
    val curHp = if (d.combat.currentHp < 0) d.maxHp else d.combat.currentHp
    val curMp = if (d.combat.currentMp < 0) d.maxMp else d.combat.currentMp
    return d.copy(
        cultivation = 0.0,
        combat = d.combat.copy(
            currentHp = (curHp * DiscipleStatCalculator.BREAKTHROUGH_FAILURE_HP_MP_RATIO)
                .toInt().coerceAtLeast(1),
            currentMp = (curMp * DiscipleStatCalculator.BREAKTHROUGH_FAILURE_HP_MP_RATIO)
                .toInt().coerceAtLeast(1)
        )
    )
}

/**
 * 功法熟练度月度等效增长（对齐玩家每旬公式，1 月 = 3 旬）。
 * AI 无藏经阁建筑 → libraryBonus = 0；上限 MAX_PROFICIENCY；
 * 只保留 manualIds 中的键，清理残留/孤儿熟练度条目（防存档冗余累积）。
 */

internal fun AISectDiscipleManager.applyMonthlyProficiencyGain(disciple: Disciple): Disciple {
    if (!ManualDatabase.isInitialized || disciple.manualIds.isEmpty()) return disciple
    val perMonthGain = ManualProficiencySystem.calculateProficiencyGainPerPhase(
        libraryBonus = 0.0
    ) * PHASES_PER_MONTH
    val validIds = disciple.manualIds.toSet()
    val updated = disciple.manualMasteries
        .filterKeys { it in validIds }
        .mapValues { (mId, mastery) ->
            if (ManualDatabase.getById(mId) == null) {
                mastery
            } else {
                // 防御篡改：负数熟练度钳 0（负值会驻留存档并长期污染），上界不变
                (mastery + perMonthGain).toInt()
                    .coerceIn(0, ManualProficiencySystem.MAX_PROFICIENCY.toInt())
            }
        }
    return disciple.copy(manualMasteries = updated)
}
