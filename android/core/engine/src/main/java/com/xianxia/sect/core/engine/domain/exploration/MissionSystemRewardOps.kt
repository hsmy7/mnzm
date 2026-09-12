package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MissionRewardConfig
import com.xianxia.sect.core.model.MissionTemplate
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.util.RngRandomAdapter
import com.xianxia.sect.core.engine.domain.exploration.MissionSystem.WeightedEntry

// ── 任务奖励配置与生成域（自 MissionSystem 拆出，行为零变更） ────────────────
internal fun MissionSystem.createRewardConfig(template: MissionTemplate): MissionRewardConfig {
    return createTier1RewardConfig(template = template)
        ?: createTier2RewardConfig(template = template)
        ?: createTier3RewardConfig(template = template)
        ?: createTier4RewardConfig(template = template)
        ?: error("createRewardConfig 未覆盖的任务类型: $template")
}

/** 低阶任务奖励配置 */

internal fun MissionSystem.createTier1RewardConfig(template: MissionTemplate): MissionRewardConfig? = when (template) {
    MissionTemplate.ESCORT_CARAVAN -> MissionRewardConfig(
        spiritStones = 600
    )
    MissionTemplate.PATROL_TERRITORY -> MissionRewardConfig(
        spiritStones = 300,
        materialCountMin = 5,
        materialCountMax = 10,
        materialMinRarity = 1,
        materialMaxRarity = 1
    )
    MissionTemplate.DELIVER_SUPPLIES -> MissionRewardConfig(
        spiritStones = 400,
        pillCountMin = 1,
        pillCountMax = 2,
        pillMinRarity = 1,
        pillMaxRarity = 1
    )
    MissionTemplate.SUPPRESS_LOW_BEASTS -> MissionRewardConfig(
        spiritStones = 400,
        materialCountMin = 10,
        materialCountMax = 15,
        materialMinRarity = 1,
        materialMaxRarity = 1
    )
    MissionTemplate.CLEAR_BANDITS -> MissionRewardConfig(
        spiritStones = 500,
        materialCountMin = 8,
        materialCountMax = 12,
        materialMinRarity = 1,
        materialMaxRarity = 1,
        equipmentChance = 0.3,
        equipmentMinRarity = 1,
        equipmentMaxRarity = 1
    )
    MissionTemplate.EXPLORE_ABANDONED_MINE -> MissionRewardConfig(
        baseSpiritStones = 200,
        spiritStones = 500,
        baseMaterialCountMin = 3,
        baseMaterialCountMax = 5,
        baseMaterialMinRarity = 1,
        baseMaterialMaxRarity = 1,
        materialCountMin = 10,
        materialCountMax = 15,
        materialMinRarity = 1,
        materialMaxRarity = 2
    )
    else -> null
}

/** 中阶任务奖励配置 */

internal fun MissionSystem.createTier2RewardConfig(template: MissionTemplate): MissionRewardConfig? = when (template) {
    MissionTemplate.ESCORT_SPIRIT_CARAVAN -> MissionRewardConfig(
        spiritStones = 1500
    )
    MissionTemplate.INVESTIGATE_ANOMALY -> MissionRewardConfig(
        spiritStones = 800,
        materialCountMin = 8,
        materialCountMax = 15,
        materialMinRarity = 2,
        materialMaxRarity = 2
    )
    MissionTemplate.DELIVER_PILLS -> MissionRewardConfig(
        spiritStones = 1000,
        pillCountMin = 1,
        pillCountMax = 2,
        pillMinRarity = 2,
        pillMaxRarity = 2
    )
    MissionTemplate.SUPPRESS_JINDAN_BEASTS -> MissionRewardConfig(
        spiritStones = 1200,
        materialCountMin = 15,
        materialCountMax = 25,
        materialMinRarity = 2,
        materialMaxRarity = 3
    )
    MissionTemplate.DESTROY_MAGIC_OUTPOST -> MissionRewardConfig(
        spiritStones = 1500,
        materialCountMin = 12,
        materialCountMax = 20,
        materialMinRarity = 2,
        materialMaxRarity = 3,
        equipmentChance = 0.3,
        equipmentMinRarity = 2,
        equipmentMaxRarity = 3
    )
    MissionTemplate.EXPLORE_ANCIENT_CAVE -> MissionRewardConfig(
        baseSpiritStones = 600,
        spiritStones = 1800,
        baseMaterialCountMin = 5,
        baseMaterialCountMax = 10,
        baseMaterialMinRarity = 2,
        baseMaterialMaxRarity = 2,
        materialCountMin = 18,
        materialCountMax = 28,
        materialMinRarity = 2,
        materialMaxRarity = 3,
        manualChance = 0.3,
        manualMinRarity = 2,
        manualMaxRarity = 3
    )
    else -> null
}

