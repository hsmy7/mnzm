package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager

/**
 * AI 宗门战力评分 — 基于境界、装备、功法、天赋综合计算。
 */
internal fun calculatePowerScore(disciples: List<Disciple>): Double {
    val aliveDisciples = disciples.filter { it.isAlive }
    if (aliveDisciples.isEmpty()) return 0.0

    val weights = GameConfig.AI.PowerWeights
    var totalPower = 0.0

    for (disciple in aliveDisciples) {
        val realmPower = (10 - disciple.realm) * weights.REALM_BASE

        val maxRarity = GameConfig.Realm.getMaxRarity(disciple.realm)
        val minRarity = AISectDiscipleManager.getMinRarityByRealm(disciple.realm)
        val avgEquipmentRarity = (minRarity + maxRarity) / 2.0
        val avgManualRarity = (minRarity + maxRarity) / 2.0
        val maxManuals = AISectDiscipleManager.getMaxManualsByRealm(disciple.realm)

        val equipmentPower = avgEquipmentRarity * 2.0 * weights.EQUIPMENT_RARITY
        val manualPower = avgManualRarity * (maxManuals / 2.0) * weights.MANUAL_RARITY

        val talentPower = disciple.talentIds.sumOf { talentId ->
            TalentDatabase.getById(talentId)?.rarity?.times(weights.TALENT_RARITY) ?: 0.0
        }

        val individualPower = realmPower + equipmentPower + manualPower + talentPower
        totalPower += individualPower
    }

    return totalPower
}

/**
 * 创建进攻队伍 — 按境界排序，选取战斗力最低的 N 个弟子。
 */
internal fun createAttackTeam(
    attackerDisciples: List<Disciple>,
    existingBusyIds: Set<String> = emptySet()
): List<Disciple> {
    val minCount = com.xianxia.sect.core.GameConfig.AI.MIN_DISCIPLES_FOR_ATTACK
    val teamSize = com.xianxia.sect.core.GameConfig.AI.TEAM_SIZE
    val availableDisciples = attackerDisciples
        .filter { it.isAlive && it.id !in existingBusyIds }
        .sortedBy { it.realm }

    if (availableDisciples.size < minCount) return emptyList()
    return availableDisciples.take(teamSize)
}

/**
 * 创建防守队伍 — 按境界排序，选取最强的 N 个弟子。
 */
internal fun createDefenseTeam(defenderDisciples: List<Disciple>): List<Disciple> {
    val teamSize = com.xianxia.sect.core.GameConfig.AI.TEAM_SIZE
    return defenderDisciples
        .filter { it.isAlive }
        .sortedBy { it.realm }
        .take(teamSize)
}

/**
 * 补充队伍到满编 — 用后备弟子填充。
 */
internal fun supplementDisciples(
    coreDisciples: List<Disciple>,
    availableDisciples: List<Disciple>
): List<Disciple> {
    val teamSize = com.xianxia.sect.core.GameConfig.AI.TEAM_SIZE
    val core = coreDisciples.take(teamSize)
    if (core.size >= teamSize) return core
    val coreIds = core.map { it.id }.toSet()
    val supplements = availableDisciples
        .filter { it.isAlive && it.id !in coreIds }
        .sortedBy { it.realm }
        .take(teamSize - core.size)
    return core + supplements
}

/**
 * 创建玩家防守队伍。
 */
internal fun createPlayerDefenseTeam(disciples: List<Disciple>): List<Disciple> {
    val teamSize = com.xianxia.sect.core.GameConfig.AI.TEAM_SIZE
    return disciples
        .filter { it.isAlive }
        .sortedBy { it.realm }
        .take(teamSize)
}

/**
 * 获取宗门驻军弟子列表。
 */
internal fun getGarrisonDisciples(
    sect: com.xianxia.sect.core.model.WorldSect,
    allDisciples: List<Disciple>
): List<Disciple> {
    return sect.garrisonSlots
        .filter { it.discipleId.isNotEmpty() }
        .mapNotNull { slot -> allDisciples.find { it.id == slot.discipleId } }
        .filter { it.isAlive }
}

/**
 * 获取宗门战争奖励配置。
 */
