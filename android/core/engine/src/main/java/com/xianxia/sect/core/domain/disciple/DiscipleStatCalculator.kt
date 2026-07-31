package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.PhysiqueEffects
import com.xianxia.sect.core.registry.AffixCombatEffects
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.engine.ManualProficiencySystem
import kotlin.math.roundToInt

object DiscipleStatCalculator {
    // ---- 魔法数字命名常量 ----
    private const val LAYER_MULTIPLIER = 0.1
    private const val BASE_CRIT_RATE = 0.05
    private const val MIN_CULTIVATION_PER_PHASE = 1.0
    private const val BASE_MANUAL_SLOTS = 6
    private const val ELDER_BONUS_PER_STEP = 0.01
    private const val SOUL_POWER_DIVISOR = 20
    private const val SOUL_POWER_MAX_STEPS = 5
    private const val ELDER_TEACHING_BASELINE = 80
    private const val MASTER_TEACHING_BASELINE = 60
    private const val ELDER_TEACHING_RATE = 0.0025
    private const val MASTER_TEACHING_RATE = 0.001
    private const val ELDER_TEACHING_MAX_BONUS = 0.10
    private const val MASTER_TEACHING_MAX_BONUS = 0.05

    // ==================== 天赋效果 ====================

    private fun computeTalentEffects(talentIds: List<String>): Map<String, Double> {
        val effects = mutableMapOf<String, Double>()
        val talents = TalentDatabase.getTalentsByIds(talentIds)
        talents.forEach { talent ->
            talent.effects.forEach { (key, value) ->
                effects[key] = (effects[key] ?: 0.0) + value
            }
        }
        return effects
    }

    fun getTalentEffects(disciple: Disciple): Map<String, Double> =
        computeTalentEffects(disciple.talentIds)

    fun getTalentEffects(aggregate: DiscipleAggregate): Map<String, Double> =
        computeTalentEffects(aggregate.talentIds)

    // ==================== 体质效果 ====================

    fun getPhysiqueEffects(disciple: Disciple): PhysiqueEffects =
        PhysiqueDatabase.aggregatePhysiqueEffects(disciple.physiqueIds)

    fun getPhysiqueEffects(aggregate: DiscipleAggregate): PhysiqueEffects =
        PhysiqueDatabase.aggregatePhysiqueEffects(aggregate.physiqueIds)

    // ==================== 词条效果 ====================

    fun getAffixEffects(disciple: Disciple): Map<String, Double> =
        AffixDatabase.calculateAffixEffects(disciple.affixIds)

    fun getAffixEffects(aggregate: DiscipleAggregate): Map<String, Double> =
        AffixDatabase.calculateAffixEffects(aggregate.affixIds)

    /**
     * 提取词条的战斗特殊加成（独立乘算因子）。
     * 与体质独立乘算因子分开，各自独立乘算。
     */
    fun getAffixCombatEffects(disciple: Disciple): AffixCombatEffects =
        getAffixCombatEffects(disciple.affixIds)

    fun getAffixCombatEffects(aggregate: DiscipleAggregate): AffixCombatEffects =
        getAffixCombatEffects(aggregate.affixIds)

    private fun getAffixCombatEffects(affixIds: List<String>): AffixCombatEffects {
        val effects = AffixDatabase.calculateAffixEffects(affixIds)
        return AffixCombatEffects(
            damageAmplification = effects["damageAmplification"] ?: 0.0,
            critDamageBonus = effects["critDamageBonus"] ?: 0.0,
            damageReduction = effects["damageReduction"] ?: 0.0,
            defenseBonus = effects["defenseBonus"] ?: 0.0,
        )
    }

    /** 合并天赋+词条的 effects map（用于 baseStats 计算） */
    private fun getMergedEffects(disciple: Disciple): Map<String, Double> =
        mergeEffects(getTalentEffects(disciple), getAffixEffects(disciple))

    private fun getMergedEffects(aggregate: DiscipleAggregate): Map<String, Double> =
        mergeEffects(getTalentEffects(aggregate), getAffixEffects(aggregate))

    private fun mergeEffects(
        talentEffects: Map<String, Double>,
        affixEffects: Map<String, Double>
    ): Map<String, Double> {
        val merged = mutableMapOf<String, Double>()
        talentEffects.forEach { (k, v) -> merged[k] = (merged[k] ?: 0.0) + v }
        affixEffects.forEach { (k, v) -> merged[k] = (merged[k] ?: 0.0) + v }
        return merged
    }

    // ==================== 职务加成查询 ====================

    /**
     * 统计弟子（天赋+词条）中指定 slotType 的 PositionBonus 总和。
     * 用于担任职务时增强该职务职能效果（乘算因子）。
     */
    fun getPositionEffectBonus(disciple: Disciple, slotType: ElderSlotType): Double =
        getPositionEffectBonus(disciple.talentIds, disciple.affixIds, slotType)

    fun getPositionEffectBonus(aggregate: DiscipleAggregate, slotType: ElderSlotType): Double =
        getPositionEffectBonus(aggregate.talentIds, aggregate.affixIds, slotType)

    /** 列式重载：从原始 talentIds/affixIds 计算职务加成（无 Disciple 组装） */
    fun getPositionEffectBonus(
        talentIds: List<String>,
        affixIds: List<String>,
        slotType: ElderSlotType
    ): Double {
        val talentBonus = talentIds.mapNotNull { TalentDatabase.getById(it) }
            .filter { !it.isNegative }
            .filter { it.positionBonus?.slotType == slotType }
            .sumOf { it.positionBonus?.effectBonus ?: 0.0 }
        val affixBonus = AffixDatabase.aggregatePositionBonus(affixIds, slotType)
        return talentBonus + affixBonus
    }

    // ==================== 基础属性 ====================