/** 高阶任务奖励配置 */

internal fun MissionSystem.createTier3RewardConfig(template: MissionTemplate): MissionRewardConfig? = when (template) {
    MissionTemplate.ESCORT_IMMORTAL_ENVOY -> MissionRewardConfig(
        spiritStones = 40000
    )
    MissionTemplate.REPAIR_ANCIENT_FORMATION -> MissionRewardConfig(
        spiritStones = 20000,
        materialCountMin = 10,
        materialCountMax = 20,
        materialMinRarity = 4,
        materialMaxRarity = 5
    )
    MissionTemplate.SEARCH_MISSING_ELDER -> MissionRewardConfig(
        spiritStones = 25000,
        pillCountMin = 1,
        pillCountMax = 2,
        pillMinRarity = 4,
        pillMaxRarity = 4
    )
    MissionTemplate.SUPPRESS_HUASHEN_BEAST_KING -> MissionRewardConfig(
        spiritStones = 30000,
        materialCountMin = 20,
        materialCountMax = 35,
        materialMinRarity = 4,
        materialMaxRarity = 5,
        equipmentChance = 0.3,
        equipmentMinRarity = 4,
        equipmentMaxRarity = 5
    )
    MissionTemplate.DESTROY_MAGIC_BRANCH -> MissionRewardConfig(
        spiritStones = 40000,
        materialCountMin = 18,
        materialCountMax = 30,
        materialMinRarity = 4,
        materialMaxRarity = 5,
        equipmentChance = 0.3,
        equipmentMinRarity = 4,
        equipmentMaxRarity = 5
    )
    MissionTemplate.EXPLORE_ANCIENT_BATTLEFIELD -> MissionRewardConfig(
        baseSpiritStones = 12500,
        spiritStones = 50000,
        baseMaterialCountMin = 8,
        baseMaterialCountMax = 15,
        baseMaterialMinRarity = 4,
        baseMaterialMaxRarity = 4,
        materialCountMin = 25,
        materialCountMax = 40,
        materialMinRarity = 4,
        materialMaxRarity = 5,
        manualChance = 0.3,
        manualMinRarity = 4,
        manualMaxRarity = 5
    )
    else -> null
}

/** 顶阶任务奖励配置 */

internal fun MissionSystem.createTier4RewardConfig(template: MissionTemplate): MissionRewardConfig? = when (template) {
    MissionTemplate.ESCORT_RELIC_ARTIFACT -> MissionRewardConfig(
        spiritStones = 200000
    )
    MissionTemplate.SEAL_SPATIAL_RIFT -> MissionRewardConfig(
        spiritStones = 100000,
        materialCountMin = 15,
        materialCountMax = 25,
        materialMinRarity = 5,
        materialMaxRarity = 6
    )
    MissionTemplate.SEARCH_SECRET_REALM_CLUE -> MissionRewardConfig(
        spiritStones = 150000,
        pillCountMin = 1,
        pillCountMax = 2,
        pillMinRarity = 5,
        pillMaxRarity = 5
    )
    MissionTemplate.SUPPRESS_ANCIENT_FIEND -> MissionRewardConfig(
        spiritStones = 150000,
        materialCountMin = 25,
        materialCountMax = 45,
        materialMinRarity = 5,
        materialMaxRarity = 6,
        equipmentChance = 0.3,
        equipmentMinRarity = 5,
        equipmentMaxRarity = 6
    )
    MissionTemplate.DESTROY_MAGIC_HEADQUARTERS -> MissionRewardConfig(
        spiritStones = 200000,
        materialCountMin = 22,
        materialCountMax = 38,
        materialMinRarity = 5,
        materialMaxRarity = 6,
        equipmentChance = 0.3,
        equipmentMinRarity = 5,
        equipmentMaxRarity = 6
    )
    MissionTemplate.EXPLORE_CORE_BATTLEFIELD -> MissionRewardConfig(
        baseSpiritStones = 60000,
        spiritStones = 250000,
        baseMaterialCountMin = 10,
        baseMaterialCountMax = 18,
        baseMaterialMinRarity = 5,
        baseMaterialMaxRarity = 5,
        materialCountMin = 30,
        materialCountMax = 50,
        materialMinRarity = 5,
        materialMaxRarity = 6,
        manualChance = 0.3,
        manualMinRarity = 5,
        manualMaxRarity = 6
    )
    else -> null
}

