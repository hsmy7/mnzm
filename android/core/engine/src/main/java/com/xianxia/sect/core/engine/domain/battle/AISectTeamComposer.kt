package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition

/** AI 组队系统的 RNG 管理器（由 GameEngine 初始化时注入） */
var teamComposerRngManager: GameRngManager? = null
private val teamComposerRng: DeterministicRng
    get() = (teamComposerRngManager ?: error("TeamComposer RNG not initialized")).getRng(RngPartition.BATTLE)

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
        val itemType = teamComposerRng.nextInt(7)
        when (itemType) {
            0 -> spiritStones += config.spiritStoneValue
            1 -> addWarEquipment(config, equipmentStacks)
            2 -> addWarManual(config, manualStacks)
            3 -> addWarPill(config, pills)
            4 -> addWarMaterial(config, materials)
            5 -> addWarHerb(config, herbs)
            6 -> addWarSeed(config, seeds)
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

/** 战争奖励：装备生成 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
private fun addWarEquipment(
    config: SectWarRewardConfig,
    equipmentStacks: MutableList<com.xianxia.sect.core.model.EquipmentStack>
) {
    if (com.xianxia.sect.core.registry.EquipmentDatabase.isInitialized) {
        try {
            equipmentStacks.add(
                com.xianxia.sect.core.registry.EquipmentDatabase.generateRandom(config.minRarity, config.maxRarity)
            )
        } catch (e: Exception) { android.util.Log.w("AISectAttackManager", "随机物品生成失败", e) }
    }
}

/** 战争奖励：功法生成 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
private fun addWarManual(
    config: SectWarRewardConfig,
    manualStacks: MutableList<com.xianxia.sect.core.model.ManualStack>
) {
    if (com.xianxia.sect.core.registry.ManualDatabase.isInitialized) {
        try {
            manualStacks.add(
                com.xianxia.sect.core.registry.ManualDatabase.generateRandom(config.minRarity, config.maxRarity)
            )
        } catch (e: Exception) { android.util.Log.w("AISectAttackManager", "随机物品生成失败", e) }
    }
}

/** 战争奖励：丹药生成 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
private fun addWarPill(
    config: SectWarRewardConfig,
    pills: MutableList<com.xianxia.sect.core.model.Pill>
) {
    try {
        pills.add(com.xianxia.sect.core.registry.ItemDatabase.generateRandomPill(config.minRarity, config.maxRarity))
    } catch (e: Exception) { android.util.Log.w("AISectAttackManager", "随机物品生成失败", e) }
}

/** 战争奖励：材料生成 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
private fun addWarMaterial(
    config: SectWarRewardConfig,
    materials: MutableList<com.xianxia.sect.core.model.Material>
) {
    try {
        materials.add(com.xianxia.sect.core.registry.ItemDatabase.generateRandomMaterial(config.minRarity,
            config.maxRarity))
    } catch (e: Exception) { android.util.Log.w("AISectAttackManager", "随机物品生成失败", e) }
}

/** 战争奖励：灵草生成 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
private fun addWarHerb(
    config: SectWarRewardConfig,
    herbs: MutableList<com.xianxia.sect.core.model.Herb>
) {
    try {
        val herbTemplate = com.xianxia.sect.core.registry.HerbDatabase.generateRandomHerb(config.minRarity,
            config.maxRarity)
        herbs.add(com.xianxia.sect.core.model.Herb(name = herbTemplate.name, rarity = herbTemplate.rarity,
            description = herbTemplate.description, category = herbTemplate.category, quantity = 1))
    } catch (e: Exception) { android.util.Log.w("AISectAttackManager", "随机物品生成失败", e) }
}

/** 战争奖励：种子生成 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
private fun addWarSeed(
    config: SectWarRewardConfig,
    seeds: MutableList<com.xianxia.sect.core.model.Seed>
) {
    try {
        val seedTemplate = com.xianxia.sect.core.registry.HerbDatabase.generateRandomSeed(config.minRarity,
            config.maxRarity)
        seeds.add(com.xianxia.sect.core.model.Seed(name = seedTemplate.name, rarity = seedTemplate.rarity,
            description = seedTemplate.description, growTime = seedTemplate.growTime,
            yield = seedTemplate.yield, quantity = 1))
    } catch (e: Exception) { android.util.Log.w("AISectAttackManager", "随机物品生成失败", e) }
}
