package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.profession.ProfessionRules
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.pillRefining
import com.xianxia.sect.core.model.spiritPlanting
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.ZoneCalculator
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.repository.getSlotsByBuildingId
import com.xianxia.sect.core.engine.domain.disciple.getBaseStats
import com.xianxia.sect.core.engine.domain.disciple.getPositionEffectBonus



@GameService("FormulaService")
@Singleton
class FormulaService @Inject constructor(
    private val stateStore: GameStateStore,
    private val productionSlotRepository: ProductionSlotRepository
) {
    // ==================== 数据类定义 ====================

    /**
     * 生产成功率乘区（Success Rate Zone）。
     *
     * 当前公式规则：配方不提供基础成功率（恒 0），
     * 基础率由**工作弟子属性 + 职业等级**合成：
     *
     * ```
     * baseProb = clamp01( baseRate + skillZone + professionZone )
     * final    = clamp01( baseProb × (1 + realmZone + talentZone + policyZone + elderZone) )
     * ```
     *
     * - [baseRate]：外部传入的基础率（当前恒为 0，保留字段兼容调用方）
     * - [skillZone]：炼丹/锻造属性加成 `(skill-30)×0.006`，clamp ≤ 0.50
     * - [professionZone]：职业加成，每低一阶 +0.20（五品炼凡品光职业即 100%）
     */
    data class SuccessRateZones(
        val baseRate: Double = 0.0,        // 外部基础率（配方值不再参与，恒 0）
        val skillZone: Double = 0.0,       // 工作弟子炼丹/锻造属性加成（基础率组成）
        val professionZone: Double = 0.0,  // 职业等级加成（基础率组成，每低一阶 +0.20）
        val realmZone: Double = 0.0,       // 境界乘区
        val talentZone: Double = 0.0,      // 天赋乘区
        val policyZone: Double = 0.0,      // 政策乘区
        val elderZone: Double = 0.0,       // 长老职位乘区
    ) {
        /** 使用乘区法计算最终成功率，clamp 到 [0, 1] */
        fun calculate(): Double {
            val baseProb = (baseRate + skillZone + professionZone).coerceIn(0.0, 1.0)
            return ZoneCalculator.calculateProbability(
                baseProb = baseProb,
                positiveSum = realmZone + talentZone + policyZone + elderZone
            )
        }
    }

    /**
     * 生产持续时间乘区（Duration Zone）。
     *
     * 公式：effectiveDuration = baseDuration / Π(1 + speedZone_i)
     * 各速度乘区（skill/policy/elder）以加速比例表示。
     */
    data class DurationZones(
        val baseDuration: Int = 0,          // 基础持续时间（月）
        val skillZone: Double = 0.0,        // 弟子技能乘区
        val policyZone: Double = 0.0,       // 政策乘区
        val elderZone: Double = 0.0,        // 长老职位乘区
    ) {
        /** 使用乘区法计算缩减后的持续时间 */
        fun calculateReduced(): Int =
            ZoneCalculator.calculateAcceleratedTime(
                baseDuration, skillZone, policyZone, elderZone
            )
    }

    /**
     * 长老加成数据类
     */
    data class ElderBonusData(
        val speedBonus: Double,
        val successBonus: Double,
        val yieldBonus: Double
    )

    // ==================== 成功率计算 ====================

    /**
     * 构建生产成功率乘区。
     *
     * 按 [buildingId] 分拣炼丹/锻造，读取工作弟子的
     * 对应技能（pillRefining/artifactRefining）与职业等级（alchemyLevel/forgeLevel），
     * 计算 [SuccessRateZones.skillZone] 与 [SuccessRateZones.professionZone]；
     * 配方 successRate 不参与（[baseRate] 恒 0）。
     *
     * @param disciple 工作弟子（槽位无弟子时 null，无属性/职业加成）
     * @param buildingId 建筑 ID（仅炼丹/锻造建筑有属性与职业加成）
     * @param recipeTier 配方品阶（职业加成 = 可炼最高阶 - 配方品阶，每低一阶 +0.20）
     * @param baseRate 外部基础率（默认 0，配方值不参与成功率）
     * @param policyBonus 政策加成（丹道/锻造激励 +10%）
     */
    fun buildSuccessRateZones(
        disciple: Disciple?,
        buildingId: String,
        recipeTier: Int = 1,
        baseRate: Double = 0.0,
        policyBonus: Double = 0.0
    ): SuccessRateZones {
        if (disciple == null) {
            return SuccessRateZones(baseRate = baseRate, policyZone = policyBonus)
        }
        val (skill, level) = when (buildingId) {
            BuildingNames.ALCHEMY -> disciple.skills.pillRefining to disciple.skills.alchemyLevel
            BuildingNames.FORGE -> disciple.skills.artifactRefining to disciple.skills.forgeLevel
            else -> 0 to 0
        }
        // Long 运算防溢出：skill=Int.MIN_VALUE 时 Int 减法溢出为正，
        // 会错误获得满属性加成
        val skillZone = ((skill.toLong() - ProfessionRules.SKILL_ZONE_BASELINE).coerceAtLeast(0L) *
            ProfessionRules.SKILL_ZONE_RATE).coerceAtMost(ProfessionRules.SKILL_ZONE_MAX)
        val professionZone = (ProfessionRules.maxCraftableTier(level) - recipeTier).coerceAtLeast(0) *
            ProfessionRules.PROFESSION_ZONE_PER_TIER
        return SuccessRateZones(
            baseRate = baseRate,
            skillZone = skillZone,
            professionZone = professionZone,
            realmZone = getRealmSuccessRateBonus(disciple.realm),
            talentZone = getSuccessRateTalentBonus(disciple, buildingId),
            policyZone = policyBonus,
            elderZone = getElderPositionBonus(buildingId)
        )
    }

    /**
     * 计算生产成功率加成
     *
     * @param弟子 执行生产的弟子（可为空）
     * @param buildingId 建筑ID
     * @return 总成功率加成（0.0-1.0）
     */
    fun calculateSuccessRateBonus(disciple: Disciple?, buildingId: String): Double {
        if (disciple == null) return 0.0

        var bonus = 0.0

        bonus += getRealmSuccessRateBonus(disciple.realm)

        bonus += getSuccessRateTalentBonus(disciple, buildingId)

        // 功法成功率加成预留项：现恒为 0（与 C++ production 公式一致，无该项），占位函数已删
        return bonus
    }

    /**
     * 根据境界获取成功率加成
     *
     * @param realm 境界等级
     * @return 成功率加成
     */
    private fun getRealmSuccessRateBonus(realm: Int): Double {
        return when (realm) {
            0 -> 0.30  // 仙人 +30%
            1 -> 0.25  // 渡劫 +25%
            2 -> 0.22  // 大乘 +22%
            3 -> 0.19  // 合体 +19%
            4 -> 0.16  // 炼虚 +16%
            5 -> 0.13  // 化神 +13%
            6 -> 0.10  // 元婴 +10%
            7 -> 0.07  // 金丹 +7%
            8 -> 0.04  // 筑基 +4%
            else -> 0.0 // 炼气 0%
        }
    }

    /**
     * 获取成功率相关天赋加成
     *
     * @param disciple 弟子对象
     * @param buildingId 建筑ID
     * @return 天赋加成
     */
    private fun getSuccessRateTalentBonus(disciple: Disciple, buildingId: String): Double {
        val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
        val breakthroughBonus = (talentEffects["breakthroughChance"] ?: 0.0) * 0.80
        val craftFlatBonus = getBuildingCraftFlatBonus(talentEffects, buildingId) * 0.006
        return breakthroughBonus + craftFlatBonus
    }

    /**
     * 获取建筑工艺固定加成
     *
     * @param talentEffects 天赋效果映射
     * @param buildingId 建筑ID
     * @return 固定加成值
     */
    @Suppress("UnusedParameter") // disciple: 语义形参：签名表达 API 决策域（调用点可读性与协议完整性优先），当前策略不消费
    private fun getBuildingCraftFlatBonus(talentEffects: Map<String, Double>, buildingId: String): Double {
        return when (buildingId) {
            BuildingNames.ALCHEMY -> talentEffects["pillRefiningFlat"] ?: 0.0
            BuildingNames.FORGE -> talentEffects["artifactRefiningFlat"] ?: 0.0
            "herbGarden" -> talentEffects["spiritPlantingFlat"] ?: 0.0
            else -> 0.0
        }
    }

    // ==================== 工作时间计算 ====================

    /**
     * 计算所有弟子的工作持续时间加成
     *
     * @param baseDuration 基础持续时间（月）
     * @param buildingId 建筑ID
     * @return 实际持续时间（月）
     */
    fun calculateWorkDurationWithAllDisciples(baseDuration: Int, buildingId: String): Int {
        var skillSpeedBonus = 0.0
        var policyTimePenalty = 0.0
        val data = stateStore.gameData.value

        when (buildingId) {
            BuildingNames.ALCHEMY -> {
                val elderBonus = calculateElderAndDisciplesBonus(BuildingNames.ALCHEMY)
                skillSpeedBonus += elderBonus.speedBonus
                // 丹道激励政策：时间+10%
                if (data.sectPolicies.alchemyIncentive) {
                    policyTimePenalty += GameConfig.PolicyConfig.ALCHEMY_TIME_PENALTY
                }
            }
            BuildingNames.FORGE -> {
                val elderBonus = calculateElderAndDisciplesBonus(BuildingNames.FORGE)
                skillSpeedBonus += elderBonus.speedBonus
                // 锻造激励政策：时间+10%
                if (data.sectPolicies.forgeIncentive) {
                    policyTimePenalty += GameConfig.PolicyConfig.FORGE_TIME_PENALTY
                }
            }
            else -> {
                val allBuildingSlots = productionSlotRepository.getSlotsByBuildingId(buildingId)
                val assignedDiscipleIds = allBuildingSlots.mapNotNull { it.assignedDiscipleId }
                if (assignedDiscipleIds.isNotEmpty()) {
                    skillSpeedBonus += getElderPositionBonus(buildingId)
                }
            }
        }

        val zones = DurationZones(
            baseDuration = baseDuration,
            skillZone = skillSpeedBonus,
            elderZone = getElderPositionBonus(buildingId)
        )
        val baseResult = zones.calculateReduced()
        // 政策时间惩罚：在速度加成计算完之后额外增加
        return if (policyTimePenalty > 0.0) {
            (baseResult * (1.0 + policyTimePenalty)).roundToInt().coerceAtLeast(baseResult)
        } else baseResult
    }

    // ==================== 长老职位加成 ====================

    /**
     * 获取长老职位对建筑的速度加成
     *
     * @param buildingId 建筑ID
     * @return 速度加成
     */
    private fun getElderPositionBonus(buildingId: String): Double {
        // Early return for unsupported building types
        if (buildingId !in listOf(BuildingNames.FORGE, BuildingNames.ALCHEMY, "herbGarden")) return 0.0

        // Check if there is an elder assigned to this building type
        val data = stateStore.gameData.value
        val elderSlots = data.elderSlots

        val elderDiscipleId = when (buildingId) {
            BuildingNames.FORGE -> elderSlots.forgeElder
            BuildingNames.ALCHEMY -> elderSlots.alchemyElder
            "herbGarden" -> elderSlots.herbGardenElder
            else -> null
        }

        val resolvedElderDiscipleId = elderDiscipleId ?: return 0.0

        val elderDisciple = stateStore.disciples.value.find { it.id == resolvedElderDiscipleId } ?: return 0.0

        // 读含天赋 Flat 加成的属性（getBaseStats），否则
        // "灵植/炼器/炼丹+18"天赋只对成功率生效，长老产量加成恒为 0
        val stats = DiscipleStatCalculator.getBaseStats(elderDisciple)

        return when (buildingId) {
            BuildingNames.FORGE -> {
                val baseline = GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
                val diff = (stats.artifactRefining - baseline).coerceAtLeast(0)
                // 体质/词条的职务加成：作为乘算因子作用于长老职能效果
                val posBonus = DiscipleStatCalculator.getPositionEffectBonus(elderDisciple, ElderSlotType.FORGE)
                diff * 0.01 * (1.0 + posBonus)
            }
            BuildingNames.ALCHEMY -> {
                val baseline = GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
                val diff = (stats.pillRefining - baseline).coerceAtLeast(0)
                val posBonus = DiscipleStatCalculator.getPositionEffectBonus(elderDisciple, ElderSlotType.ALCHEMY)
                diff * 0.01 * (1.0 + posBonus)
            }
            "herbGarden" -> {
                val baseline = GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
                val diff = (stats.spiritPlanting - baseline).coerceAtLeast(0)
                val posBonus = DiscipleStatCalculator.getPositionEffectBonus(elderDisciple, ElderSlotType.HERB_GARDEN)
                diff * 0.01 * (1.0 + posBonus)
            }
            else -> 0.0
        }
    }

    // ==================== 长老和弟子综合加成 ====================

    /**
     * 计算长老和亲传弟子的综合加成
     *
     * @param buildingType 建筑类型
     * @return 长老加成数据（包含速度、成功率和产量加成）
     */
    fun calculateElderAndDisciplesBonus(buildingType: String): ElderBonusData {
        val data = stateStore.gameData.value
        val (elderId, discipleSlots) = resolveElderSlotsForBuilding(data, buildingType)
            ?: return ElderBonusData(0.0, 0.0, 0.0)

        val elder = elderId?.let { stateStore.disciples.value.find { d -> d.id == it } }
        val disciples = discipleSlots.mapNotNull { slot ->
            slot.discipleId?.let { id -> stateStore.disciples.value.find { d -> d.id == id } }
        }

        var yieldBonus = 0.0
        var speedBonus = 0.0
        var successBonus = 0.0

        when (buildingType) {
            "herbGarden" -> {
                val (speed, success) = accumulateElderAndDiscipleBonus(
                    elder, disciples,
                    { DiscipleStatCalculator.getBaseStats(it).spiritPlanting },
                    ElderSlotType.HERB_GARDEN, elderBonusGoesToSpeed = true
                )
                speedBonus += speed; successBonus += success
            }
            BuildingNames.ALCHEMY -> {
                val (speed, success) = accumulateElderAndDiscipleBonus(
                    elder, disciples,
                    { DiscipleStatCalculator.getBaseStats(it).pillRefining },
                    ElderSlotType.ALCHEMY, elderBonusGoesToSpeed = false
                )
                speedBonus += speed; successBonus += success
            }
            BuildingNames.FORGE -> {
                val (speed, success) = accumulateElderAndDiscipleBonus(
                    elder, disciples,
                    { DiscipleStatCalculator.getBaseStats(it).artifactRefining },
                    ElderSlotType.FORGE, elderBonusGoesToSpeed = false
                )
                speedBonus += speed; successBonus += success
            }
        }

        return ElderBonusData(speedBonus, successBonus, yieldBonus)
    }
}