internal fun MissionSystem.rollSpiritStones(rewards: MissionRewardConfig): Int {
    return if (rewards.spiritStonesMax > 0) {
        rewards.spiritStones + rng.nextInt(rewards.spiritStonesMax - rewards.spiritStones + 1)
    } else {
        rewards.spiritStones
    }
}

internal fun MissionSystem.generateMaterials(rewards: MissionRewardConfig): List<Material> {
    return generateMaterialBatch(
        rewards.materialCountMin, rewards.materialCountMax,
        rewards.materialMinRarity, rewards.materialMaxRarity
    )
}

internal fun MissionSystem.generateBaseMaterials(rewards: MissionRewardConfig): List<Material> {
    return generateMaterialBatch(
        rewards.baseMaterialCountMin, rewards.baseMaterialCountMax,
        rewards.baseMaterialMinRarity, rewards.baseMaterialMaxRarity
    )
}

internal fun MissionSystem.generatePills(rewards: MissionRewardConfig): List<Pill> {
    if (rewards.pillCountMin <= 0) return emptyList()

    /** 结构数量（与 [SpriteAtlasDef.STRUCTURES] 同序同量）。 */
    val count = rewards.pillCountMin + rng.nextInt(rewards.pillCountMax - rewards.pillCountMin + 1)
    val pills = mutableListOf<Pill>()

    repeat(count) {
        // 模板选择经 MISSION 分区适配器（读档可重放；与 C++ mission_completion.h
        // 抽取序逐位一致）
        pills.add(ItemDatabase.generateRandomPill(
            rewards.pillMinRarity, rewards.pillMaxRarity, RngRandomAdapter(rng)
        ))
    }

    return pills
}

internal fun MissionSystem.generateEquipment(
    rewards: MissionRewardConfig
): List<com.xianxia.sect.core.model.EquipmentStack> {
    if (rewards.equipmentChance <= 0.0) return emptyList()
    if (rng.nextDouble() >= rewards.equipmentChance) return emptyList()

    // S5：装备模板选择经 MISSION 分区适配器（同上——非确定性修正）
    return listOf(EquipmentDatabase.generateRandom(
        rewards.equipmentMinRarity, rewards.equipmentMaxRarity, RngRandomAdapter(rng)
    ))
}

internal fun MissionSystem.generateManuals(
    rewards: MissionRewardConfig
): List<com.xianxia.sect.core.model.ManualStack> {
    if (rewards.manualChance <= 0.0) return emptyList()
    if (rng.nextDouble() >= rewards.manualChance) return emptyList()

    return try {
        // S5：功法模板选择经 MISSION 分区适配器（同上）
        listOf(ManualDatabase.generateRandom(
            rewards.manualMinRarity, rewards.manualMaxRarity, null, RngRandomAdapter(rng)
        ))
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun MissionSystem.buildWeightedPool(): List<WeightedEntry> {
    var cumulative = 0.0
    return MissionTemplate.entries.map { template ->
        cumulative += template.difficulty.spawnChance
        WeightedEntry(template, cumulative)
    }
}

internal fun MissionSystem.weightedRandom(pool: List<WeightedEntry>): MissionTemplate {
    val totalWeight = pool.lastOrNull()?.cumulativeWeight ?: 0.0
    if (totalWeight <= 0.0) return MissionTemplate.entries[rng.nextInt(MissionTemplate.entries.size)]
    val roll = rng.nextDouble() * totalWeight
    return pool.first { roll < it.cumulativeWeight }.template
}

internal fun MissionSystem.generateMaterialBatch(
    countMin: Int,
    countMax: Int,
    minRarity: Int,
    maxRarity: Int
): List<Material> {
    if (countMin <= 0) return emptyList()

    /** 结构数量（与 [SpriteAtlasDef.STRUCTURES] 同序同量）。 */
    val count = countMin + rng.nextInt(countMax - countMin + 1)
    val materials = mutableListOf<Material>()

    repeat(count) {
        val eligibleMaterials = BeastMaterialDatabase.getAllMaterials()
            .filter { it.rarity in minRarity..maxRarity }
        if (eligibleMaterials.isNotEmpty()) {
            val template = eligibleMaterials[rng.nextInt(eligibleMaterials.size)]
            materials.add(ItemDatabase.createMaterialFromTemplate(
                ItemDatabase.MaterialTemplate(
                    id = template.id,
                    name = template.name,
                    category = template.materialCategory,
                    rarity = template.rarity,
                    description = template.description,
                    price = template.price
                )
            ))
        }
    }

    return materials
}