internal fun getSectWarRewardConfig(sectLevel: Int): SectWarRewardConfig {
    return when (sectLevel) {
        0 -> SectWarRewardConfig(minRarity = 1, maxRarity = 2, spiritStoneValue = 2000)
        1 -> SectWarRewardConfig(minRarity = 2, maxRarity = 4, spiritStoneValue = 6000)
        2 -> SectWarRewardConfig(minRarity = 3, maxRarity = 5, spiritStoneValue = 30000)
        3 -> SectWarRewardConfig(minRarity = 4, maxRarity = 6, spiritStoneValue = 80000)
        else -> SectWarRewardConfig(minRarity = 1, maxRarity = 2, spiritStoneValue = 2000)
    }
}

/**
 * 宗门被攻破时生成的随机战争奖励。
 */
internal fun generateWarRewards(sectLevel: Int, itemCount: Int): WarRewards {
    val config = getSectWarRewardConfig(sectLevel)
    var spiritStones = 0L

    val equipmentStacks = mutableListOf<com.xianxia.sect.core.model.EquipmentStack>()
    val manualStacks = mutableListOf<com.xianxia.sect.core.model.ManualStack>()
    val pills = mutableListOf<com.xianxia.sect.core.model.Pill>()
    val materials = mutableListOf<com.xianxia.sect.core.model.Material>()
    val herbs = mutableListOf<com.xianxia.sect.core.model.Herb>()
    val seeds = mutableListOf<com.xianxia.sect.core.model.Seed>()

    repeat(itemCount) {
        val itemType = kotlin.random.Random.nextInt(7)
        when (itemType) {
            0 -> spiritStones += config.spiritStoneValue
            1 -> {
                if (com.xianxia.sect.core.registry.EquipmentDatabase.isInitialized) {
                    try {
                        equipmentStacks.add(
                            com.xianxia.sect.core.registry.EquipmentDatabase.generateRandom(config.minRarity, config.maxRarity)
                        )
                    } catch (e: Exception) { android.util.Log.w("AISectAttackManager", "随机物品生成失败", e) }
                }
            }
            2 -> {
                if (com.xianxia.sect.core.registry.ManualDatabase.isInitialized) {
                    try {
                        manualStacks.add(
                            com.xianxia.sect.core.registry.ManualDatabase.generateRandom(config.minRarity, config.maxRarity)
                        )
                    } catch (e: Exception) { android.util.Log.w("AISectAttackManager", "随机物品生成失败", e) }
                }
            }
            3 -> {
                try {
                    pills.add(com.xianxia.sect.core.registry.ItemDatabase.generateRandomPill(config.minRarity, config.maxRarity))
                } catch (e: Exception) { android.util.Log.w("AISectAttackManager", "随机物品生成失败", e) }
            }
            4 -> {
                try {
                    materials.add(com.xianxia.sect.core.registry.ItemDatabase.generateRandomMaterial(config.minRarity, config.maxRarity))
                } catch (e: Exception) { android.util.Log.w("AISectAttackManager", "随机物品生成失败", e) }
            }
            5 -> {
                try {
                    val herbTemplate = com.xianxia.sect.core.registry.HerbDatabase.generateRandomHerb(config.minRarity, config.maxRarity)
                    herbs.add(com.xianxia.sect.core.model.Herb(name = herbTemplate.name, rarity = herbTemplate.rarity,
                        description = herbTemplate.description, category = herbTemplate.category, quantity = 1))
                } catch (e: Exception) { android.util.Log.w("AISectAttackManager", "随机物品生成失败", e) }
            }
            6 -> {
                try {
                    val seedTemplate = com.xianxia.sect.core.registry.HerbDatabase.generateRandomSeed(config.minRarity, config.maxRarity)
                    seeds.add(com.xianxia.sect.core.model.Seed(name = seedTemplate.name, rarity = seedTemplate.rarity,
                        description = seedTemplate.description, growTime = seedTemplate.growTime,
                        yield = seedTemplate.yield, quantity = 1))
                } catch (e: Exception) { android.util.Log.w("AISectAttackManager", "随机物品生成失败", e) }
            }
        }
    }

    return WarRewards(
        spiritStones = spiritStones,
        equipmentStacks = equipmentStacks,
        manualStacks = manualStacks,
        pills = pills,
        materials = materials,
        herbs = herbs,
        seeds = seeds
    )
}