    private fun computeBaseStats(
        realm: Int,
        realmLayer: Int,
        hpVariance: Int,
        mpVariance: Int,
        physicalAttackVariance: Int,
        magicAttackVariance: Int,
        physicalDefenseVariance: Int,
        magicDefenseVariance: Int,
        speedVariance: Int,
        talentEffects: Map<String, Double>,
        bloodRefinementPct: BloodRefinementPctTotal? = null,
        intelligence: Int,
        charm: Int,
        loyalty: Int,
        comprehension: Int,
        teaching: Int,
        morality: Int,
        mining: Int
    ): DiscipleStats {
        val realmConfig = GameConfig.Realm.get(realm)
        val layerMult = 1.0 + (realmLayer - 1) * LAYER_MULTIPLIER

        // 血炼百分比乘区与天赋同乘区加算（防御存档篡改的 NaN/Infinity/负数）
        fun safeBrPct(pct: Double): Double =
            pct.coerceAtLeast(0.0).takeIf { it.isFinite() } ?: 0.0
        val br = bloodRefinementPct
        val hpBonus = (talentEffects["maxHp"] ?: 0.0) + safeBrPct(br?.hpBonusPct ?: 0.0)
        val mpBonus = talentEffects["maxMp"] ?: 0.0
        val attackBonus = (talentEffects["physicalAttack"] ?: 0.0) + safeBrPct(br?.physicalAttackBonusPct ?: 0.0)
        val magicAttackBonus = (talentEffects["magicAttack"] ?: 0.0) + safeBrPct(br?.magicAttackBonusPct ?: 0.0)
        val defenseBonus = (talentEffects["physicalDefense"] ?: 0.0) + safeBrPct(br?.physicalDefenseBonusPct ?: 0.0)
        val magicDefenseBonus = (talentEffects["magicDefense"] ?: 0.0) + safeBrPct(br?.magicDefenseBonusPct ?: 0.0)
        val speedBonus = (talentEffects["speed"] ?: 0.0) + safeBrPct(br?.speedBonusPct ?: 0.0)
        val critBonus = talentEffects["critRate"] ?: 0.0
        val intelligenceFlat = (talentEffects["intelligenceFlat"] ?: 0.0).toInt()
        val charmFlat = (talentEffects["charmFlat"] ?: 0.0).toInt()
        val loyaltyFlat = (talentEffects["loyaltyFlat"] ?: 0.0).toInt()
        val comprehensionFlat = (talentEffects["comprehensionFlat"] ?: 0.0).toInt()
        val teachingFlat = (talentEffects["teachingFlat"] ?: 0.0).toInt()
        val moralityFlat = (talentEffects["moralityFlat"] ?: 0.0).toInt()
        val miningFlat = (talentEffects["miningFlat"] ?: 0.0).toInt()

        val hpVar = 1.0 + hpVariance / 100.0
        val mpVar = 1.0 + mpVariance / 100.0
        val paVar = 1.0 + physicalAttackVariance / 100.0
        val maVar = 1.0 + magicAttackVariance / 100.0
        val pdVar = 1.0 + physicalDefenseVariance / 100.0
        val mdVar = 1.0 + magicDefenseVariance / 100.0
        val spdVar = 1.0 + speedVariance / 100.0

        return DiscipleStats(
            hp = (realmConfig.baseHp * hpVar * layerMult * (1.0 + hpBonus)).roundToInt(),
            maxHp = (realmConfig.baseHp * hpVar * layerMult * (1.0 + hpBonus)).roundToInt(),
            mp = (realmConfig.baseMp * mpVar * layerMult * (1.0 + mpBonus)).roundToInt(),
            maxMp = (realmConfig.baseMp * mpVar * layerMult * (1.0 + mpBonus)).roundToInt(),
            physicalAttack = (realmConfig.basePhysicalAttack * paVar * layerMult * (1.0 + attackBonus)).roundToInt(),
            magicAttack = (realmConfig.baseMagicAttack * maVar * layerMult * (1.0 + magicAttackBonus)).roundToInt(),
            physicalDefense = (realmConfig.basePhysicalDefense * pdVar * layerMult * (1.0 + defenseBonus)).roundToInt(),
            magicDefense = (realmConfig.baseMagicDefense * mdVar * layerMult * (1.0 + magicDefenseBonus)).roundToInt(),
            speed = (realmConfig.baseSpeed * spdVar * layerMult * (1.0 + speedBonus)).roundToInt(),
            critRate = BASE_CRIT_RATE + critBonus,
            intelligence = intelligence + intelligenceFlat,
            charm = charm + charmFlat,
            loyalty = loyalty + loyaltyFlat,
            comprehension = comprehension + comprehensionFlat,
            teaching = teaching + teachingFlat,
            morality = morality + moralityFlat,
            mining = mining + miningFlat
        )
    }

    fun getBaseStats(disciple: Disciple): DiscipleStats {
        val c = disciple.combat
        return computeBaseStats(
            realm = disciple.realm,
            realmLayer = disciple.realmLayer,
            hpVariance = c.hpVariance,
            mpVariance = c.mpVariance,
            physicalAttackVariance = c.physicalAttackVariance,
            magicAttackVariance = c.magicAttackVariance,
            physicalDefenseVariance = c.physicalDefenseVariance,
            magicDefenseVariance = c.magicDefenseVariance,
            speedVariance = c.speedVariance,
            talentEffects = getMergedEffects(disciple),
            intelligence = disciple.skills.intelligence,
            charm = disciple.skills.charm,
            loyalty = disciple.skills.loyalty,
            comprehension = disciple.skills.comprehension,
            teaching = disciple.skills.teaching,
            morality = disciple.skills.morality,
            mining = disciple.skills.mining
        )
    }

    fun getBaseStats(aggregate: DiscipleAggregate): DiscipleStats {
        val cs = aggregate.combatStats
        val attr = aggregate.attributes
        return computeBaseStats(
            realm = aggregate.realm,
            realmLayer = aggregate.realmLayer,
            hpVariance = cs?.hpVariance ?: 0,
            mpVariance = cs?.mpVariance ?: 0,
            physicalAttackVariance = cs?.physicalAttackVariance ?: 0,
            magicAttackVariance = cs?.magicAttackVariance ?: 0,
            physicalDefenseVariance = cs?.physicalDefenseVariance ?: 0,
            magicDefenseVariance = cs?.magicDefenseVariance ?: 0,
            speedVariance = cs?.speedVariance ?: 0,
            talentEffects = getMergedEffects(aggregate),
            intelligence = attr?.intelligence ?: 50,
            charm = attr?.charm ?: 50,
            loyalty = attr?.loyalty ?: 50,
            comprehension = attr?.comprehension ?: 50,
            teaching = attr?.teaching ?: 50,
            morality = attr?.morality ?: 50,
            mining = attr?.mining ?: 50
        )
    }

    /**
     * 计算弟子的永久基础属性（含境界基础 + 天赋 + 血炼百分比乘区）。
     *
     * 用于战力计算：属性 = 境界基础 × 方差 × 层数 × (1 + 天赋% + 血炼%)。
     * 不包含装备、功法、临时丹药等临时加成。
     *
     * @param aggregate 弟子聚合数据
     * @param bloodRefinementPct 血炼百分比累计记录，若无则为 null
     */
    fun getPermanentBaseStats(
        aggregate: DiscipleAggregate,
        bloodRefinementPct: BloodRefinementPctTotal? = null
    ): DiscipleStats {
        val cs = aggregate.combatStats
        val attr = aggregate.attributes
        return computeBaseStats(
            realm = aggregate.realm,
            realmLayer = aggregate.realmLayer,
            hpVariance = cs?.hpVariance ?: 0,
            mpVariance = cs?.mpVariance ?: 0,
            physicalAttackVariance = cs?.physicalAttackVariance ?: 0,
            magicAttackVariance = cs?.magicAttackVariance ?: 0,
            physicalDefenseVariance = cs?.physicalDefenseVariance ?: 0,
            magicDefenseVariance = cs?.magicDefenseVariance ?: 0,
            speedVariance = cs?.speedVariance ?: 0,
            talentEffects = getMergedEffects(aggregate),
            bloodRefinementPct = bloodRefinementPct,
            intelligence = attr?.intelligence ?: 50,
            charm = attr?.charm ?: 50,
            loyalty = attr?.loyalty ?: 50,
            comprehension = attr?.comprehension ?: 50,
            teaching = attr?.teaching ?: 50,
            morality = attr?.morality ?: 50,
            mining = attr?.mining ?: 50
        )
    }

    // ==================== 装备属性 ====================

    private fun computeStatsWithEquipment(
        baseStats: DiscipleStats,
        equipmentIds: List<String>,
        equipments: Map<String, EquipmentInstance>
    ): DiscipleStats {
        var total = baseStats
        var totalCritChance = 0.0
        equipmentIds.forEach { equipId ->
            val equipment = equipments[equipId]
            if (equipment != null) {
                equipment.getFinalStats().toDiscipleStats().let { total = total + it }
                totalCritChance += equipment.critChance
            }
        }
        return total.copy(critRate = total.critRate + totalCritChance)
    }

    fun getStatsWithEquipment(
        disciple: Disciple,
        equipments: Map<String, EquipmentInstance>
    ): DiscipleStats {
        val equipmentIds = listOfNotNull(
            disciple.equipment.weaponId,
            disciple.equipment.armorId,
            disciple.equipment.bootsId,
            disciple.equipment.accessoryId
        )
        return computeStatsWithEquipment(getBaseStats(disciple), equipmentIds, equipments)
    }

    fun getStatsWithEquipment(
        aggregate: DiscipleAggregate,
        equipments: Map<String, EquipmentInstance>
    ): DiscipleStats {
        val eq = aggregate.equipment
        val equipmentIds = listOfNotNull(
            eq?.weaponId, eq?.armorId, eq?.bootsId, eq?.accessoryId
        ).filter { it.isNotEmpty() }
        return computeStatsWithEquipment(getBaseStats(aggregate), equipmentIds, equipments)
    }

