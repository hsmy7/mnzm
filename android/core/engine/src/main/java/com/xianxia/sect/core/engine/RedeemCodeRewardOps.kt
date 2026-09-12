package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.ItemNames
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleRewardConfig
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.RedeemRewardType
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.baseHp
import com.xianxia.sect.core.model.baseMagicAttack
import com.xianxia.sect.core.model.baseMagicDefense
import com.xianxia.sect.core.model.baseMp
import com.xianxia.sect.core.model.basePhysicalAttack
import com.xianxia.sect.core.model.basePhysicalDefense
import com.xianxia.sect.core.model.baseSpeed
import com.xianxia.sect.core.model.charm
import com.xianxia.sect.core.model.comprehension
import com.xianxia.sect.core.model.hpVariance
import com.xianxia.sect.core.model.intelligence
import com.xianxia.sect.core.model.loyalty
import com.xianxia.sect.core.model.magicAttackVariance
import com.xianxia.sect.core.model.magicDefenseVariance
import com.xianxia.sect.core.model.mining
import com.xianxia.sect.core.model.morality
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.model.mpVariance
import com.xianxia.sect.core.model.physicalAttackVariance
import com.xianxia.sect.core.model.physicalDefenseVariance
import com.xianxia.sect.core.model.pillRefining
import com.xianxia.sect.core.model.speedVariance
import com.xianxia.sect.core.model.spiritPlanting
import com.xianxia.sect.core.model.teaching
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.engine.RedeemCodeManager.DiscipleBuildContext

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── 兑换码奖励生成域（自 RedeemCodeManager 拆出，行为零变更） ─────────────────

private val TAG = RedeemCodeManager.TAG
/** 物品类奖励生成：EQUIPMENT/MANUAL/PILL/MATERIAL/HERB/SEED 分支 */
internal fun RedeemCodeManager.addItemRewards(
    type: RedeemRewardType,
    rarity: Int,
    quantity: Int,
    random: kotlin.random.Random,
    rewards: MutableList<RewardSelectedItem>
) {
    // quantity 负数时 repeat 零次迭代 →
    // 零奖励但兑换码照常消耗（静默吞码）；coerceAtLeast(1) 兜底
    repeat(quantity.coerceAtLeast(1)) {
        val (id, name, rarity) = generateSingleItemReward(type, rarity, random)
        rewards.add(
            RewardSelectedItem(
                id = id,
                type = type.name.lowercase(),
                name = name,
                rarity = rarity,
                quantity = 1
            )
        )
    }
    DomainLog.d(
        TAG,
        "Generated $quantity ${type.name.lowercase()}(s) with rarity $rarity"
    )
}

/** 弟子奖励生成：DISCIPLE 分支 */

internal fun RedeemCodeManager.addDiscipleRewards(
    config: DiscipleRewardConfig?,
    quantity: Int,
    existingNames: Set<String>,
    random: kotlin.random.Random,
    disciples: MutableList<Disciple>,
    rewards: MutableList<RewardSelectedItem>
) {
    /** 结构数量（与 [SpriteAtlasDef.STRUCTURES] 同序同量）。 */
    val count = quantity.coerceAtLeast(1)
    val usedNames = existingNames.toMutableSet()
    repeat(count) {
        val d = generateDisciple(config, usedNames, random = random)
        disciples.add(d)
        usedNames.add(d.name)
        rewards.add(
            RewardSelectedItem(
                id = d.id,
                type = "disciple",
                name = d.name,
                rarity = 1,
                quantity = 1
            )
        )
    }
    DomainLog.d(TAG, "Generated $count disciple(s) with config: $config")
}

/** 新手包奖励生成：STARTER_PACK 分支 */

