package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.PillEffects
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.BreakthroughZoneBonusInput
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.BreakthroughZones
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.CultivationRateColumnInput
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.CultivationSpeedZones
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.CultivationZoneInput
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.HpMpColumnInput

// ── DiscipleStatCalculator 拆分域 4/6（行为零变更） ──
fun DiscipleStatCalculator.getFinalStats(
    disciple: Disciple,
    equipments: Map<String, EquipmentInstance>,
    manuals: Map<String, ManualInstance>,
    manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
): DiscipleStats {
    val pe = disciple.pillEffects
    return computeFinalStats(
        baseStats = getBaseStats(disciple),
        equipmentIds = disciple.equipment.equippedItemIds,
        manualIds = disciple.manualIds,
        equipments = equipments,
        manuals = manuals,
        manualProficiencies = manualProficiencies,
        pillEffects = pe
    )
}

// ==================== 列直读 maxHp/maxMp（每旬热点） ====================

/**
 * 列直读 maxHp/maxMp 计算（每旬热点专用，无 Disciple 组装）。
 *
 * 基础值走 [computeBaseHpMp]（与对象版同一实现）；装备/功法/丹药加成
 * 与 [computeFinalStats] 的 hp/mp 逻辑逐字一致。
 *
 * @param input 列直读输入
 * @param equipments 装备实例映射（每旬热点循环共享构建）
 * @param manuals 功法实例映射（每旬热点循环共享构建）
 * @param manualProficiencies 功法熟练度（按 manualId 索引）
 * @return (maxHp, maxMp)
 */

fun DiscipleStatCalculator.getMaxHpMpColumn(
    input: HpMpColumnInput,
    equipments: Map<String, EquipmentInstance>,
    manuals: Map<String, ManualInstance>,
    manualProficiencies: Map<String, ManualProficiencyData>
): Pair<Int, Int> {
    val (baseHp, baseMp) = computeBaseHpMp(
        input.realm, input.realmLayer, input.hpVariance, input.mpVariance
    )
    var hp = baseHp
    var mp = baseMp

    // 装备（四部位：槽位实例 flat HP 求和 + 套装档位；与 EquipStatResolver 一字一致）
    val equippedInstances = listOfNotNull(
        input.headId, input.bodyId, input.handsId, input.feetId
    ).mapNotNull { equipments[it] }
    val equipBonus = EquipStatResolver.resolve(equippedInstances)
    hp += equipBonus.flatHp.toInt()

    // 功法（熟练度加成与 computeFinalStats 一致）
    input.manualIds.forEach { manualId ->
        val manual = manuals[manualId]
        if (manual != null) {
            val masteryLevel = manualProficiencies[manualId]?.masteryLevel ?: 0
            val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
            val hpValue = manual.stats["hp"] ?: manual.stats["maxHp"] ?: 0
            val mpValue = manual.stats["mp"] ?: manual.stats["maxMp"] ?: 0
            hp += (hpValue * masteryBonus).toInt()
            mp += (mpValue * masteryBonus).toInt()
        }
    }

    // 丹药（与 computeFinalStats 的 hasPillEffect 判定一致）
    if (input.pillEffectDuration > 0) {
        hp += input.pillHpBonus
        mp += input.pillMpBonus
    }
    return Pair(hp, mp)
}

fun DiscipleStatCalculator.getFinalStats(
    aggregate: DiscipleAggregate,
    equipments: Map<String, EquipmentInstance>,
    manuals: Map<String, ManualInstance>,
    manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
): DiscipleStats {
    val eq = aggregate.equipment
    val cs = aggregate.combatStats
    return computeFinalStats(
        baseStats = getBaseStats(aggregate),
        equipmentIds = eq?.equippedItemIds ?: emptyList(),
        manualIds = aggregate.manualIds,
        equipments = equipments,
        manuals = manuals,
        manualProficiencies = manualProficiencies,
        pillEffects = cs?.let {
            PillEffects(
                pillEffectDuration = it.pillEffectDuration,
                pillHpBonus = it.pillHpBonus,
                pillMpBonus = it.pillMpBonus,
                pillAttackBonus = it.pillAttackBonus,
                pillDefenseBonus = it.pillDefenseBonus,
                pillSpeedBonus = it.pillSpeedBonus,
                pillCritRateBonus = it.pillCritRateBonus
            )
        } ?: PillEffects()
    )
}