    // ==================== 最终属性（含装备+功法+丹药） ====================

    private fun computeFinalStats(
        baseStats: DiscipleStats,
        equipmentIds: List<String>,
        manualIds: List<String>,
        equipments: Map<String, EquipmentInstance>,
        manuals: Map<String, ManualInstance>,
        manualProficiencies: Map<String, ManualProficiencyData>,
        hasPillEffect: Boolean,
        pillHpBonus: Int,
        pillMpBonus: Int,
        pillPhysicalAttackBonus: Int,
        pillMagicAttackBonus: Int,
        pillPhysicalDefenseBonus: Int,
        pillMagicDefenseBonus: Int,
        pillSpeedBonus: Int,
        pillCritRateBonus: Double
    ): DiscipleStats {
        var total = baseStats
        var totalCritRate = total.critRate

        // 装备
        equipmentIds.forEach { equipId ->
            val equipment = equipments[equipId]
            if (equipment != null) {
                equipment.getFinalStats().toDiscipleStats().let { total = total + it }
                totalCritRate += equipment.critChance
            }
        }

        // 功法
        manualIds.forEach { manualId ->
            val manual = manuals[manualId]
            if (manual != null) {
                val proficiencyData = manualProficiencies[manualId]
                val masteryLevel = proficiencyData?.masteryLevel ?: 0
                val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus

                val hpValue = manual.stats["hp"] ?: manual.stats["maxHp"] ?: 0
                val mpValue = manual.stats["mp"] ?: manual.stats["maxMp"] ?: 0
                val manualStats = DiscipleStats(
                    hp = (hpValue * masteryBonus).toInt(),
                    maxHp = (hpValue * masteryBonus).toInt(),
                    mp = (mpValue * masteryBonus).toInt(),
                    maxMp = (mpValue * masteryBonus).toInt(),
                    physicalAttack = ((manual.stats["physicalAttack"] ?: 0) * masteryBonus).toInt(),
                    magicAttack = ((manual.stats["magicAttack"] ?: 0) * masteryBonus).toInt(),
                    physicalDefense = ((manual.stats["physicalDefense"] ?: 0) * masteryBonus).toInt(),
                    magicDefense = ((manual.stats["magicDefense"] ?: 0) * masteryBonus).toInt(),
                    speed = ((manual.stats["speed"] ?: 0) * masteryBonus).toInt(),
                    critRate = 1.0
                )
                total = total + manualStats
                totalCritRate += ((manual.stats["critRate"] ?: 0) * masteryBonus) / 100.0
            }
        }

        // 丹药
        if (hasPillEffect) {
            val pillBonus = DiscipleStats(
                hp = pillHpBonus,
                maxHp = pillHpBonus,
                mp = pillMpBonus,
                maxMp = pillMpBonus,
                physicalAttack = pillPhysicalAttackBonus,
                magicAttack = pillMagicAttackBonus,
                physicalDefense = pillPhysicalDefenseBonus,
                magicDefense = pillMagicDefenseBonus,
                speed = pillSpeedBonus,
                critRate = pillCritRateBonus
            )
            total = total + pillBonus
            totalCritRate += pillCritRateBonus
        }

        return total.copy(critRate = totalCritRate)
    }

    fun getFinalStats(
        disciple: Disciple,
        equipments: Map<String, EquipmentInstance>,
        manuals: Map<String, ManualInstance>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): DiscipleStats {
        val pe = disciple.pillEffects
        return computeFinalStats(
            baseStats = getBaseStats(disciple),
            equipmentIds = listOfNotNull(
                disciple.equipment.weaponId,
                disciple.equipment.armorId,
                disciple.equipment.bootsId,
                disciple.equipment.accessoryId
            ),
            manualIds = disciple.manualIds,
            equipments = equipments,
            manuals = manuals,
            manualProficiencies = manualProficiencies,
            hasPillEffect = pe.pillEffectDuration > 0,
            pillHpBonus = pe.pillHpBonus,
            pillMpBonus = pe.pillMpBonus,
            pillPhysicalAttackBonus = pe.pillPhysicalAttackBonus,
            pillMagicAttackBonus = pe.pillMagicAttackBonus,
            pillPhysicalDefenseBonus = pe.pillPhysicalDefenseBonus,
            pillMagicDefenseBonus = pe.pillMagicDefenseBonus,
            pillSpeedBonus = pe.pillSpeedBonus,
            pillCritRateBonus = pe.pillCritRateBonus
        )
    }

    fun getFinalStats(
        aggregate: DiscipleAggregate,
        equipments: Map<String, EquipmentInstance>,
        manuals: Map<String, ManualInstance>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): DiscipleStats {
        val eq = aggregate.equipment
        val cs = aggregate.combatStats
        return computeFinalStats(
            baseStats = getBaseStats(aggregate),
            equipmentIds = listOfNotNull(
                eq?.weaponId, eq?.armorId, eq?.bootsId, eq?.accessoryId
            ).filter { it.isNotEmpty() },
            manualIds = aggregate.manualIds,
            equipments = equipments,
            manuals = manuals,
            manualProficiencies = manualProficiencies,
            hasPillEffect = cs != null && cs.pillEffectDuration > 0,
            pillHpBonus = cs?.pillHpBonus ?: 0,
            pillMpBonus = cs?.pillMpBonus ?: 0,
            pillPhysicalAttackBonus = cs?.pillPhysicalAttackBonus ?: 0,
            pillMagicAttackBonus = cs?.pillMagicAttackBonus ?: 0,
            pillPhysicalDefenseBonus = cs?.pillPhysicalDefenseBonus ?: 0,
            pillMagicDefenseBonus = cs?.pillMagicDefenseBonus ?: 0,
            pillSpeedBonus = cs?.pillSpeedBonus ?: 0,
            pillCritRateBonus = cs?.pillCritRateBonus ?: 0.0
        )
    }

    // ==================== 修炼速度乘区 ====================

    /**
     * 修炼速度乘区分组。
     *
     * 遵循"同类加算、异类乘算"原则，将 14 种加成归入 5 个独立乘区。
     * 每个乘区内部为加算，乘区之间为乘算。
     */
    data class CultivationSpeedZones(
        val aptitudeBonus: Double = 0.0,    // 资质乘区：天赋
        val resourceBonus: Double = 0.0,    // 资源乘区：功法+丹药+建筑
        val socialBonus: Double = 0.0,      // 社交乘区：师徒+传道+父母
        val statusBonus: Double = 0.0,      // 状态乘区：丧亲+寿命+政策
        val temporaryBonus: Double = 0.0,   // 临时乘区：丹药临时加速
    )

    /**
     * 计算每旬修炼值（乘区制核心公式）。
     *
     * 公式：基础速度 × Π(1 + 各乘区加算和)
     *
     * @param realm 弟子境界（0=仙人 … 9=炼气）
     * @param spiritRootCount 灵根数量（1-5）
     * @param zones 各乘区加算值分组
     * @return 每旬修炼值，最低 1.0
     */
    fun calculateCultivationPerPhase(
        realm: Int,
        spiritRootCount: Int,
        zones: CultivationSpeedZones
    ): Double {
        val rootCount = spiritRootCount.coerceAtLeast(1)
        val base = GameConfig.Cultivation.getRealmPerPhase(realm) / rootCount.toDouble()
        return (base
            * (1.0 + zones.aptitudeBonus)
            * (1.0 + zones.resourceBonus)
            * (1.0 + zones.socialBonus)
            * (1.0 + zones.statusBonus)
            * (1.0 + zones.temporaryBonus)
        ).coerceAtLeast(MIN_CULTIVATION_PER_PHASE)
    }