internal fun RedeemCodeManager.addStarterPackRewards(
    existingNames: Set<String>,
    random: kotlin.random.Random,
    disciples: MutableList<Disciple>,
    rewards: MutableList<RewardSelectedItem>
) {
    rewards.add(
        RewardSelectedItem(
            id = "spiritStones",
            type = "spiritStones",
            name = ItemNames.SPIRIT_STONE,
            rarity = 1,
            quantity = 10000000
        )
    )
    DomainLog.d(TAG, "Generated spirit stones reward: 10000000")
    val starterUsedNames = existingNames.toMutableSet()
    repeat(5) {
        val singleRootDisciple = generateDisciple(
            DiscipleRewardConfig(
                spiritRootCount = 1,
                loyalty = 80
            ),
            starterUsedNames,
            random = random
        )
        disciples.add(singleRootDisciple)
        starterUsedNames.add(singleRootDisciple.name)
        rewards.add(
            RewardSelectedItem(
                id = singleRootDisciple.id,
                type = "disciple",
                name = singleRootDisciple.name,
                rarity = 1,
                quantity = 1
            )
        )
    }
    DomainLog.d(TAG, "Generated 5 single spirit root disciples")
}

/** 功法包奖励生成：MANUAL_PACK 分支 */

internal fun RedeemCodeManager.addManualPackRewards(
    random: kotlin.random.Random,
    rewards: MutableList<RewardSelectedItem>
) {
    val rarities = listOf(1, 2, 3, 4)
    rarities.forEach { targetRarity ->
        val templates = ManualDatabase.getByRarity(targetRarity)
        repeat(30) {
            val template = templates[random.nextInt(templates.size)]
            val manual = ManualDatabase.createFromTemplate(template)
            rewards.add(
                RewardSelectedItem(
                    id = manual.id,
                    type = "manual",
                    name = manual.name,
                    rarity = manual.rarity,
                    quantity = 1
                )
            )
        }
    }
    DomainLog.d(TAG, "Generated manual pack: 30 manuals for each rarity 1-4")
}

internal fun RedeemCodeManager.generateRandomEquipment(rarity: Int,
    random: kotlin.random.Random = kotlin.random.Random): EquipmentStack {
    return EquipmentDatabase.generateRandom(minRarity = rarity, maxRarity = rarity, random = random)
}

/**
 * 单物品类奖励生成（统一 6 种物品类型的生成器分派）。
 *
 * @return Triple(id, name, rarity)——RewardSelectedItem 构造所需字段
 */

internal fun RedeemCodeManager.generateSingleItemReward(
    type: RedeemRewardType,
    rarity: Int,
    random: kotlin.random.Random
): Triple<String, String, Int> = when (type) {
    RedeemRewardType.EQUIPMENT -> {
        val equipment = generateRandomEquipment(rarity, random)
        Triple(equipment.id, equipment.name, equipment.rarity)
    }
    RedeemRewardType.MANUAL -> {
        val manual = ManualDatabase.generateRandom(minRarity = rarity, maxRarity = rarity, random = random)
        Triple(manual.id, manual.name, manual.rarity)
    }
    RedeemRewardType.PILL -> {
        val pill = ItemDatabase.generateRandomPill(minRarity = rarity, maxRarity = rarity, random = random)
        Triple(pill.id, pill.name, pill.rarity)
    }
    RedeemRewardType.MATERIAL -> {
        val material = ItemDatabase.generateRandomMaterial(minRarity = rarity, maxRarity = rarity, random = random)
        Triple(material.id, material.name, material.rarity)
    }
    RedeemRewardType.HERB -> {
        val herb = HerbDatabase.generateRandomHerb(minRarity = rarity, maxRarity = rarity, random = random)
        Triple(herb.id, herb.name, herb.rarity)
    }
    RedeemRewardType.SEED -> {
        val seed = HerbDatabase.generateRandomSeed(minRarity = rarity, maxRarity = rarity, random = random)
        Triple(seed.id, seed.name, seed.rarity)
    }
    else -> error("generateSingleItemReward 仅支持物品类奖励，实际: $type")
}

