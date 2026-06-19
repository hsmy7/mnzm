package com.xianxia.sect.core.engine

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.util.DomainLog
import java.util.UUID

// ── 宗门等级结果类型 ────────────────────────────────────────────

/** 宗门等级升级结果 */
sealed interface SectLevelClaimResult {
    data object Success : SectLevelClaimResult
    data class AlreadyClaimed(val nextClaimableMs: Long) : SectLevelClaimResult
    data class Error(val message: String) : SectLevelClaimResult
}

/** 宗门升级结果 */
sealed interface SectLevelUpgradeResult {
    data class Success(val newLevel: Int) : SectLevelUpgradeResult
    data object AlreadyMaxLevel : SectLevelUpgradeResult
    data class ConditionsNotMet(val unmetConditions: List<String>) : SectLevelUpgradeResult
    data class Error(val message: String) : SectLevelUpgradeResult
}

// ── 宗门等级奖励领取 ──────────────────────────────────────────

/** 7 天 = 604,800,000 毫秒 */
private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

/**
 * 领取当前 [level] 宗门等级的每周奖励。
 *
 * 检查该等级上一次领取时间戳，距现实时间不足 7 天则拒绝。
 * 奖励物品（兽血/储物袋/灵石）通过 inventorySystem 直接发放。
 */
suspend fun GameEngine.claimSectLevelReward(level: Int): SectLevelClaimResult {
    val nowMs = System.currentTimeMillis()
    val snapshot = stateStore.gameDataSnapshot

    // 检查是否在冷却中
    val lastClaim = snapshot.sectLevelClaimRecords
        .find { it.level == level }
    if (lastClaim != null) {
        val elapsed = nowMs - lastClaim.claimedAtEpochMs
        if (elapsed < WEEK_MS) {
            val nextClaimable = lastClaim.claimedAtEpochMs + WEEK_MS
            DomainLog.d(TAG, "claimSectLevelReward: level=$level cooldown, nextClaimable=$nextClaimable")
            return SectLevelClaimResult.AlreadyClaimed(nextClaimable)
        }
    }

    val rewardCards = com.xianxia.sect.core.config.SectLevelRewardConfig.getRewardCards(level)
    if (rewardCards.isEmpty()) {
        DomainLog.w(TAG, "claimSectLevelReward: level=$level has no rewards configured")
        return SectLevelClaimResult.Error("没有找到该等级的奖励配置")
    }

    try {
        // 1. 预生成所有物品并收集飞行卡片（精灵图能正确匹配具体物品名）
        val flyCards = mutableListOf<RewardCardItem>()
        val beastBloodRarities = mutableMapOf<Int, Int>()
        val storageBagRarities = mutableMapOf<Int, Int>()
        var totalSpiritStones = 0L

        rewardCards.forEach { card ->
            when (card.itemType) {
                "beastMaterial" -> {
                    val existing = beastBloodRarities[card.rarity] ?: 0
                    beastBloodRarities[card.rarity] = existing + card.quantity
                }
                "storageBag" -> {
                    val existing = storageBagRarities[card.rarity] ?: 0
                    storageBagRarities[card.rarity] = existing + card.quantity
                }
                "spiritStones" -> {
                    totalSpiritStones += card.quantity.toLong()
                }
            }
        }

        // 预生成兽血材料（汇总到 Map<name, Pair<rarity, count>>）
        val generatedBeastBlood = mutableMapOf<String, Pair<Int, Int>>()
        beastBloodRarities.forEach { (rarity, count) ->
            val bloodMaterials = BeastMaterialDatabase.getMaterialsByRarity(rarity)
                .filter { it.category == "blood" }
            if (bloodMaterials.isNotEmpty()) {
                repeat(count) {
                    val template = bloodMaterials.random()
                    val existing = generatedBeastBlood[template.name]
                    if (existing != null) {
                        generatedBeastBlood[template.name] = Pair(rarity, existing.second + 1)
                    } else {
                        generatedBeastBlood[template.name] = Pair(rarity, 1)
                    }
                }
            }
        }

        // 生成兽血飞行卡片
        generatedBeastBlood.forEach { (name, pair) ->
            flyCards.add(RewardCardItem(
                itemName = name,
                itemType = "beastMaterial",
                rarity = pair.first,
                quantity = pair.second
            ))
        }

        // 储物袋飞行卡片
        storageBagRarities.forEach { (rarity, count) ->
            val bagName = StorageBag.TIER_NAMES.getOrElse(rarity - 1) { "凡品储物袋" }
            flyCards.add(RewardCardItem(
                itemName = bagName,
                itemType = "storageBag",
                rarity = rarity,
                quantity = count
            ))
        }

        // 灵石飞行卡片
        if (totalSpiritStones > 0) {
            flyCards.add(RewardCardItem(
                itemName = "灵石",
                itemType = "spiritStones",
                rarity = 1,
                quantity = totalSpiritStones.toInt().coerceAtMost(Int.MAX_VALUE)
            ))
        }

        // 2. 写入 state（发放物品 + 记录领取时间戳）
        stateStore.update {
            generatedBeastBlood.forEach { (name, pair) ->
                val template = BeastMaterialDatabase.getMaterialsByRarity(pair.first)
                    .find { it.name == name && it.category == "blood" }
                if (template != null) {
                    val material = Material(
                        id = UUID.randomUUID().toString(),
                        name = template.name,
                        rarity = template.rarity,
                        category = template.materialCategory,
                        quantity = pair.second
                    )
                    inventorySystem.addMaterial(material)
                }
            }

            storageBagRarities.forEach { (rarity, count) ->
                val bagName = StorageBag.TIER_NAMES.getOrElse(rarity - 1) { "凡品储物袋" }
                val existing = storageBags.find { it.rarity == rarity && it.name == bagName }
                if (existing != null) {
                    storageBags = storageBags.map {
                        if (it.id == existing.id) it.copy(quantity = it.quantity + count)
                        else it
                    }
                } else {
                    storageBags = storageBags + StorageBag(
                        id = UUID.randomUUID().toString(),
                        name = bagName,
                        rarity = rarity,
                        quantity = count
                    )
                }
            }

            if (totalSpiritStones > 0) {
                gameData = gameData.copy(
                    spiritStones = gameData.spiritStones + totalSpiritStones
                )
            }

            val newRecord = SectLevelClaimRecord(
                level = level,
                claimedAtEpochMs = nowMs
            )
            val updatedRecords = gameData.sectLevelClaimRecords
                .filter { it.level != level } + newRecord
            gameData = gameData.copy(sectLevelClaimRecords = updatedRecords)
        }

        // 3. 入队飞行卡片（具体物品名，精灵图可正确解析）
        if (flyCards.isNotEmpty()) {
            stateStore.enqueueRewardCards(flyCards)
        }

        DomainLog.d(TAG, "claimSectLevelReward: level=$level success, claimedAt=$nowMs, flyCards=${flyCards.size}")
        return SectLevelClaimResult.Success
    } catch (e: Exception) {
        DomainLog.e(TAG, "claimSectLevelReward failed: level=$level", e)
        return SectLevelClaimResult.Error("领取失败: ${e.message}")
    }
}