    private fun computeCultivationZones(
        mergedEffects: Map<String, Double>,
        physiqueEffects: PhysiqueEffects,
        manualIds: List<String>,
        manuals: Map<String, ManualInstance>,
        manualProficiencies: Map<String, ManualProficiencyData>,
        buildingBonus: Double,
        preachingElderBonus: Double,
        preachingMastersBonus: Double,
        parentCultivationBonus: Double,
        masterDiscipleBonus: Double,
        cultivationSubsidyBonus: Double,
        griefCultivationSpeedPenalty: Double,
        age: Int,
        lifespan: Int,
        temporaryBonus: Double
    ): CultivationSpeedZones {
        // ── 资质乘区：天赋(旧存档可能有) + 体质 + 词条 ──
        val aptitudeBonus = (mergedEffects["cultivationSpeed"] ?: 0.0) +
            physiqueEffects.cultivationSpeedBonus

        // ── 资源乘区：功法 + 建筑 ──
        var resourceBonus = (buildingBonus - 1.0)
        if (manuals.isNotEmpty()) {
            manualIds.forEach { manualId ->
                val manual = manuals[manualId] ?: return@forEach
                val masteryLevel = manualProficiencies[manualId]?.masteryLevel ?: 0
                val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
                resourceBonus += manual.cultivationSpeedPercent * masteryBonus / 100.0
            }
        } else if (manualIds.isNotEmpty()) {
            // 兜底路径：调用方未传 manuals 实例映射时，从 ManualDatabase 静态查询
            // 影响：不支持动态实例属性（如孕养等级），但用于 UI 显示预览足够
            manualIds.forEach { manualId ->
                val manual = ManualDatabase.getById(manualId) ?: return@forEach
                val masteryLevel = manualProficiencies[manualId]?.masteryLevel ?: 0
                val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
                resourceBonus += (manual.stats["cultivationSpeedPercent"] ?: 0) * masteryBonus / 100.0
            }
        }

        // ── 社交乘区：师徒 + 传道长老/师兄 + 父母 ──
        val socialBonus = preachingElderBonus + preachingMastersBonus +
            parentCultivationBonus + masterDiscipleBonus

        // ── 状态乘区：政策津贴 - 丧亲 - 寿命 ──
        val lifespanPenalty = calculateLifespanCultivationPenalty(age, lifespan)
        val statusBonus = cultivationSubsidyBonus - griefCultivationSpeedPenalty - lifespanPenalty

        return CultivationSpeedZones(
            aptitudeBonus = aptitudeBonus,
            resourceBonus = resourceBonus,
            socialBonus = socialBonus,
            statusBonus = statusBonus,
            temporaryBonus = temporaryBonus
        )
    }

    /**
     * 组装修炼速度乘区（从 Disciple 对象提取各加成）。
     */
    fun buildCultivationZones(
        disciple: Disciple,
        manuals: Map<String, ManualInstance> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        buildingBonus: Double = 1.0,
        preachingElderBonus: Double = 0.0,
        preachingMastersBonus: Double = 0.0,
        cultivationSubsidyBonus: Double = 0.0,
        parentCultivationBonus: Double = 0.0,
        griefCultivationSpeedPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0
    ): CultivationSpeedZones {
        var temporaryBonus = 0.0
        if (disciple.cultivationSpeedDuration > 0 && disciple.cultivationSpeedBonus > 0.0) {
            temporaryBonus += disciple.cultivationSpeedBonus
        }
        if (disciple.pillEffects.pillEffectDuration > 0 && disciple.pillEffects.pillCultivationSpeedBonus > 0.0) {
            temporaryBonus += disciple.pillEffects.pillCultivationSpeedBonus
        }
        return computeCultivationZones(
            mergedEffects = getMergedEffects(disciple),
            physiqueEffects = getPhysiqueEffects(disciple),
            manualIds = disciple.manualIds,
            manuals = manuals,
            manualProficiencies = manualProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = preachingElderBonus,
            preachingMastersBonus = preachingMastersBonus,
            parentCultivationBonus = parentCultivationBonus,
            masterDiscipleBonus = masterDiscipleBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus,
            griefCultivationSpeedPenalty = griefCultivationSpeedPenalty,
            age = disciple.age,
            lifespan = disciple.lifespan,
            temporaryBonus = temporaryBonus
        )
    }

    /**
     * 组装修炼速度乘区（从 DiscipleAggregate 对象提取各加成）。
     */
    fun buildCultivationZones(
        aggregate: DiscipleAggregate,
        manuals: Map<String, ManualInstance> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        buildingBonus: Double = 1.0,
        preachingElderBonus: Double = 0.0,
        preachingMastersBonus: Double = 0.0,
        cultivationSubsidyBonus: Double = 0.0,
        parentCultivationBonus: Double = 0.0,
        griefCultivationSpeedPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0
    ): CultivationSpeedZones {
        var temporaryBonus = 0.0
        val ext = aggregate.extended
        if (ext != null && ext.cultivationSpeedDuration > 0 && ext.cultivationSpeedBonus > 0.0) {
            temporaryBonus += ext.cultivationSpeedBonus
        }
        if (ext != null && ext.pillEffectDuration > 0 && ext.pillCultivationSpeedBonus > 0.0) {
            temporaryBonus += ext.pillCultivationSpeedBonus
        }
        return computeCultivationZones(
            mergedEffects = getMergedEffects(aggregate),
            physiqueEffects = getPhysiqueEffects(aggregate),
            manualIds = aggregate.manualIds,
            manuals = manuals,
            manualProficiencies = manualProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = preachingElderBonus,
            preachingMastersBonus = preachingMastersBonus,
            parentCultivationBonus = parentCultivationBonus,
            masterDiscipleBonus = masterDiscipleBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus,
            griefCultivationSpeedPenalty = griefCultivationSpeedPenalty,
            age = aggregate.age,
            lifespan = aggregate.lifespan,
            temporaryBonus = temporaryBonus
        )
    }

    // ==================== 修炼乘区便捷计算（公共） ====================

    /**
     * 列式修炼速率计算的输入字段。
     *
     * 由调用方从 [com.xianxia.sect.core.state.DiscipleTables] 列直读提取，
     * 避免为计算速率而 assemble 完整 Disciple 对象（每旬热点循环用）。
     */
    data class CultivationRateColumnInput(
        val realm: Int,
        val spiritRootCount: Int,
        val talentIds: List<String>,
        val physiqueIds: List<String>,
        val affixIds: List<String>,
        val manualIds: List<String>,
        val age: Int,
        val lifespan: Int,
        val cultivationSpeedDuration: Int,
        val cultivationSpeedBonus: Double,
        val pillEffectDuration: Int,
        val pillCultivationSpeedBonus: Double
    )