/** 弟子主体构建：构造 + 基础属性结算 */
internal fun RedeemCodeManager.buildRedeemDisciple(
    cfg: DiscipleRewardConfig,
    context: DiscipleBuildContext,
    random: kotlin.random.Random
): Disciple {
    val talents = TalentDatabase.getTalentsByIds(context.idBundle.talentIds)
    // 寿命加成口径并入词条（同出生/突破口径对齐）——
    // 只算天赋会使带"延年"词条兑换弟子 lifespan 低于特质加成水平
    val lifespanBonus = talents.sumOf { it.effects["lifespan"] ?: 0.0 } +
        (AffixDatabase.calculateAffixEffects(context.idBundle.affixIds)["lifespan"] ?: 0.0)

    return Disciple(
        name = context.nameResult.fullName,
        surname = context.nameResult.surname,
        realm = cfg.realm,
        realmLayer = cfg.realmLayer,
        spiritRootType = context.spiritRootType,
        age = context.age,
        lifespan = (context.lifespan * (1.0 + lifespanBonus)).toInt(),
        gender = context.gender,
        portraitRes = PortraitPool.getRandomPortrait(context.gender) { random.nextInt(it) },
        discipleType = "outer",
        talentIds = context.idBundle.talentIds,
        physiqueIds = context.idBundle.physiqueIds,
        affixIds = context.idBundle.affixIds,
        combat = CombatAttributes(
            hpVariance = context.variance.hpVariance,
            mpVariance = context.variance.mpVariance,
            physicalAttackVariance = context.variance.physicalAttackVariance,
            magicAttackVariance = context.variance.magicAttackVariance,
            physicalDefenseVariance = context.variance.physicalDefenseVariance,
            magicDefenseVariance = context.variance.magicDefenseVariance,
            speedVariance = context.variance.speedVariance
        ),
        skills = buildRedeemSkills(cfg = cfg, spiritRootType = context.spiritRootType, random = random)
    ).apply {
        val baseStats = Disciple.calculateBaseStatsWithVariance(
            context.variance.hpVariance, context.variance.mpVariance,
            context.variance.physicalAttackVariance, context.variance.magicAttackVariance,
            context.variance.physicalDefenseVariance, context.variance.magicDefenseVariance,
            context.variance.speedVariance
        )
        combat.baseHp = baseStats.baseHp
        combat.baseMp = baseStats.baseMp
        combat.basePhysicalAttack = baseStats.basePhysicalAttack
        combat.baseMagicAttack = baseStats.baseMagicAttack
        combat.basePhysicalDefense = baseStats.basePhysicalDefense
        combat.baseMagicDefense = baseStats.baseMagicDefense
        combat.baseSpeed = baseStats.baseSpeed
    }
}

/** 弟子技能属性生成：SkillStats 构建，RNG 调用序与原一致 */

internal fun RedeemCodeManager.buildRedeemSkills(
    cfg: DiscipleRewardConfig,
    spiritRootType: String,
    random: kotlin.random.Random
): SkillStats {
    val spiritRootCount = spiritRootType.split(",").size
    return SkillStats(
        intelligence = cfg.intelligence ?: 1 + random.nextInt(GameConfig.Disciple.SKILL_MAX),
        comprehension = cfg.comprehension ?: rollBySpiritRootCount(
            spiritRootCount = spiritRootCount,
            random = random
        ),
        charm = cfg.charm ?: 1 + random.nextInt(GameConfig.Disciple.SKILL_MAX),
        loyalty = cfg.loyalty ?: 1 + random.nextInt(GameConfig.Disciple.MAX_LOYALTY),
        artifactRefining = cfg.artifactRefining ?: 1 + random.nextInt(GameConfig.Disciple.SKILL_MAX),
        pillRefining = cfg.pillRefining ?: 1 + random.nextInt(GameConfig.Disciple.SKILL_MAX),
        spiritPlanting = cfg.spiritPlanting ?: 1 + random.nextInt(GameConfig.Disciple.SKILL_MAX),
        mining = cfg.mining ?: 1 + random.nextInt(GameConfig.Disciple.SKILL_MAX),
        teaching = cfg.teaching ?: 1 + random.nextInt(GameConfig.Disciple.SKILL_MAX),
        morality = cfg.morality ?: 1 + random.nextInt(GameConfig.Disciple.SKILL_MAX),
        // 资质：按灵根阶梯生成（固定属性，配置不覆盖，最小改动；避开哨兵 50 防自愈误判）
        aptitude = avoidSentinel50(
            rollBySpiritRootCount(
                spiritRootCount = spiritRootCount,
                random = random
            )
        )
    )
}