/**
 * 计算每旬修炼值（乘区制核心公式）。
 *
 * 公式：基础速度 × Π(1 + 各乘区加算和)
 * 乘区相乘顺序固定为 资源→社交→状态→临时→星级（C++ `disciple.h` 同序）。
 *
 * @param realm 弟子境界（0=仙人 … 9=炼气）
 * @param spiritRootCount 灵根数量（1-5）
 * @param zones 各乘区加算值分组
 * @return 每旬修炼值，最低 1.0
 */
// ==================== 修炼速度乘区 ====================


fun DiscipleStatCalculator.calculateCultivationPerPhase(
    realm: Int,
    spiritRootCount: Int,
    zones: CultivationSpeedZones
): Double {
    val rootCount = spiritRootCount.coerceAtLeast(1)
    val base = GameConfig.Cultivation.getRealmPerPhase(realm) / rootCount.toDouble()
    return (base
        * (1.0 + zones.resourceBonus)
        * (1.0 + zones.socialBonus)
        * (1.0 + zones.statusBonus)
        * (1.0 + zones.temporaryBonus)
        * (1.0 + zones.starBonus)
    ).coerceAtLeast(MIN_CULTIVATION_PER_PHASE)
}


internal fun DiscipleStatCalculator.computeCultivationZones(
    input: CultivationZoneInput
): CultivationSpeedZones {
    // ── 资源乘区：功法 + 建筑 ──
    var resourceBonus = (input.buildingBonus - 1.0)
    if (input.manuals.isNotEmpty()) {
        input.manualIds.forEach { manualId ->
            val manual = input.manuals[manualId] ?: return@forEach
            val masteryLevel = input.manualProficiencies[manualId]?.masteryLevel ?: 0
            val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
            resourceBonus += manual.cultivationSpeedPercent * masteryBonus / 100.0
        }
    } else if (input.manualIds.isNotEmpty()) {
        // 兜底路径：调用方未传 manuals 实例映射时，从 ManualDatabase 静态查询
        // 影响：不支持动态实例属性（如孕养等级），但用于 UI 显示预览足够
        input.manualIds.forEach { manualId ->
            val manual = ManualDatabase.getById(manualId) ?: return@forEach
            val masteryLevel = input.manualProficiencies[manualId]?.masteryLevel ?: 0
            val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
            resourceBonus += (manual.stats["cultivationSpeedPercent"] ?: 0) * masteryBonus / 100.0
        }
    }

    // ── 社交乘区：传道长老/师兄 ──
    val socialBonus = input.preachingElderBonus + input.preachingMastersBonus

    // ── 状态乘区：政策 ──
    val statusBonus = input.cultivationSubsidyBonus

    return CultivationSpeedZones(
        resourceBonus = resourceBonus,
        socialBonus = socialBonus,
        statusBonus = statusBonus,
        temporaryBonus = input.temporaryBonus,
        starBonus = input.starBonus
    )
}

/**
 * 组装修炼速度乘区（从 Disciple 对象提取各加成）。
 */

fun DiscipleStatCalculator.buildCultivationZones(
    disciple: Disciple,
    manuals: Map<String, ManualInstance> = emptyMap(),
    manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
    buildingBonus: Double = 1.0,
    preachingElderBonus: Double = 0.0,
    preachingMastersBonus: Double = 0.0,
    cultivationSubsidyBonus: Double = 0.0,
    starBonus: Double = 0.0
): CultivationSpeedZones {
    // 丹药修炼速度加成统一收敛于 pillEffects 体系（旧
    // cultivationSpeedBonus 顶层字段不再参与累加，防止同颗丹药双字段双倍生效）
    var temporaryBonus = 0.0
    if (disciple.pillEffects.pillEffectDuration > 0 && disciple.pillEffects.pillCultivationSpeedBonus > 0.0) {
        temporaryBonus += disciple.pillEffects.pillCultivationSpeedBonus
    }
    return computeCultivationZones(
        CultivationZoneInput(
            manualIds = disciple.manualIds,
            manuals = manuals,
            manualProficiencies = manualProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = preachingElderBonus,
            preachingMastersBonus = preachingMastersBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus,
            temporaryBonus = temporaryBonus,
            starBonus = starBonus
        )
    )
}