    /**
     * 使用乘区制计算每旬修炼值（列式直读版本，无 Disciple 组装）。
     *
     * 与 [calculateCultivationPerPhase]（Disciple 版本）语义等价：
     * 从 [CultivationRateColumnInput] 提取原始字段，复用同一套乘区计算。
     * 供每旬热点循环（accumulateCultivationPerPhase）使用。
     */
    fun calculateCultivationPerPhaseColumn(
        input: CultivationRateColumnInput,
        manuals: Map<String, ManualInstance> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        buildingBonus: Double = 1.0,
        preachingElderBonus: Double = 0.0,
        preachingMastersBonus: Double = 0.0,
        cultivationSubsidyBonus: Double = 0.0,
        parentCultivationBonus: Double = 0.0,
        griefCultivationSpeedPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0
    ): Double {
        var temporaryBonus = 0.0
        if (input.cultivationSpeedDuration > 0 && input.cultivationSpeedBonus > 0.0) {
            temporaryBonus += input.cultivationSpeedBonus
        }
        if (input.pillEffectDuration > 0 && input.pillCultivationSpeedBonus > 0.0) {
            temporaryBonus += input.pillCultivationSpeedBonus
        }
        val zones = computeCultivationZones(
            mergedEffects = mergeEffects(
                computeTalentEffects(input.talentIds),
                AffixDatabase.calculateAffixEffects(input.affixIds)
            ),
            physiqueEffects = PhysiqueDatabase.aggregatePhysiqueEffects(input.physiqueIds),
            manualIds = input.manualIds,
            manuals = manuals,
            manualProficiencies = manualProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = preachingElderBonus,
            preachingMastersBonus = preachingMastersBonus,
            parentCultivationBonus = parentCultivationBonus,
            masterDiscipleBonus = masterDiscipleBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus,
            griefCultivationSpeedPenalty = griefCultivationSpeedPenalty,
            age = input.age,
            lifespan = input.lifespan,
            temporaryBonus = temporaryBonus
        )
        return calculateCultivationPerPhase(input.realm, input.spiritRootCount, zones)
    }

    /**
     * 使用乘区制计算每旬修炼值（Disciple 版本便捷入口）。
     */
    fun calculateCultivationPerPhase(
        disciple: Disciple,
        manuals: Map<String, ManualInstance> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        buildingBonus: Double = 1.0,
        preachingElderBonus: Double = 0.0,
        preachingMastersBonus: Double = 0.0,
        cultivationSubsidyBonus: Double = 0.0,
        parentCultivationBonus: Double = 0.0,
        griefCultivationSpeedPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0
    ): Double {
        val zones = buildCultivationZones(
            disciple, manuals, manualProficiencies,
            buildingBonus, preachingElderBonus, preachingMastersBonus,
            cultivationSubsidyBonus, parentCultivationBonus,
            griefCultivationSpeedPenalty, masterDiscipleBonus
        )
        return calculateCultivationPerPhase(disciple.realm, disciple.spiritRoot.types.size, zones)
    }

    /**
     * 使用乘区制计算每旬修炼值（DiscipleAggregate 版本便捷入口）。
     */
    fun calculateCultivationPerPhase(
        aggregate: DiscipleAggregate,
        manuals: Map<String, ManualInstance> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        buildingBonus: Double = 1.0,
        preachingElderBonus: Double = 0.0,
        preachingMastersBonus: Double = 0.0,
        cultivationSubsidyBonus: Double = 0.0,
        parentCultivationBonus: Double = 0.0,
        griefCultivationSpeedPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0
    ): Double {
        val zones = buildCultivationZones(
            aggregate, manuals, manualProficiencies,
            buildingBonus, preachingElderBonus, preachingMastersBonus,
            cultivationSubsidyBonus, parentCultivationBonus,
            griefCultivationSpeedPenalty, masterDiscipleBonus
        )
        return calculateCultivationPerPhase(aggregate.realm, aggregate.spiritRoot.types.size, zones)
    }

    // ==================== 突破概率乘区 ====================

    /**
     * 突破概率乘区（Breakthrough Zone）。
     *
     * 遵循"乘区内加算、乘区间乘算"原则。
     * 基础概率作为 baseZone（本身就是概率值 0~1），其他乘区以 (1 + bonus) 形式乘算。
     *
     * 公式：baseZone × (1 + elderGuidance + selfBonus) × (1 - penalty) + adFlatBonus
     */
    data class BreakthroughZones(
        val baseZone: Double = 0.0,        // 基础概率（境界+灵根+层数）
        val elderGuidance: Double = 0.0,   // 长老指导乘区：内门+外门
        val selfBonus: Double = 0.0,       // 自身加成乘区：天赋+魂力+丹药+师徒
        val statusPenalty: Double = 0.0,   // 状态惩罚乘区：丧亲+寿命（正值 = 惩罚幅度）
        val adFlatBonus: Double = 0.0,     // 广告扁平加成（不经过乘区缩放，直接加在最终值上）
    )

    private fun computeBreakthroughZones(
        realm: Int,
        realmLayer: Int,
        spiritRootCount: Int,
        soulPower: Int,
        age: Int,
        lifespan: Int,
        innerElderComprehension: Int,
        outerElderComprehension: Int,
        pillBonus: Double,
        adBonus: Double,
        griefBreakthroughPenalty: Double,
        masterDiscipleBonus: Double,
        innerElderPositionBonus: Double = 0.0,
        outerElderPositionBonus: Double = 0.0
    ): BreakthroughZones {
        val baseZone = GameConfig.Realm.getBreakthroughChance(realm, spiritRootCount, realmLayer)
        // 长老职能效果 × (1 + PositionBonus)：PositionBonus 来自长老弟子（非突破弟子）的天赋/词条
        val innerElderBonus = elderBreakthroughBonus(innerElderComprehension) * (1.0 + innerElderPositionBonus)
        val outerElderBonus = elderBreakthroughBonus(outerElderComprehension) * (1.0 + outerElderPositionBonus)
        val soulPowerBonus = getSoulPowerBreakthroughBonus(soulPower)
        val lifespanPenalty = calculateLifespanBreakthroughPenalty(age, lifespan)

        // 突破加成已从天赋系统中移除：selfBonus 不再包含 talentBreakthroughBonus
        return BreakthroughZones(
            baseZone = baseZone,
            elderGuidance = innerElderBonus + outerElderBonus,
            selfBonus = pillBonus + soulPowerBonus + masterDiscipleBonus,
            statusPenalty = griefBreakthroughPenalty + lifespanPenalty,
            adFlatBonus = adBonus
        )
    }

    /**
     * 构建突破概率乘区（从 Disciple 对象提取各加成）。
     */
    fun buildBreakthroughZones(
        disciple: Disciple,
        innerElderComprehension: Int = 0,
        outerElderComprehension: Int = 0,
        pillBonus: Double = 0.0,
        adBonus: Double = 0.0,
        griefBreakthroughPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0,
        innerElderPositionBonus: Double = 0.0,
        outerElderPositionBonus: Double = 0.0
    ): BreakthroughZones = computeBreakthroughZones(
        realm = disciple.realm,
        realmLayer = disciple.realmLayer,
        spiritRootCount = disciple.spiritRoot.types.size,
        soulPower = disciple.soulPower,
        age = disciple.age,
        lifespan = disciple.lifespan,
        innerElderComprehension = innerElderComprehension,
        outerElderComprehension = outerElderComprehension,
        pillBonus = pillBonus,
        adBonus = adBonus,
        griefBreakthroughPenalty = griefBreakthroughPenalty,
        masterDiscipleBonus = masterDiscipleBonus,
        innerElderPositionBonus = innerElderPositionBonus,
        outerElderPositionBonus = outerElderPositionBonus
    )

    /**
     * 构建突破概率乘区（从 DiscipleAggregate 对象提取各加成）。
     */
    fun buildBreakthroughZones(
        aggregate: DiscipleAggregate,
        innerElderComprehension: Int = 0,
        outerElderComprehension: Int = 0,
        pillBonus: Double = 0.0,
        adBonus: Double = 0.0,
        griefBreakthroughPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0,
        innerElderPositionBonus: Double = 0.0,
        outerElderPositionBonus: Double = 0.0
    ): BreakthroughZones = computeBreakthroughZones(
        realm = aggregate.realm,
        realmLayer = aggregate.realmLayer,
        spiritRootCount = aggregate.spiritRoot.types.size,
        soulPower = aggregate.soulPower,
        age = aggregate.age,
        lifespan = aggregate.lifespan,
        innerElderComprehension = innerElderComprehension,
        outerElderComprehension = outerElderComprehension,
        pillBonus = pillBonus,
        adBonus = adBonus,
        griefBreakthroughPenalty = griefBreakthroughPenalty,
        masterDiscipleBonus = masterDiscipleBonus,
        innerElderPositionBonus = innerElderPositionBonus,
        outerElderPositionBonus = outerElderPositionBonus
    )