/** 灵根阶梯属性掷点：单灵根 80+ 起逐级降 20，RNG 调用序与原一致 */

internal fun RedeemCodeManager.rollBySpiritRootCount(
    spiritRootCount: Int,
    random: kotlin.random.Random
): Int = when (spiritRootCount) {
    1 -> 80 + random.nextInt(21)
    2 -> 60 + random.nextInt(21)
    3 -> 40 + random.nextInt(21)
    4 -> 20 + random.nextInt(21)
    else -> 1 + random.nextInt(20)
}

/** 灵根类型解析（配置指定/数量随机/默认生成，RNG 调用序与原一致）。 */

internal fun RedeemCodeManager.resolveSpiritRoot(
    cfg: DiscipleRewardConfig,
    random: kotlin.random.Random
): String {
    val cfgSpiritRootType = cfg.spiritRootType
    // spiritRootCount ≤ 0 会产生空灵根字符串 ""
    // （0 灵根弟子数据错误）；coerceAtLeast(1) 兜底为单灵根
    val cfgSpiritRootCount = cfg.spiritRootCount?.coerceAtLeast(1)
    return if (cfgSpiritRootType != null && cfgSpiritRootCount != null) {
        val types = listOf("metal", "wood", "water", "fire", "earth")
        val baseType = cfgSpiritRootType
        if (cfgSpiritRootCount == 1) {
            baseType
        } else {
            val additionalTypes = types.filter { it != baseType }.shuffled(java.util.Random(random.nextInt()
                .toLong())).take(cfgSpiritRootCount - 1)
            (listOf(baseType) + additionalTypes).joinToString(",")
        }
    } else if (cfgSpiritRootCount != null) {
        val types = listOf("metal", "wood", "water", "fire", "earth")
        types.shuffled(java.util.Random(random.nextInt().toLong())).take(cfgSpiritRootCount).joinToString(",")
    } else {
        SpiritRootGenerator.generate(random)
    }
}

/** 年龄与基础寿命解析（含 ±10% 寿命波动，RNG 调用序与原一致）。 */

internal fun RedeemCodeManager.resolveAgeAndLifespan(
    cfg: DiscipleRewardConfig,
    random: kotlin.random.Random
): Pair<Int, Int> {
    // minAge > maxAge 时 nextInt(负数) 抛
    // IllegalArgumentException 崩溃（服务端 config 可注入）；改为 >= 直接取 minAge
    val age = if (cfg.minAge >= cfg.maxAge) {
        cfg.minAge
    } else {
        cfg.minAge + random.nextInt(cfg.maxAge - cfg.minAge + 1)
    }
    val realmConfig = GameConfig.Realm.get(cfg.realm)
    // ±10% 波动下限侧（×0.9）不得低于境界基准寿元——
    // 与出生/突破口径（不低于 realmMaxAge）对齐，防 lifespan 恒落后触发截断死循环
    val lifespan = (realmConfig.maxAge * (1.0 + (-0.1 + random.nextDouble() * 0.2)))
        .toInt().coerceAtLeast(realmConfig.maxAge)
    return age to lifespan
}

/** 天赋 ID 解析（配置指定或随机生成）。 */

internal fun RedeemCodeManager.resolveTalentIds(
    cfg: DiscipleRewardConfig,
    random: kotlin.random.Random
): List<String> = if (cfg.talentIds.isNotEmpty()) {
    cfg.talentIds
} else {
    generateRandomTalents(random = random)
}

/** 属性方差生成（-50..50，替代原逐行重复的 nextInt 表达式）。 */

internal fun RedeemCodeManager.generateVariance(random: kotlin.random.Random): Int = -50 + random.nextInt(101)

/** 资质生成避开哨兵值 50（==50 强制 +1）：与 [DiscipleTables.healDefaultAptitudes] 收敛逻辑一致，防自愈误判 */

internal fun RedeemCodeManager.avoidSentinel50(roll: Int): Int =
    if (roll == DiscipleTables.DEFAULT_APTITUDE) DiscipleTables.DEFAULT_APTITUDE + 1 else roll

/** 生成随机天赋（internal 供测试验证；统一走 TalentDatabase 的弟子分布，与玩家招募一致） */
