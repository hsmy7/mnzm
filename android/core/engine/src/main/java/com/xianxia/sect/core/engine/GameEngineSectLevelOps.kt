package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.ItemNames

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.SectLevelClaimRecord
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.wallet.SpiritStoneSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.util.UUID



// ── 宗门等级结果类型 ────────────────────────────────────────────

/** 宗门等级升级结果 */
sealed interface SectLevelClaimResult {
    data object Success : SectLevelClaimResult
    data class AlreadyClaimed(val nextClaimableMs: Long) : SectLevelClaimResult
    /** 仓库容量不足：奖励未发放、领取记录未写入，清理后可重新领取 */
    data class CapacityInsufficient(val message: String) : SectLevelClaimResult
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
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
suspend fun GameEngine.claimSectLevelReward(level: Int): SectLevelClaimResult = engineContextDispatcher
    .withEngineContext {
    val nowMs = System.currentTimeMillis()

    // 检查是否在冷却中
    val cooldownResult = findSectLevelCooldownResult(
        level = level,
        nowMs = nowMs
    )
    if (cooldownResult != null) {
        return@withEngineContext cooldownResult
    }

    val rewardCards = com.xianxia.sect.core.config.SectLevelRewardConfig.getRewardCards(level)
    if (rewardCards.isEmpty()) {
        DomainLog.w(TAG, "claimSectLevelReward: level=$level has no rewards configured")
        return@withEngineContext SectLevelClaimResult.Error("没有找到该等级的奖励配置")
    }

    try {
        // 1. 预生成所有物品并收集飞行卡片（精灵图能正确匹配具体物品名）
        val prepared = buildSectLevelRewardCards(rewardCards)
        // 2. 写入 state（发放物品 + 记录领取时间戳）
        // batch-19 native 臂：宗门等级奖励领取落账（SECT_LEVEL_CLAIM_TX——
        // 凭据类：溢出抑制 + 失败零写入 + 领取记录 upsert，零 RNG）。
        // AUTHORITATIVE 门控 + 镜像服务可空局部判空（handover findings 13）；
        // 失败信封/降级 → Kotlin 原路径（双实现并行契约，回退臂语义逐字一致）。
        if (!tryNativeSectLevelClaim(level = level, nowMs = nowMs, prepared = prepared)) {
            // Kotlin 原路径：任一物品发放失败/溢出（仓库满）时不写领取记录，
            // 玩家清理仓库后可重新领取，奖励不会永久消耗（凭据类路径：抑制溢出转邮件，
            // 溢出部分由重试补齐，避免"邮件 + 重试"重复发放）。
            // **凭据未写入时必须按失败上抛**：忽略返回值会让"发放失败 + 无凭据"被
            // 报成 Success——冷却判定失去依据（`sectLevelClaimRecords` 为空），
            // 玩家可在冷却窗口内无限重领。抛出后由下方 IllegalStateException 分支
            // 转为明确的玩家可见失败文案（奖励未发放，可重试）。
            if (!writeSectLevelRewards(
                    level = level,
                    nowMs = nowMs,
                    prepared = prepared
                )
            ) {
                // 凭据未写入 = 玩家可见的"未发放"失败（可重试），不得报成功
                DomainLog.w(TAG, "claimSectLevelReward: level=$level 存在未成功入账的奖励，凭据未写（可重试）")
                return@withEngineContext SectLevelClaimResult.Error("奖励未完全发放，请清理仓库后重试")
            }
        }

        // 3. 入队飞行卡片（具体物品名，精灵图可正确解析）
        // 卡片仅在全部成功时入队（失败时无幻影卡片）
        if (prepared.flyCards.isNotEmpty()) {
            stateStore.enqueueRewardCards(prepared.flyCards)
        }

        DomainLog.d(
            TAG,
            "claimSectLevelReward: level=$level success, claimedAt=$nowMs, flyCards=${prepared.flyCards.size}"
        )
        return@withEngineContext SectLevelClaimResult.Success
    } catch (e: CancellationException) {
        throw e // 取消穿透: 领取取消时上抛, 奖励发放凭据保留, 不以 Error 冒充失败
    } catch (e: IllegalStateException) {
        // 容量不足/发放失败 → 事务整体回滚（物品/灵石/记录均未写），凭据保留可重试
        DomainLog.w(TAG, "claimSectLevelReward: level=$level 仓库容量不足（事务已回滚）")
        return@withEngineContext SectLevelClaimResult.CapacityInsufficient(
            e.message ?: "仓库容量不足，奖励未发放，请清理仓库后重新领取"
        )
    } catch (e: Exception) {
        DomainLog.e(TAG, "claimSectLevelReward failed: level=$level", e)
        return@withEngineContext SectLevelClaimResult.Error("领取失败: ${e.message}")
    }}

/** 领取冷却检查：距上次领取不足 7 天返回 AlreadyClaimed，否则 null */
private suspend fun GameEngine.findSectLevelCooldownResult(
    level: Int,
    nowMs: Long
): SectLevelClaimResult? {
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
    return null
}

/** 宗门等级奖励数量汇总 */
private data class SectLevelRewardAggregate(
    val beastBloodRarities: Map<Int, Int>,
    val storageBagRarities: Map<Int, Int>,
    val totalSpiritStones: Long
)

/** 宗门等级奖励预生成结果 */
private data class SectLevelRewardPrepared(
    val flyCards: List<RewardCardItem>,
    val generatedBeastBlood: Map<String, Pair<Int, Int>>,
    val storageBagRarities: Map<Int, Int>,
    val totalSpiritStones: Long
)

/** 奖励卡片数量汇总：按 itemType 聚合兽血/储物袋/灵石 */
private fun GameEngine.aggregateSectLevelRewards(
    rewardCards: List<RewardCardItem>
): SectLevelRewardAggregate {
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
    return SectLevelRewardAggregate(
        beastBloodRarities = beastBloodRarities,
        storageBagRarities = storageBagRarities,
        totalSpiritStones = totalSpiritStones
    )
}

/** 奖励预生成：预生成兽血材料并构建全部飞行卡片 */
private fun GameEngine.buildSectLevelRewardCards(
    rewardCards: List<RewardCardItem>
): SectLevelRewardPrepared {
    val aggregate = aggregateSectLevelRewards(rewardCards)
    val flyCards = mutableListOf<RewardCardItem>()

    // 预生成兽血材料（汇总到 Map<name, Pair<rarity, count>>）
    val generatedBeastBlood = mutableMapOf<String, Pair<Int, Int>>()
    aggregate.beastBloodRarities.forEach { (rarity, count) ->
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
    aggregate.storageBagRarities.forEach { (rarity, count) ->
        val bagName = StorageBag.TIER_NAMES.getOrElse(rarity - 1) { "凡品储物袋" }
        flyCards.add(RewardCardItem(
            itemName = bagName,
            itemType = "storageBag",
            rarity = rarity,
            quantity = count
        ))
    }

    // 灵石飞行卡片
    if (aggregate.totalSpiritStones > 0) {
        flyCards.add(RewardCardItem(
            itemName = ItemNames.SPIRIT_STONE,
            itemType = "spiritStones",
            rarity = 1,
            quantity = aggregate.totalSpiritStones.toInt().coerceAtMost(Int.MAX_VALUE)
        ))
    }

    return SectLevelRewardPrepared(
        flyCards = flyCards,
        generatedBeastBlood = generatedBeastBlood,
        storageBagRarities = aggregate.storageBagRarities,
        totalSpiritStones = aggregate.totalSpiritStones
    )
}

/**
 * 奖励发放入账：兽血/储物袋/灵石发放 + 领取记录写入（凭据类抑制溢出转邮件）。
 *
 * @return true = 领取凭据（`sectLevelClaimRecords`）已写入；false = 存在未成功
 *         入账的物品 → **凭据未写**（调用方必须按失败处理，见 [claimSectLevelReward]）。
 *
 * 根因说明：凭据写入与物品入账必须同生共死——若物品入账失败却写凭据，玩家在
 * 冷却期内既拿不到奖励也无法重领（凭据被白耗）；若物品入账失败又不告知调用方
 * （旧实现忽略本返回值恒报 Success），则冷却判定失效、可无限重领。
 * 返回值的唯一消费者是 [claimSectLevelReward]，它据此返回明确失败文案。
 */
@Suppress("ThrowsCount")
private fun GameEngine.writeSectLevelRewards(
    level: Int,
    nowMs: Long,
    prepared: SectLevelRewardPrepared
): Boolean {
    // 2. 写入 state（发放物品 + 记录领取时间戳）：任一物品发放失败/溢出（仓库满）时不写领取记录，
    // 凭据类路径抑制溢出转邮件，溢出部分由重试补齐，避免"邮件 + 重试"重复发放
    var allSucceeded = true
    stateStore.update {
        inventorySystem.withOverflowMailSuppressed {
        inventorySystem.withTrackingSource("sect_level") {
        prepared.generatedBeastBlood.forEach { (name, pair) ->
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
                // Partial/Failure 抛异常整体回滚（物品/灵石/记录均未写），
                // 凭据保留 → 玩家清理后重试全量，避免"部分入仓 + 凭据保留"重复发放
                when (val r = inventorySystem.addMaterial(material)) {
                    is DomainResult.Success -> {}
                    is DomainResult
                        .Partial -> error("材料 ${material.name} 仓库空间不足，溢出 ${r.overflow} 个")
                    is DomainResult.Failure -> error("材料 ${material.name} 添加失败: ${r.error}")
                }
            }
        }
        prepared.storageBagRarities.forEach { (rarity, count) ->
            val bagName = StorageBag.TIER_NAMES.getOrElse(rarity - 1) { "凡品储物袋" }
            // 统一委托 addStorageBag（走 StackableItemStore 合并，同稀有度自动合并）
            val r = inventorySystem.addStorageBag(
                StorageBag(
                    id = UUID.randomUUID().toString(),
                    name = bagName,
                    rarity = rarity,
                    quantity = count
                )
            )
            check(r is DomainResult.Success) { "储物袋 $bagName 发放失败: ${(r as? DomainResult.Failure)?.error}" }
        }
        if (prepared.totalSpiritStones > 0) {
            spiritStoneWallet.add(this, prepared.totalSpiritStones, SpiritStoneGrade.LOW,
                SpiritStoneSource.SectLevelReward)
        }
        if (allSucceeded) {
            val newRecord = SectLevelClaimRecord(
                level = level,
                claimedAtEpochMs = nowMs
            )
            val updatedRecords = gameData.sectLevelClaimRecords
                .filter { it.level != level } + newRecord
            gameData = gameData.copy(sectLevelClaimRecords = updatedRecords)
        }
        }
        }
    }
    return allSucceeded
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
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
suspend fun GameEngine.upgradeSectLevel(): SectLevelUpgradeResult = engineContextDispatcher.withEngineContext {
    val snapshot = stateStore.gameDataSnapshot
    var playerSect = snapshot.worldMapSects.find { it.isPlayerSect }

    // 未找到玩家宗门时，尝试修复后重试一次
    if (playerSect == null) {
        DomainLog.w(TAG, "upgradeSectLevel: 未找到玩家宗门，尝试修复")
        ensureGameDataIntegrity()
        val repairedSnapshot = stateStore.gameDataSnapshot
        playerSect = repairedSnapshot.worldMapSects.find { it.isPlayerSect }
        if (playerSect == null) {
            return@withEngineContext SectLevelUpgradeResult.Error("未找到玩家宗门，修复后仍不存在")
        }
    }

    val currentLevel = playerSect.level
    if (currentLevel >= SectLevel.TOP) {
        return@withEngineContext SectLevelUpgradeResult.AlreadyMaxLevel
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
        return@withEngineContext SectLevelUpgradeResult.ConditionsNotMet(unmetConditions)
    }

    // 条件满足，执行升级
    val newLevelName = SectLevel.levelName(targetLevel)
    try {
        // batch-19 native 臂：宗门升级写回（SECT_LEVEL_UPGRADE_TX——零 RNG 纯
        // 状态变换）。失败/降级 → Kotlin 原路径（双实现并行契约）。
        if (!tryNativeSectLevelUpgrade(targetLevel = targetLevel, levelName = newLevelName)) {
            stateStore.update {
                gameData = gameData.copy(
                    worldMapSects = gameData.worldMapSects.map { sect ->
                        if (sect.isPlayerSect) {
                            sect.copy(level = targetLevel, levelName = newLevelName)
                        } else sect
                    }
                )
            }
        }
        DomainLog.d(TAG, "upgradeSectLevel: level=$currentLevel->$targetLevel success")
        return@withEngineContext SectLevelUpgradeResult.Success(targetLevel)
    } catch (e: CancellationException) {
        throw e // 取消穿透: 升级取消时上抛, 不以 Error 冒充失败
    } catch (e: Exception) {
        DomainLog.e(TAG, "upgradeSectLevel failed", e)
        return@withEngineContext SectLevelUpgradeResult.Error("升级失败: ${e.message}")
    }
}

/**
 * 获取玩家宗门升至 [targetLevel] 的升级条件状态。
 */
fun GameEngine.checkSectLevelUpgradeConditions(targetLevel: Int): List<com.xianxia.sect.core.config
    .UpgradeConditionState> {
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

// ── batch-19 native 臂（宗门升级/等级奖励领取决的「落账段」下沉 C++） ─────

/**
 * 宗门升级写回 native 臂（ActionIds.SECT_LEVEL_UPGRADE_TX——jade_tx.h）。
 *
 * 升级条件校验（最高境界/已占宗门等级/SectLevelRewardConfig）为只读读面，留 Kotlin
 * 原位（下沉段 = `worldMapSects` 玩家宗门 level/levelName 写回）。零 RNG。
 *
 * @return true = C++ 已落账（Kotlin 侧不再写入）；false = 降级/失败 → 调用方
 *         走 Kotlin 原路径（双实现并行契约）
 */
@Suppress("ReturnCount")  // 降级契约：flag 关/镜像缺失/信封失败逐级早退（同 tryNativeRecruitAll）
private fun GameEngine.tryNativeSectLevelUpgrade(targetLevel: Int, levelName: String): Boolean {
    if (!NativeEngineFlag.authoritative) return false
    val sync: StateSyncService? = gameEngineCore.stateSyncServiceRef
    if (sync == null) return false
    val data = GameEngineNativeOps.tryExecuteNative(
        stateSyncService = sync,
        actionId = ActionIds.SECT_LEVEL_UPGRADE_TX,
        paramsJson = params {
            put("targetLevel", targetLevel)
            put("levelName", levelName)
        }
    )
    if (data == null) return false
    DomainLog.i(TAG, "upgradeSectLevel: native wrote level=$targetLevel")
    return true
}

/**
 * 宗门等级奖励领取落账 native 臂（ActionIds.SECT_LEVEL_CLAIM_TX——jade_tx.h）。
 *
 * **凭据类**（CLAUDE.md 13.3）：C++ 侧 `overflowMailSuppressed=true`——溢出不转邮件，
 * 事务整体回滚失败 → 调用方回退 Kotlin 原路径重执行（凭据保留可重试，避免
 * 「邮件 + 重试」重复发放）。
 *
 * 物品**模板解析**已由 Kotlin 预生成阶段完成（`buildSectLevelRewardCards`，
 * 含兽血模板抽取）——C++ data 层仅有静态模板表无生成器，故只下沉落账段；
 * 零 RNG（本臂不接受 RNG 参数，C++ 侧签名级同证）。
 *
 * @return true = C++ 已落账（物品/灵石/领取记录）；false = 降级/失败 → Kotlin 原路径
 */
@Suppress("ReturnCount")  // 降级契约：flag 关/镜像缺失/信封失败逐级早退（同上）
private fun GameEngine.tryNativeSectLevelClaim(
    level: Int,
    nowMs: Long,
    prepared: SectLevelRewardPrepared
): Boolean {
    if (!NativeEngineFlag.authoritative) return false
    val sync: StateSyncService? = gameEngineCore.stateSyncServiceRef
    if (sync == null) return false
    val data = GameEngineNativeOps.tryExecuteNative(
        stateSyncService = sync,
        actionId = ActionIds.SECT_LEVEL_CLAIM_TX,
        paramsJson = params {
            put("level", level)
            put("nowMs", nowMs)
            put("spiritStones", prepared.totalSpiritStones)
            put("materials", buildJsonArray {
                prepared.generatedBeastBlood.forEach { (name, pair) ->
                    addJsonObject {
                        put("name", name)
                        put("rarity", pair.first)
                        put("quantity", pair.second)
                        put("category", beastBloodCategory(name, pair.first))
                    }
                }
            })
            put("storageBags", buildJsonArray {
                prepared.storageBagRarities.forEach { (rarity, count) ->
                    addJsonObject {
                        put("name", StorageBag.TIER_NAMES.getOrElse(rarity - 1) { "凡品储物袋" })
                        put("rarity", rarity)
                        put("quantity", count)
                    }
                }
            })
        }
    )
    if (data == null) return false
    DomainLog.i(
        TAG,
        "claimSectLevelReward: native distributed level=$level " +
            "(materials=${prepared.generatedBeastBlood.size}, stones=${prepared.totalSpiritStones})"
    )
    return true
}

/** 兽血材料分类名（与 writeSectLevelRewards 同款模板反查口径；查不到回退默认分类） */
private fun beastBloodCategory(name: String, rarity: Int): String =
    BeastMaterialDatabase.getMaterialsByRarity(rarity)
        .find { it.name == name && it.category == "blood" }
        ?.materialCategory?.name ?: "BEAST_BLOOD"

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
private const val TAG = "GameEngineSectLevel"