    /**
     * 使用乘区法计算最终突破概率。
     *
     * 公式：baseZone × (1 + elderGuidance + selfBonus) × (1 - statusPenalty)
     * 结果 clamp 到 [0, 1]。
     */
    fun calculateBreakthroughChance(zones: BreakthroughZones): Double {
        val positiveMult = 1.0 + zones.elderGuidance + zones.selfBonus
        val penaltyMult = (1.0 - zones.statusPenalty).coerceAtLeast(0.0)
        val base = zones.baseZone * positiveMult * penaltyMult
        // adFlatBonus 为扁平加法，不过乘区，确保广告观看后固定增加
        return (base + zones.adFlatBonus).coerceIn(0.0, 1.0)
    }

    /**
     * 长老突破率加成（内外门共用）。
     * 悟性80基准，每5点+1%，最多+5%。
     */
    private fun elderBreakthroughBonus(comprehension: Int): Double {
        if (comprehension < GameConfig.PolicyConfig.ELDER_SKILL_BASELINE) {
            return 0.0
        }
        val steps =
            (comprehension - GameConfig.PolicyConfig.ELDER_SKILL_BASELINE) /
            GameConfig.PolicyConfig.ELDER_BONUS_DIVISOR
        return steps.coerceAtMost(
            GameConfig.PolicyConfig.ELDER_BREAKTHROUGH_MAX_STEPS
        ) * ELDER_BONUS_PER_STEP
    }

    /**
     * 计算突破概率（Disciple 版本便捷入口）。
     */
    fun getBreakthroughChance(
        disciple: Disciple,
        innerElderComprehension: Int = 0,
        outerElderComprehension: Int = 0,
        pillBonus: Double = 0.0,
        adBonus: Double = 0.0,
        griefBreakthroughPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0,
        innerElderPositionBonus: Double = 0.0,
        outerElderPositionBonus: Double = 0.0
    ): Double {
        if (disciple.realm < 0) return 0.0
        val zones = buildBreakthroughZones(
            disciple, innerElderComprehension, outerElderComprehension,
            pillBonus, adBonus, griefBreakthroughPenalty, masterDiscipleBonus,
            innerElderPositionBonus, outerElderPositionBonus
        )
        return calculateBreakthroughChance(zones)
    }

    /**
     * 计算突破概率（DiscipleAggregate 版本便捷入口）。
     */
    fun getBreakthroughChance(
        aggregate: DiscipleAggregate,
        innerElderComprehension: Int = 0,
        outerElderComprehension: Int = 0,
        pillBonus: Double = 0.0,
        adBonus: Double = 0.0,
        griefBreakthroughPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0,
        innerElderPositionBonus: Double = 0.0,
        outerElderPositionBonus: Double = 0.0
    ): Double {
        if (aggregate.realm < 0) return 0.0
        val zones = buildBreakthroughZones(
            aggregate, innerElderComprehension, outerElderComprehension,
            pillBonus, adBonus, griefBreakthroughPenalty, masterDiscipleBonus,
            innerElderPositionBonus, outerElderPositionBonus
        )
        return calculateBreakthroughChance(zones)
    }

    fun getSoulPowerBreakthroughBonus(soulPower: Int): Double {
        return ((soulPower / SOUL_POWER_DIVISOR).coerceAtMost(SOUL_POWER_MAX_STEPS)) / 100.0
    }

    data class BreakthroughBonusDetail(
        val baseChance: Double,
        val innerElderBonus: Double,
        val outerElderBonus: Double,
        /** @deprecated 突破加成已从天赋系统移除，此字段仅供旧存档显示，不参与计算 */
        val talentBonus: Double,
        val soulPowerBonus: Double,
        val pillBonus: Double,
        val adBonus: Double,
        val masterDiscipleBonus: Double,
        val griefPenalty: Double,
        val lifespanPenalty: Double,
        val total: Double
    )