/**
 * 组装修炼速度乘区（从 DiscipleAggregate 对象提取各加成）。
 */

fun DiscipleStatCalculator.buildCultivationZones(
    aggregate: DiscipleAggregate,
    manuals: Map<String, ManualInstance> = emptyMap(),
    manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
    buildingBonus: Double = 1.0,
    preachingElderBonus: Double = 0.0,
    preachingMastersBonus: Double = 0.0,
    cultivationSubsidyBonus: Double = 0.0,
    starBonus: Double = 0.0
): CultivationSpeedZones {
    // 丹药修炼速度加成统一收敛于 pillEffects 体系（同 Disciple 版，
    // 旧 cultivationSpeedBonus 顶层字段不再参与累加，防双倍生效）
    var temporaryBonus = 0.0
    val ext = aggregate.extended
    if (ext != null && ext.pillEffectDuration > 0 && ext.pillCultivationSpeedBonus > 0.0) {
        temporaryBonus += ext.pillCultivationSpeedBonus
    }
    return computeCultivationZones(
        CultivationZoneInput(
            manualIds = aggregate.manualIds,
            manuals = manuals,
            manualProficiencies = manualProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = preachingElderBonus,
            preachingMastersBonus = preachingMastersBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus,
            temporaryBonus = temporaryBonus,
            starBonus = starBonus
        )
    )
}

// ==================== 修炼乘区便捷计算（公共） ====================

/**
 * 使用乘区制计算每旬修炼值（列式直读版本，无 Disciple 组装）。
 *
 * 与 [calculateCultivationPerPhase]（Disciple 版本）语义等价：
 * 从 [CultivationRateColumnInput] 提取原始字段，复用同一套乘区计算。
 * 供每旬热点循环（accumulateCultivationPerPhase）使用。
 */

fun DiscipleStatCalculator.calculateCultivationPerPhaseColumn(
    input: CultivationRateColumnInput,
    manuals: Map<String, ManualInstance> = emptyMap(),
    manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
    buildingBonus: Double = 1.0,
    preachingElderBonus: Double = 0.0,
    preachingMastersBonus: Double = 0.0,
    cultivationSubsidyBonus: Double = 0.0,
    starBonus: Double = 0.0
): Double {
    // 丹药修炼速度加成统一收敛于 pillEffects 体系（同 Disciple 版，
    // 旧 cultivationSpeedBonus 顶层字段不再参与累加，防双倍生效）
    var temporaryBonus = 0.0
    if (input.pillEffectDuration > 0 && input.pillCultivationSpeedBonus > 0.0) {
        temporaryBonus += input.pillCultivationSpeedBonus
    }
    val zones = computeCultivationZones(
        CultivationZoneInput(
            manualIds = input.manualIds,
            manuals = manuals,
            manualProficiencies = manualProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = preachingElderBonus,
            preachingMastersBonus = preachingMastersBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus,
            temporaryBonus = temporaryBonus,
            starBonus = starBonus
        )
    )
    return calculateCultivationPerPhase(input.realm, input.spiritRootCount, zones)
}

/**
 * 使用乘区制计算每旬修炼值（Disciple 版本便捷入口）。
 */

fun DiscipleStatCalculator.calculateCultivationPerPhase(
    disciple: Disciple,
    manuals: Map<String, ManualInstance> = emptyMap(),
    manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
    buildingBonus: Double = 1.0,
    preachingElderBonus: Double = 0.0,
    preachingMastersBonus: Double = 0.0,
    cultivationSubsidyBonus: Double = 0.0,
    starBonus: Double = 0.0
): Double {
    val zones = buildCultivationZones(
        disciple, manuals, manualProficiencies,
        buildingBonus, preachingElderBonus, preachingMastersBonus,
        cultivationSubsidyBonus, starBonus
    )
    return calculateCultivationPerPhase(disciple.realm, disciple.spiritRoot.types.size, zones)
}

/**
 * 使用乘区制计算每旬修炼值（DiscipleAggregate 版本便捷入口）。
 */