/**
 * 检查 [level] 宗门等级的奖励是否可领取（距上次领取 ≥ 7 天）。
 */
fun GameEngine.canClaimSectLevelReward(level: Int): Boolean {
    val snapshot = stateStore.gameDataSnapshot
    val lastClaim = snapshot.sectLevelClaimRecords.find { it.level == level }
        ?: return true  // 从未领取过
    val elapsed = System.currentTimeMillis() - lastClaim.claimedAtEpochMs
    return elapsed >= WEEK_MS
}

// ── 宗门等级升级 ──────────────────────────────────────────────

/**
 * 尝试将玩家宗门升至下一等级。
 *
 * 从当前等级读取升级条件并逐一验证，全满足则执行升级。
 * 升级直接写入 worldMapSects 中玩家宗门的 level / levelName。
 */
suspend fun GameEngine.upgradeSectLevel(): SectLevelUpgradeResult {
    val snapshot = stateStore.gameDataSnapshot
    val playerSect = snapshot.worldMapSects.find { it.isPlayerSect }
        ?: return SectLevelUpgradeResult.Error("未找到玩家宗门")

    val currentLevel = playerSect.level
    if (currentLevel >= SectLevel.TOP) {
        return SectLevelUpgradeResult.AlreadyMaxLevel
    }

    val targetLevel = currentLevel + 1

    // 计算当前游戏状态
    val tables = stateStore.discipleTables
    var highestRealm = 9
    tables.realms.forEach { id, realm ->
        if (tables.isAlive[id] == 1 && realm < highestRealm) {
            highestRealm = realm
        }
    }

    val occupiedSectLevels = snapshot.worldMapSects
        .filter { it.isPlayerOccupied }
        .map { it.level }

    // 验证条件
    val conditionStates = com.xianxia.sect.core.config.SectLevelRewardConfig
        .getUpgradeConditionStates(targetLevel, highestRealm, occupiedSectLevels)
    val unmetConditions = conditionStates.filter { !it.isMet }.map { it.description }
    if (unmetConditions.isNotEmpty()) {
        DomainLog.d(TAG, "upgradeSectLevel: level=$currentLevel->$targetLevel unmet: $unmetConditions")
        return SectLevelUpgradeResult.ConditionsNotMet(unmetConditions)
    }

    // 条件满足，执行升级
    val newLevelName = SectLevel.levelName(targetLevel)
    try {
        stateStore.update {
            gameData = gameData.copy(
                worldMapSects = gameData.worldMapSects.map { sect ->
                    if (sect.isPlayerSect) {
                        sect.copy(level = targetLevel, levelName = newLevelName)
                    } else sect
                }
            )
        }
        DomainLog.d(TAG, "upgradeSectLevel: level=$currentLevel->$targetLevel success")
        return SectLevelUpgradeResult.Success(targetLevel)
    } catch (e: Exception) {
        DomainLog.e(TAG, "upgradeSectLevel failed", e)
        return SectLevelUpgradeResult.Error("升级失败: ${e.message}")
    }
}

/**
 * 获取玩家宗门升至 [targetLevel] 的升级条件状态。
 */
fun GameEngine.checkSectLevelUpgradeConditions(targetLevel: Int): List<com.xianxia.sect.core.config.UpgradeConditionState> {
    val tables = stateStore.discipleTables
    var highestRealm = 9
    tables.realms.forEach { id, realm ->
        if (tables.isAlive[id] == 1 && realm < highestRealm) {
            highestRealm = realm
        }
    }

    val snapshot = stateStore.gameDataSnapshot
    val occupiedSectLevels = snapshot.worldMapSects
        .filter { it.isPlayerOccupied }
        .map { it.level }

    return com.xianxia.sect.core.config.SectLevelRewardConfig
        .getUpgradeConditionStates(targetLevel, highestRealm, occupiedSectLevels)
}

private const val TAG = "GameEngineSectLevel"