/** 建筑长老/亲传槽位解析：灵矿与未知建筑不参与（null） */
private fun resolveElderSlotsForBuilding(
    data: GameData,
    buildingType: String
): Pair<String?, List<DirectDiscipleSlot>>? = when (buildingType) {
    "spiritMine" -> null
    "herbGarden" -> data.elderSlots.herbGardenElder to data.elderSlots.herbGardenDisciples
    BuildingNames.ALCHEMY -> data.elderSlots.alchemyElder to data.elderSlots.alchemyDisciples
    BuildingNames.FORGE -> data.elderSlots.forgeElder to data.elderSlots.forgeDisciples
    else -> null
}

/** 单建筑长老+亲传弟子加成聚合：返回 (速度增量, 成功率增量) */
private fun accumulateElderAndDiscipleBonus(
    elder: Disciple?,
    disciples: List<Disciple>,
    statSelector: (Disciple) -> Int,
    slotType: ElderSlotType,
    elderBonusGoesToSpeed: Boolean
): Pair<Double, Double> {
    val elderBaseline = 80
    val discipleBaseline = 80
    /** 当前速度：0=暂停, 1=1x, 2=2x */
    var speed = 0.0
    var success = 0.0

    elder?.let { e ->
        val base = statSelector(e)
        val posBonus = DiscipleStatCalculator.getPositionEffectBonus(e, slotType)
        val bonus = (base - elderBaseline).coerceAtLeast(0) * 0.01 * (1.0 + posBonus)
        if (elderBonusGoesToSpeed) speed += bonus else success += bonus
    }
    disciples.forEach { d ->
        val base = statSelector(d)
        speed += (base - discipleBaseline).coerceAtLeast(0) * 0.01
    }
    return speed to success
}