    fun getBreakthroughBonusDetail(
        aggregate: DiscipleAggregate,
        innerElderComprehension: Int = 0,
        outerElderComprehension: Int = 0,
        pillBonus: Double = 0.0,
        adBonus: Double = 0.0,
        griefBreakthroughPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0
    ): BreakthroughBonusDetail {
        if (aggregate.realm < 0) return BreakthroughBonusDetail(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val zones = buildBreakthroughZones(
            aggregate, innerElderComprehension, outerElderComprehension,
            pillBonus, adBonus, griefBreakthroughPenalty, masterDiscipleBonus
        )
        val total = calculateBreakthroughChance(zones)
        return BreakthroughBonusDetail(
            baseChance = zones.baseZone,
            innerElderBonus = elderBreakthroughBonus(innerElderComprehension),
            outerElderBonus = elderBreakthroughBonus(outerElderComprehension),
            // 旧存档弟子可能有 breakthroughChance 天赋，仅供显示，不参与 total 计算
            talentBonus = getTalentEffects(aggregate)["breakthroughChance"] ?: 0.0,
            soulPowerBonus = getSoulPowerBreakthroughBonus(aggregate.soulPower),
            pillBonus = pillBonus,
            adBonus = adBonus,
            masterDiscipleBonus = masterDiscipleBonus,
            griefPenalty = griefBreakthroughPenalty,
            lifespanPenalty = calculateLifespanBreakthroughPenalty(aggregate.age, aggregate.lifespan),
            total = total
        )
    }

    // ==================== 功法/灵根槽位 ====================

    private fun computeMaxManualSlots(mergedEffects: Map<String, Double>): Int {
        val manualSlotBonus = mergedEffects["manualSlot"]?.toInt() ?: 0
        return BASE_MANUAL_SLOTS + manualSlotBonus
    }

    fun getMaxManualSlots(disciple: Disciple): Int =
        computeMaxManualSlots(getMergedEffects(disciple))

    fun getMaxManualSlots(aggregate: DiscipleAggregate): Int =
        computeMaxManualSlots(getMergedEffects(aggregate))

    // ==================== 传道加成 ====================

    private fun computePreachingBonus(
        discipleType: String,
        realm: Int,
        targetDiscipleType: String,
        preachingElder: Disciple?,
        preachingMasters: List<Disciple>
    ): Pair<Double, Double> {
        if (discipleType != targetDiscipleType) return 0.0 to 0.0

        var elderBonus = 0.0
        var mastersBonus = 0.0

        if (preachingElder != null && preachingElder.isAlive) {
            val elderTeaching = getBaseStats(preachingElder).teaching
            if (realm >= preachingElder.realm && elderTeaching >= ELDER_TEACHING_BASELINE) {
                val base = ((elderTeaching - ELDER_TEACHING_BASELINE) * ELDER_TEACHING_RATE)
                    .coerceAtMost(ELDER_TEACHING_MAX_BONUS)
                // 长老职务加成（PositionBonus）：作为乘算因子作用于长老职能效果
                // 外门传道→PREACHING，内门青云传道→CLOUD_PREACHING
                val slotType = if (targetDiscipleType == TYPE_OUTER) ElderSlotType.PREACHING
                    else ElderSlotType.CLOUD_PREACHING
                val posBonus = getPositionEffectBonus(preachingElder, slotType)
                elderBonus = base * (1.0 + posBonus)
            }
        }

        preachingMasters.filter { it.isAlive }.forEach { master ->
            val masterTeaching = getBaseStats(master).teaching
            if (realm >= master.realm && masterTeaching >= MASTER_TEACHING_BASELINE) {
                val bonus = ((masterTeaching - MASTER_TEACHING_BASELINE) * MASTER_TEACHING_RATE)
                    .coerceAtMost(MASTER_TEACHING_MAX_BONUS)
                mastersBonus += bonus
            }
        }

        return elderBonus to mastersBonus
    }

    fun calculatePreachingBonus(
        disciple: Disciple,
        targetDiscipleType: String,
        preachingElder: Disciple?,
        preachingMasters: List<Disciple>
    ): Pair<Double, Double> = computePreachingBonus(
        discipleType = disciple.discipleType,
        realm = disciple.realm,
        targetDiscipleType = targetDiscipleType,
        preachingElder = preachingElder,
        preachingMasters = preachingMasters
    )

    fun calculatePreachingBonus(
        aggregate: DiscipleAggregate,
        targetDiscipleType: String,
        preachingElder: Disciple?,
        preachingMasters: List<Disciple>
    ): Pair<Double, Double> = computePreachingBonus(
        discipleType = aggregate.discipleType,
        realm = aggregate.realm,
        targetDiscipleType = targetDiscipleType,
        preachingElder = preachingElder,
        preachingMasters = preachingMasters
    )

    fun calculateQingyunPeakCultivationSpeedBonus(
        disciple: Disciple,
        innerElder: Disciple? = null,
        qingyunPreachingElder: Disciple? = null,
        qingyunPreachingMasters: List<Disciple> = emptyList()
    ): Double {
        val (elderBonus, mastersBonus) = calculatePreachingBonus(
            disciple = disciple,
            targetDiscipleType = TYPE_INNER,
            preachingElder = qingyunPreachingElder,
            preachingMasters = qingyunPreachingMasters
        )
        return elderBonus + mastersBonus
    }

    fun calculateQingyunPeakCultivationSpeedBonus(
        aggregate: DiscipleAggregate,
        innerElder: Disciple? = null,
        qingyunPreachingElder: Disciple? = null,
        qingyunPreachingMasters: List<Disciple> = emptyList()
    ): Double {
        val (elderBonus, mastersBonus) = calculatePreachingBonus(
            aggregate = aggregate,
            targetDiscipleType = TYPE_INNER,
            preachingElder = qingyunPreachingElder,
            preachingMasters = qingyunPreachingMasters
        )
        return elderBonus + mastersBonus
    }

    // ==================== 血炼系统属性加成 ====================

    /**
     * 根据血种随机选择属性（50/50），返回属性key。
     *
     * @param bloodType 血种类型
     * @param rngManager 确定性 PRNG 管理器，确保存档/读档一致性。
     */
    fun randomBloodRefineStat(bloodType: String, rngManager: com.xianxia.sect.core.util.GameRngManager): String {
        val rule = BeastMaterialDatabase.BLOOD_RULES[bloodType] ?: return ""
        val rng = rngManager.getRng(com.xianxia.sect.core.util.RngPartition.BREAKTHROUGH)
        val choice = rng.nextInt(2) == 0
        return if (choice) rule.statA else rule.statB
    }

    /**
     * 获取 CombatAttributes 中指定 stat key 的 base 值。
     */
    fun getBaseStatValue(combat: com.xianxia.sect.core.model.CombatAttributes, statKey: String): Int = when (statKey) {
        "speed" -> combat.baseSpeed
        "hp" -> combat.baseHp
        "physicalAttack" -> combat.basePhysicalAttack
        "magicAttack" -> combat.baseMagicAttack
        "physicalDefense" -> combat.basePhysicalDefense
        "magicDefense" -> combat.baseMagicDefense
        else -> 0
    }

    // ==================== 血炼百分比乘区（替代旧绝对值单利计算） ====================

    /**
     * 从血炼百分比累计记录中读取指定属性的累计百分比。
     */
    fun getAccumulatedPct(
        total: BloodRefinementPctTotal?,
        statKey: String
    ): Double {
        if (total == null) return 0.0
        return when (statKey) {
            "speed" -> total.speedBonusPct
            "hp" -> total.hpBonusPct
            "physicalAttack" -> total.physicalAttackBonusPct
            "magicAttack" -> total.magicAttackBonusPct
            "physicalDefense" -> total.physicalDefenseBonusPct
            "magicDefense" -> total.magicDefenseBonusPct
            else -> 0.0
        }
    }

    /**
     * 将本次血炼百分比累加到累计记录中，返回更新后的记录。
     */
    fun addPctToTotal(
        total: BloodRefinementPctTotal,
        statKey: String,
        pct: Double
    ): BloodRefinementPctTotal {
        return when (statKey) {
            "speed" -> total.copy(speedBonusPct = total.speedBonusPct + pct)
            "hp" -> total.copy(hpBonusPct = total.hpBonusPct + pct)
            "physicalAttack" -> total.copy(physicalAttackBonusPct = total.physicalAttackBonusPct + pct)
            "magicAttack" -> total.copy(magicAttackBonusPct = total.magicAttackBonusPct + pct)
            "physicalDefense" -> total.copy(physicalDefenseBonusPct = total.physicalDefenseBonusPct + pct)
            "magicDefense" -> total.copy(magicDefenseBonusPct = total.magicDefenseBonusPct + pct)
            else -> total
        }
    }

    /**
     * 获取属性显示名称
     */
    fun getStatDisplayName(statKey: String): String = when (statKey) {
        "speed" -> "速度"
        "hp" -> "气血"
        "physicalAttack" -> "物攻"
        "magicAttack" -> "法攻"
        "physicalDefense" -> "物防"
        "magicDefense" -> "法防"
        else -> statKey
    }

    // ==================== 父母灵根对子嗣修炼速度的影响 ====================

    /**
     * 根据灵根数量计算父母对子嗣修炼速度的加成比例
     * 单灵根 +10%, 双灵根 +5%, 三灵根 0%, 四灵根 -5%, 五灵根 -10%
     */
    fun getParentSpiritRootBonus(spiritRootCount: Int): Double {
        return when (spiritRootCount) {
            1 -> 0.10
            2 -> 0.05
            3 -> 0.0
            4 -> -0.05
            5 -> -0.10
            else -> 0.0
        }
    }

    /**
     * 计算父母灵根对子嗣修炼速度的总加成
     * 仅存活父母影响，父母各自独立计算
     * @param parent1 父亲（或父母之一），null表示不存在或已故
     * @param parent2 母亲（或父母之一），null表示不存在或已故
     * @return 总加成比例（如 0.20 表示 +20%）
     */
    fun calculateParentCultivationBonus(parent1: Disciple?, parent2: Disciple?): Double {
        var bonus = 0.0
        if (parent1 != null && parent1.isAlive) {
            bonus += getParentSpiritRootBonus(parent1.spiritRoot.types.size)
        }
        if (parent2 != null && parent2.isAlive) {
            bonus += getParentSpiritRootBonus(parent2.spiritRoot.types.size)
        }
        return bonus
    }

    /**
     * 计算父母灵根对子嗣修炼速度的总加成（DiscipleAggregate版本）
     */
    fun calculateParentCultivationBonusForAggregate(parent1: DiscipleAggregate?, parent2: DiscipleAggregate?): Double {
        var bonus = 0.0
        if (parent1 != null && parent1.isAlive) {
            bonus += getParentSpiritRootBonus(parent1.spiritRoot.types.size)
        }
        if (parent2 != null && parent2.isAlive) {
            bonus += getParentSpiritRootBonus(parent2.spiritRoot.types.size)
        }
        return bonus
    }

    // ==================== 亲人逝世影响 ====================

    /**
     * 亲人逝世对修炼速度的惩罚比例：降低50%
     */
    const val GRIEF_CULTIVATION_SPEED_PENALTY = 0.50

    /**
     * 亲人逝世对突破率的惩罚比例：降低20%
     */
    const val GRIEF_BREAKTHROUGH_CHANCE_PENALTY = 0.20

    // ==================== 师徒加成 ====================

    /** 每位师父最多可收徒弟数 */
    const val MAX_APPRENTICES_PER_MASTER = 5

    /** 师徒大境界差每级提供的修炼速度加成：5% */
    const val MASTER_DISCIPLE_CULTIVATION_BONUS_PER_GAP = 0.05

    /** 师徒大境界差每级提供的突破率加成：3% */
    const val MASTER_DISCIPLE_BREAKTHROUGH_BONUS_PER_GAP = 0.03

    /**
     * 计算师父与徒弟之间的大境界差。
     * 境界 Int 值越小境界越高（练气=9, 筑基=8, 金丹=7...）。
     * "隔整境界才算"：金丹师父(7)+练气徒弟(9) 中间隔筑基(8) 一个大境界 → gap=1。
     * 同境界 / 徒弟境界 ≥ 师父境界时 gap=0。
     */
    fun getMasterDiscipleRealmGap(discipleRealm: Int, masterRealm: Int): Int =
        (discipleRealm - masterRealm - 1).coerceAtLeast(0)

    /**
     * 计算徒弟从师父处获得的修炼速度加成（已乘以 gap）。
     */
    fun getMasterDiscipleCultivationBonus(discipleRealm: Int, masterRealm: Int): Double =
        getMasterDiscipleRealmGap(discipleRealm, masterRealm) * MASTER_DISCIPLE_CULTIVATION_BONUS_PER_GAP

    /**
     * 计算徒弟从师父处获得的突破率加成（已乘以 gap）。
     */
    fun getMasterDiscipleBreakthroughBonus(discipleRealm: Int, masterRealm: Int): Double =
        getMasterDiscipleRealmGap(discipleRealm, masterRealm) * MASTER_DISCIPLE_BREAKTHROUGH_BONUS_PER_GAP

    // ==================== 寿命将尽惩罚 ====================

    /** 寿命惩罚阈值：剩余寿命低于此比例时触发 */
    private const val LIFESPAN_PENALTY_THRESHOLD = 0.20
    /** 每低于阈值1个百分点降低5%修炼速度 */
    private const val LIFESPAN_CULTIVATION_PENALTY_PER_PCT = 0.05
    /** 每低于阈值1个百分点降低2%突破率 */
    private const val LIFESPAN_BREAKTHROUGH_PENALTY_PER_PCT = 0.02

    /**
     * 计算剩余寿命百分比（0.0~1.0）
     * lifespan <= 0 时返回 1.0（无惩罚，避免除零）
     */
    fun calculateLifespanRemainingPercent(age: Int, lifespan: Int): Double {
        if (lifespan <= 0) return 1.0
        return ((lifespan - age).coerceAtLeast(0)).toDouble() / lifespan
    }

    /**
     * 计算寿命将尽对修炼速度的惩罚值
     * 剩余寿命低于20%时，每少1个百分点降低5%修炼速度
     * @return 惩罚值（非负数），可直接从 totalBonus 中扣除
     */
    fun calculateLifespanCultivationPenalty(age: Int, lifespan: Int): Double {
        val remaining = calculateLifespanRemainingPercent(age, lifespan)
        if (remaining >= LIFESPAN_PENALTY_THRESHOLD) return 0.0
        val deficitPercent = (LIFESPAN_PENALTY_THRESHOLD - remaining) * 100
        return deficitPercent * LIFESPAN_CULTIVATION_PENALTY_PER_PCT
    }

    /**
     * 计算寿命将尽对突破率的惩罚值
     * 剩余寿命低于20%时，每少1个百分点降低2%突破率
     * @return 惩罚值（非负数），可直接从 totalBonus 中扣除
     */
    fun calculateLifespanBreakthroughPenalty(age: Int, lifespan: Int): Double {
        val remaining = calculateLifespanRemainingPercent(age, lifespan)
        if (remaining >= LIFESPAN_PENALTY_THRESHOLD) return 0.0
        val deficitPercent = (LIFESPAN_PENALTY_THRESHOLD - remaining) * 100
        return deficitPercent * LIFESPAN_BREAKTHROUGH_PENALTY_PER_PCT
    }

    /**
     * 判断弟子是否处于丧亲悲痛期
     * @param griefEndYear 悲痛结束年份，null表示未处于悲痛期
     * @param currentYear 当前游戏年份
     */
    fun isGrieving(griefEndYear: Int?, currentYear: Int): Boolean {
        return griefEndYear != null && currentYear < griefEndYear
    }

    // ==================== 亲属关系判定 ====================

    /**
     * 判断两个弟子是否为亲属关系（道侣、父母/子嗣、兄弟姐妹）
     * 用于丧亲悲痛系统
     */
    fun areRelatives(a: Disciple, b: Disciple): Boolean {
        // 道侣关系
        if (a.social.partnerId == b.id || b.social.partnerId == a.id) return true

        // 父母-子女关系：a是b的父母 或 b是a的父母
        if (a.id == b.social.parentId1 || a.id == b.social.parentId2) return true
        if (b.id == a.social.parentId1 || b.id == a.social.parentId2) return true

        // 兄弟姐妹关系：有共同父母（支持单亲匹配）
        val aParents = setOfNotNull(a.social.parentId1, a.social.parentId2)
        val bParents = setOfNotNull(b.social.parentId1, b.social.parentId2)
        if (aParents.isNotEmpty() && aParents.intersect(bParents).isNotEmpty()) return true

        return false
    }

    /**
     * 为所有存活亲属设置悲痛期（持续1年），支持多个逝者批量处理
     * @param disciples 当前弟子列表
     * @param deceasedList 阵亡/逝世的弟子列表
     * @param currentYear 当前游戏年份
     * @return 更新后的弟子列表
     */
    fun applyGriefToRelatives(
        disciples: List<Disciple>,
        deceasedList: List<Disciple>,
        currentYear: Int
    ): List<Disciple> {
        val griefEndYear = currentYear + 1
        var updated = disciples
        for (deceased in deceasedList) {
            updated = updated.map { d ->
                if (!d.isAlive || d.id == deceased.id) return@map d
                if (areRelatives(d, deceased)) {
                    val existingGriefEnd = d.social.griefEndYear
                    val newGriefEnd = if (existingGriefEnd != null && existingGriefEnd > griefEndYear) existingGriefEnd else griefEndYear
                    d.copy(social = d.social.copy(griefEndYear = newGriefEnd))
                } else {
                    d
                }
            }
        }
        return updated
    }

    /**
     * 计算所有存活亲属的悲痛结束年份映射。
     *
     * 与 [applyGriefToRelatives] 逻辑相同，但返回 `Map<Int, Int>`
     * 而非全量 `List<Disciple>`，便于直接列写入 griefEndYears。
     *
     * @param disciples 当前弟子列表
     * @param deceasedList 阵亡/逝世的弟子列表
     * @param currentYear 当前游戏年份
     * @return Map<Int, Int> 弟子ID → griefEndYear（仅包含需更新的弟子）
     */
    fun computeGriefEndYearMap(
        disciples: List<Disciple>,
        deceasedList: List<Disciple>,
        currentYear: Int
    ): Map<Int, Int> {
        val griefEndYear = currentYear + 1
        val result = mutableMapOf<Int, Int>()
        for (deceased in deceasedList) {
            for (d in disciples) {
                if (!d.isAlive || d.id == deceased.id) continue
                if (areRelatives(d, deceased)) {
                    val idInt = d.id.toIntOrNull() ?: continue
                    val existingGriefEnd = d.social.griefEndYear
                    val newGriefEnd = if (existingGriefEnd != null && existingGriefEnd > griefEndYear) existingGriefEnd else griefEndYear
                    // 取最大值（多个逝者时取最长的悲痛期）
                    val currentMax = result[idInt]
                    if (currentMax == null || newGriefEnd > currentMax) {
                        result[idInt] = newGriefEnd
                    }
                }
            }
        }
        return result
    }
}