fun DiscipleStatCalculator.calculateCultivationPerPhase(
    aggregate: DiscipleAggregate,
    manuals: Map<String, ManualInstance> = emptyMap(),
    manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
    buildingBonus: Double = 1.0,
    preachingElderBonus: Double = 0.0,
    preachingMastersBonus: Double = 0.0,
    cultivationSubsidyBonus: Double = 0.0,
    starBonus: Double = 0.0
): Double {
    val zones = buildCultivationZones(
        aggregate, manuals, manualProficiencies,
        buildingBonus, preachingElderBonus, preachingMastersBonus,
        cultivationSubsidyBonus, starBonus
    )
    return calculateCultivationPerPhase(aggregate.realm, aggregate.spiritRoot.types.size, zones)
}

// ==================== 突破概率乘区 ====================


internal fun DiscipleStatCalculator.computeBreakthroughZones(
    realm: Int,
    realmLayer: Int,
    spiritRootCount: Int,
    bonuses: BreakthroughZoneBonusInput
): BreakthroughZones {
    val baseZone = GameConfig.Realm.getBreakthroughChance(realm, spiritRootCount, realmLayer)
    // 长老职能效果 × (1 + 职务乘算因子)：因子由组装点传入（当前恒 0）
    val innerElderBonus = comprehensionBreakthroughBonus(bonuses.innerElderComprehension) *
        (1.0 + bonuses.innerElderPositionBonus)
    val outerElderBonus = comprehensionBreakthroughBonus(bonuses.outerElderComprehension) *
        (1.0 + bonuses.outerElderPositionBonus)

    return BreakthroughZones(
        baseZone = baseZone,
        elderGuidance = innerElderBonus + outerElderBonus,
        selfBonus = bonuses.pillBonus +
            comprehensionBreakthroughBonus(bonuses.selfComprehension),
        adFlatBonus = bonuses.adBonus
    )
}

/**
 * 构建突破概率乘区（从 Disciple 对象提取各加成）。
 */

fun DiscipleStatCalculator.buildBreakthroughZones(
    disciple: Disciple,
    innerElderComprehension: Int = 0,
    outerElderComprehension: Int = 0,
    pillBonus: Double = 0.0,
    adBonus: Double = 0.0
): BreakthroughZones = computeBreakthroughZones(
    realm = disciple.realm,
    realmLayer = disciple.realmLayer,
    spiritRootCount = disciple.spiritRoot.types.size,
    bonuses = BreakthroughZoneBonusInput(
        innerElderComprehension = innerElderComprehension,
        outerElderComprehension = outerElderComprehension,
        selfComprehension = disciple.getBaseStats().comprehension,
        pillBonus = pillBonus,
        adBonus = adBonus,
        // 职务乘算因子：特质（天赋/词条）数值源已下线，恒为 0
        innerElderPositionBonus = 0.0,
        outerElderPositionBonus = 0.0
    )
)

/**
 * 构建突破概率乘区（从 DiscipleAggregate 对象提取各加成）。
 */

fun DiscipleStatCalculator.buildBreakthroughZones(
    aggregate: DiscipleAggregate,
    innerElderComprehension: Int = 0,
    outerElderComprehension: Int = 0,
    pillBonus: Double = 0.0,
    adBonus: Double = 0.0
): BreakthroughZones = computeBreakthroughZones(
    realm = aggregate.realm,
    realmLayer = aggregate.realmLayer,
    spiritRootCount = aggregate.spiritRoot.types.size,
    bonuses = BreakthroughZoneBonusInput(
        innerElderComprehension = innerElderComprehension,
        outerElderComprehension = outerElderComprehension,
        selfComprehension = aggregate.getBaseStats().comprehension,
        pillBonus = pillBonus,
        adBonus = adBonus,
        // 职务乘算因子：特质（天赋/词条）数值源已下线，恒为 0
        innerElderPositionBonus = 0.0,
        outerElderPositionBonus = 0.0
    )
)

/**
 * 使用乘区法计算最终突破概率。
 *
 * 公式：baseZone × (1 + elderGuidance + selfBonus) + adFlatBonus
 * 结果 clamp 到 [0, 1]。
 */
