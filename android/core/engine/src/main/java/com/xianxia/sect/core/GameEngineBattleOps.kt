package com.xianxia.sect.core.engine
import com.xianxia.sect.core.util.ItemNames

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.engine.domain.battle.AIBattleResult
import com.xianxia.sect.core.engine.domain.battle.AIBattleWinner
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.battle.generateWarRewards
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.battle.WarRewards
import com.xianxia.sect.core.engine.domain.exploration.LevelGenerator
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.wallet.SpiritStoneSource

// ── 宗门战战利品数量（P-01 分区 RNG 迁移，原 kotlin.random Iterable.random）──

/** 宗门战胜利战利品数量：攻占 80~130，击溃 20~60（分区 RNG，可存档复现） */
internal fun sectBattleRewardCount(canOccupy: Boolean, rng: DeterministicRng): Int {
    val range = if (canOccupy) SECT_BATTLE_OCCUPY_REWARD_COUNT else SECT_BATTLE_ROUT_REWARD_COUNT
    return range.first + rng.nextInt(range.last - range.first + 1)
}

private val SECT_BATTLE_OCCUPY_REWARD_COUNT = 80..130
private val SECT_BATTLE_ROUT_REWARD_COUNT = 20..60

// ── Battle facade delegates ─────────────────────────────────────────

suspend fun GameEngine.processBattleCasualties(deadMemberIds: Set<String>, survivorHpMap: Map<String, Int>, survivorMpMap: Map<String, Int> = emptyMap()) {
    battleFacade.processBattleCasualties(deadMemberIds, survivorHpMap, survivorMpMap)
    // 战斗死亡后清理 Gate 注册表
    deadMemberIds.forEach { assignmentGate.release(it) }
}
fun GameEngine.getTotalBattlesCount(): Int = battleFacade.getTotalBattlesCount()
fun GameEngine.getRecentBattles(count: Int = 10): List<BattleLog> = battleFacade.getRecentBattles(count)
fun GameEngine.getWinRate(lastNBattles: Int = 50): Double = battleFacade.getWinRate(lastNBattles)
fun GameEngine.clearPendingBattleResult() = battleFacade.clearPendingBattleResult()

// ── Attack sect ─────────────────────────────────────────────────────

suspend fun GameEngine.attackSect(sectId: String, attackSlots: List<Pair<Int, DiscipleAggregate>>) {
    return engineContextDispatcher.withEngineContext {
        ensureHeavyDataLoaded()
        val data = stateStore.gameDataSnapshot
        val targetSect = data.worldMapSects.find { it.id == sectId } ?: return@withEngineContext

        // 不能攻击空 ID 或自己的宗门
        if (sectId.isBlank() || targetSect.isPlayerSect) return@withEngineContext

        // 不能攻击已由玩家占领的宗门（避免重复俘虏）
        if (targetSect.isPlayerOccupied) return@withEngineContext

        // 不能攻击自己的附属宗门
        if (data.vassalContracts.any { it.vassalSectId == sectId }) return@withEngineContext

        val combatIds = attackSlots.map { it.second.id }
        if (combatIds.isNotEmpty()) {
            stateStore.update {
                cultivationService.forceSettleDisciplesBeforeBattle(
                    this, combatIds
                )
            }
        }
        val allDisciples = stateStore.discipleTables.assembleAll()
        val attackers = attackSlots.mapNotNull { (_, agg) -> allDisciples.find { it.id == agg.id && it.isAlive } }
        if (attackers.isEmpty()) return@withEngineContext
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val setup = buildSectAttackSetup(data, targetSect, sectId, playerSect)
        val battleResult = AISectAttackManager.executeSectBattle(
            attackers, targetSect, setup.defenderDisciples, setup.fullDefenderPool
        )
        val deadPlayerIds = battleResult.deadAttackerIds.toSet()
        combatService.processBattleCasualties(deadMemberIds = deadPlayerIds, survivorHpMap = battleResult.survivorHpMap, survivorMpMap = battleResult.survivorMpMap, isOutsideSect = true)
        val deadDefenderIds = battleResult.deadDefenderIds.toSet()
        removeDeadDefenders(sectId, setup.defenderPoolSectId, deadDefenderIds)
        val (teamMembers, enemyMembers) = buildSectBattleLogMembers(
            attackers, setup.defenderDisciples, targetSect, battleResult, deadPlayerIds, deadDefenderIds
        )
        val (log, warRewards) = buildSectBattleLog(
            data, targetSect, battleResult, teamMembers, enemyMembers, deadPlayerIds
        )
        val updatedLogs = (stateStore.battleLogsSnapshot + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)

        // 记录宗门战战绩（近3年内，用于附属决策算法）
        recordSectBattleRecord(
            when (battleResult.winner) {
                AIBattleWinner.ATTACKER -> if (battleResult.canOccupy) SectBattleType.CONQUEST else SectBattleType.BATTLE_WIN
                else -> SectBattleType.BATTLE_LOSS
            },
            updatedLogs
        )

        if (battleResult.winner == AIBattleWinner.ATTACKER) {
            val rewards = requireNotNull(warRewards) { "warRewards must be set when ATTACKER wins" }
            val sectSurvivorIds = attackers.filter { it.id !in deadPlayerIds }.map { it.id }.toSet()
            grantWarSoulPowers(sectSurvivorIds)
            if (battleResult.canOccupy) {
                occupySectRewards(sectId, data, attackers, deadPlayerIds, rewards)
            } else {
                crushSectRewards(rewards)
            }
            applyVictoryPendingResult(log, teamMembers, rewards)
        } else {
            applyDefeatPendingResult(log, teamMembers)
        }
    }
}

/** 攻防双方构建打包（attackSect 提取） */
private data class SectAttackSetup(
    val defenderDisciples: List<Disciple>,
    val defenderPoolSectId: String,
    val fullDefenderPool: List<Disciple>
)

/** 攻防双方构建（attackSect 提取）：AI 占领者守军 / 常规守军两分支 */
private fun GameEngine.buildSectAttackSetup(
    data: GameData,
    targetSect: WorldSect,
    sectId: String,
    playerSect: WorldSect?
): SectAttackSetup {
    val isAiOccupied = targetSect.occupierSectId.isNotEmpty() && targetSect.occupierSectId != playerSect?.id
    val defenderDisciples = if (isAiOccupied) {
        val occupierDisciples = data.aiSectDisciples[targetSect.occupierSectId] ?: emptyList()
        targetSect.garrisonSlots.filter { it.discipleId.isNotEmpty() }.mapNotNull { slot -> occupierDisciples.find { d -> d.id == slot.discipleId && d.isAlive } }
    } else {
        val sectDisciplePool = data.aiSectDisciples[sectId] ?: emptyList()
        sectDisciplePool.filter { it.isAlive }.sortedBy { it.realm }.take(AISectAttackManager.TEAM_SIZE)
    }
    val defenderPoolSectId = if (isAiOccupied) targetSect.occupierSectId else sectId
    val fullDefenderPool = data.aiSectDisciples[sectId] ?: emptyList()
    return SectAttackSetup(defenderDisciples, defenderPoolSectId, fullDefenderPool)
}

/** AI 阵亡守军清理（attackSect 提取） */
private fun GameEngine.removeDeadDefenders(sectId: String, defenderPoolSectId: String, deadDefenderIds: Set<String>) {
    stateStore.update {
        gameData = gameData.copy(
            aiSectDisciples = gameData.aiSectDisciples.mapValues { (sId, d) -> if (sId == defenderPoolSectId) d.filter { it.id !in deadDefenderIds } else d },
            worldMapSects = gameData.worldMapSects.map { sect ->
                if (sect.id == sectId) sect.copy(garrisonSlots = sect.garrisonSlots.map { slot -> if (slot.discipleId in deadDefenderIds) GarrisonSlot(index = slot.index) else slot }) else sect
            }
        )
    }
}

/** 宗门战战报成员构建（attackSect 提取） */
private fun GameEngine.buildSectBattleLogMembers(
    attackers: List<Disciple>,
    defenderDisciples: List<Disciple>,
    targetSect: WorldSect,
    battleResult: AIBattleResult,
    deadPlayerIds: Set<String>,
    deadDefenderIds: Set<String>
): Pair<List<BattleLogMember>, List<BattleLogEnemy>> {
    val teamMembers = attackers.map { d -> BattleLogMember(id = d.id, name = d.name, realm = d.realm, realmName = d.realmName, hp = battleResult.survivorHpMap[d.id] ?: 0, maxHp = d.maxHp, mp = battleResult.survivorMpMap[d.id] ?: 0, maxMp = d.maxMp, isAlive = d.id !in deadPlayerIds, portraitRes = d.portraitRes) }
    val enemyMembers = defenderDisciples.map { d ->
        val survivorHp = battleResult.defenderSurvivorHpMap[d.id]
        val isDead = d.id in deadDefenderIds
        BattleLogEnemy(
            id = d.id,
            name = "${targetSect.name}弟子",
            realm = d.realm,
            realmName = d.realmName,
            hp = if (isDead) 0 else (survivorHp ?: d.maxHp),
            maxHp = d.maxHp,
            isAlive = !isDead,
            portraitRes = d.portraitRes
        )
    }
    return teamMembers to enemyMembers
}

/**
 * 宗门战战报组装（attackSect 提取）：战利品预计算 + drops + BattleLog。
 * 返回战报与战利品（胜方发放入库用）。
 */
private fun GameEngine.buildSectBattleLog(
    data: GameData,
    targetSect: WorldSect,
    battleResult: AIBattleResult,
    teamMembers: List<BattleLogMember>,
    enemyMembers: List<BattleLogEnemy>,
    deadPlayerIds: Set<String>
): Pair<BattleLog, WarRewards?> {
    val winResult = when (battleResult.winner) { AIBattleWinner.ATTACKER -> BattleResult.WIN; AIBattleWinner.DEFENDER -> BattleResult.LOSE; AIBattleWinner.DRAW -> BattleResult.DRAW }

    // 预计算战利品（用于日志 drops 显示）
    var warRewards: WarRewards? = null
    if (battleResult.winner == AIBattleWinner.ATTACKER) {
        val rewardCount = sectBattleRewardCount(
            battleResult.canOccupy, gameRngManager.getRng(RngPartition.BATTLE)
        )
        warRewards = generateWarRewards(targetSect.level, rewardCount)
    }

    val details = when (battleResult.winner) {
        AIBattleWinner.ATTACKER -> if (battleResult.canOccupy) "攻占了${targetSect.name}"
        else "击溃了${targetSect.name}的守军"
        AIBattleWinner.DEFENDER -> "进攻${targetSect.name}失败"
        AIBattleWinner.DRAW -> "与${targetSect.name}打成平手"
    }
    val log = BattleLog(
        year = data.gameYear, month = data.gameMonth, type = BattleType.SECT_WAR,
        attackerName = "玩家队伍", defenderName = targetSect.name, result = winResult,
        teamMembers = teamMembers, enemies = enemyMembers, rounds = battleResult.rounds,
        turns = battleResult.turns, teamCasualties = deadPlayerIds.size,
        drops = buildWarDrops(warRewards), details = details
    )
    return log to warRewards
}

/** 宗门战战利品文本摘要（buildSectBattleLog 提取，用于战报 drops 显示） */
private fun buildWarDrops(warRewards: WarRewards?): List<String> {
    val drops = mutableListOf<String>()
    warRewards?.let { wr ->
        if (wr.spiritStones > 0) drops.add("灵石 ×${wr.spiritStones}")
        wr.equipmentStacks.forEach { drops.add("${it.name} ×${it.quantity}") }
        wr.manualStacks.forEach { drops.add("${it.name} ×${it.quantity}") }
        wr.pills.forEach { drops.add("${it.name} ×${it.quantity}") }
        wr.materials.forEach { drops.add("${it.name} ×${it.quantity}") }
        wr.herbs.forEach { drops.add("${it.name} ×${it.quantity}") }
        wr.seeds.forEach { drops.add("${it.name} ×${it.quantity}") }
    }
    return drops
}

/** 宗门战战绩记录（attackSect 提取，近3年内用于附属决策算法） */
private fun GameEngine.recordSectBattleRecord(battleType: SectBattleType, updatedLogs: List<BattleLog>) {
    stateStore.update {
        gameData = gameData.copy(
            sectBattleRecords = gameData.sectBattleRecords + SectBattleRecord(
                year = gameData.gameYear,
                type = battleType
            )
        )
        battleLogs = updatedLogs
    }
}

/** 胜方存活弟子魂魄+1（attackSect 提取） */
private fun GameEngine.grantWarSoulPowers(sectSurvivorIds: Set<String>) {
    stateStore.update { discipleTables.ids.filter { it.toString() in sectSurvivorIds && discipleTables.isAlive[it] == 1 }.forEach { id -> discipleTables.soulPowers[id] = discipleTables.soulPowers[id] + 1 } }
}

/**
 * 占领奖励（attackSect 提取）：驻军槽位 + 俘虏过滤 + 单事务入账 + 占领事件。
 * 事务内调用 grantWarRewardsInside，保持原子性。
 */
private fun GameEngine.occupySectRewards(
    sectId: String,
    data: GameData,
    attackers: List<Disciple>,
    deadPlayerIds: Set<String>,
    rewards: WarRewards
) {
    val targetSect = data.worldMapSects.find { it.id == sectId } ?: return
    val playerSect = data.worldMapSects.find { it.isPlayerSect }
    val survivors = attackers.filter { it.id !in deadPlayerIds }
    val garrisonSlots = targetSect.garrisonSlots.mapIndexed { index, _ ->
        if (index < survivors.size) { val d = survivors[index]; GarrisonSlot(index = index, discipleId = d.id, discipleName = d.name, discipleRealm = d.realmName, discipleSpiritRootColor = d.spiritRoot.countColor, portraitRes = d.portraitRes) } else GarrisonSlot(index = index)
    }
    val capturedDisciples = data.aiSectDisciples[sectId]?.filter { it.isAlive } ?: emptyList()
    // 按俘虏灵根过滤规则分流
    val rawFilter = data.prisonerSpiritRootFilter
    // 守卫：只接受 1-5（有效灵根数量），剔除入库不合理值/负值
    val prisonerFilter = rawFilter.filter { it in 1..5 }.toSet()
    val acceptedCaptives = if (prisonerFilter.isNotEmpty()) {
        capturedDisciples.filter { d ->
            d.spiritRootType.split(",").count { it.isNotBlank() } in prisonerFilter
        }
    } else {
        capturedDisciples // 无过滤规则或全部被守卫过滤时全部接收
    }
    if (acceptedCaptives.size < capturedDisciples.size) {
        DomainLog.i("GameEngine",
            "俘虏管理: 接收${acceptedCaptives.size}人, " +
            "丢弃${capturedDisciples.size - acceptedCaptives.size}人")
    }
    stateStore.update {
        gameData = gameData.copy(
            worldMapSects = gameData.worldMapSects.toList().map { sect -> if (sect.id == sectId) sect.copy(isPlayerOccupied = true, occupierSectId = playerSect?.id ?: "", garrisonSlots = garrisonSlots) else sect },
            recruitList = gameData.recruitList.toList() + acceptedCaptives,
            aiSectDisciples = gameData.aiSectDisciples.toMutableMap().apply { this[sectId] = emptyList() },
            // 宗门被占领后与其相关的所有附属关系一并清除
            vassalContracts = gameData.vassalContracts.filter { it.vassalSectId != sectId },
            suzerainSectId = if (gameData.suzerainSectId == sectId)
                "" else gameData.suzerainSectId
        )
        grantWarRewardsInside(this, rewards)
        recordGameEvent(
            GameEventCategory.WORLD, GameEventType.SECT_OCCUPY,
            "玩家宗门占领了${targetSect.name}"
        )
    }
}

/** 击溃奖励入账（attackSect 提取） */
private fun GameEngine.crushSectRewards(rewards: WarRewards) {
    stateStore.update {
        grantWarRewardsInside(this, rewards)
    }
}

/** 胜利结算 UI 结果（attackSect 提取） */
private fun GameEngine.applyVictoryPendingResult(
    log: BattleLog,
    teamMembers: List<BattleLogMember>,
    rewards: WarRewards
) {
    stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = true, teamMembers = teamMembers, rewards = warRewardsToBattleRewardItems(rewards)))
    stateStore.setPendingBattleRewardCards(buildBattleRewardCards(rewards))
}

/** 失败结算 UI 结果（attackSect 提取） */
private fun GameEngine.applyDefeatPendingResult(log: BattleLog, teamMembers: List<BattleLogMember>) {
    stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = false, teamMembers = teamMembers, rewards = emptyList()))
}

    /**
     * 发放宗门战奖励（灵石 + 装备/功法/丹药/草药/材料/种子六类）。
     * 在调用方 stateStore.update 事务内执行，保持原子性。
     */
    private fun GameEngine.grantWarRewardsInside(state: MutableGameState, rewards: WarRewards) {
        spiritStoneWallet.add(state, rewards.spiritStones,
            SpiritStoneGrade.LOW, SpiritStoneSource.Battle)
        inventorySystem.withTrackingSource("battle") {
            rewards.equipmentStacks.forEach { item ->
                grantStackResult(item.name, inventorySystem.addEquipmentStack(item))
            }
        }
        rewards.manualStacks.forEach { item ->
            grantStackResult(item.name, inventorySystem.addManualStack(item))
        }
        inventorySystem.withTrackingSource("battle") {
            rewards.pills.forEach { item ->
                grantStackResult(item.name, inventorySystem.addPill(item))
            }
            rewards.herbs.forEach { item ->
                grantStackResult(item.name, inventorySystem.addHerb(item))
            }
        }
        rewards.materials.forEach { item ->
            grantStackResult(item.name, inventorySystem.addMaterial(item))
        }
        rewards.seeds.forEach { item ->
            grantStackResult(item.name, inventorySystem.addSeed(item))
        }
    }

    /** 物品入库结果统一日志（成功静默/部分溢出/失败告警） */
    private fun GameEngine.grantStackResult(name: String, result: DomainResult<*>) {
        when (result) {
            is DomainResult.Success -> {}
            is DomainResult.Partial -> DomainLog.w("GameEngine", "$name 溢出 ${result.overflow} 个")
            is DomainResult.Failure -> DomainLog.w("GameEngine", "添加 $name 失败: ${result.error}")
        }
    }


    private fun GameEngine.buildBattleRewardCards(rewards: WarRewards): List<RewardCardItem> {
        val cards = mutableListOf<RewardCardItem>()
        if (rewards.spiritStones > 0) {
            cards.add(RewardCardItem(itemName = ItemNames.SPIRIT_STONE, itemType = "spiritStones", rarity = Rarity.COMMON.toInt(), quantity = rewards.spiritStones.toInt()))
        }
        rewards.equipmentStacks.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "equipment", rarity = it.rarity, quantity = it.quantity)) }
        rewards.manualStacks.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "manual", rarity = it.rarity, quantity = it.quantity)) }
        rewards.pills.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "pill", rarity = it.rarity, quantity = it.quantity)) }
        rewards.materials.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "material", rarity = it.rarity, quantity = it.quantity)) }
        rewards.herbs.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "herb", rarity = it.rarity, quantity = it.quantity)) }
        rewards.seeds.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "seed", rarity = it.rarity, quantity = it.quantity)) }
        return cards
    }

private fun warRewardsToBattleRewardItems(rewards: WarRewards): List<BattleRewardItem> {
    val items = mutableListOf<BattleRewardItem>()
    if (rewards.spiritStones > 0) items.add(BattleRewardItem(name = ItemNames.SPIRIT_STONE, quantity = rewards.spiritStones.toInt(), rarity = Rarity.COMMON.toInt(), type = "spiritStones"))
    rewards.equipmentStacks.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "equipment")) }
    rewards.manualStacks.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "manual")) }
    rewards.pills.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "pill")) }
    rewards.materials.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "material")) }
    rewards.herbs.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "herb")) }
    rewards.seeds.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity, rarity = it.rarity, type = "seed")) }
    return items
}

// ── Garrison ────────────────────────────────────────────────────────

suspend fun GameEngine.assignGarrisonDisciple(sectId: String, slotIndex: Int, discipleId: String) {
    return engineContextDispatcher.withEngineContext {
        val data = stateStore.gameDataSnapshot
        val disciple = stateStore.discipleTables.assemble(discipleId.toInt()).takeIf { it.isAlive } ?: return@withEngineContext
        val targetSect = data.worldMapSects.find { it.id == sectId } ?: return@withEngineContext
        if (targetSect.garrisonSlots.any { it.discipleId == discipleId }) return@withEngineContext
        stateStore.update {
            gameData = gameData.copy(worldMapSects = gameData.worldMapSects.map { sect ->
                if (sect.id == sectId) sect.copy(garrisonSlots = sect.garrisonSlots.map { slot ->
                    if (slot.index == slotIndex) GarrisonSlot(index = slotIndex, discipleId = disciple.id, discipleName = disciple.name, discipleRealm = disciple.realmName, discipleSpiritRootColor = disciple.spiritRoot.countColor, portraitRes = disciple.portraitRes) else slot
                }) else sect
            })
        }
        val slotRef = SlotRef(
            category = SlotCategory.GARRISON_SLOT,
            slotType = "${sectId}:${slotIndex}",
            slotId = "garrison_${sectId}_${slotIndex}"
        )
        assignmentGate.confirmAssign(discipleId, slotRef)
    }
}

suspend fun GameEngine.removeGarrisonDisciple(sectId: String, slotIndex: Int) {
    return engineContextDispatcher.withEngineContext {
        val currentDiscipleId = stateStore.gameDataSnapshot.worldMapSects
            .find { it.id == sectId }
            ?.garrisonSlots?.find { it.index == slotIndex }?.discipleId.orEmpty()
        stateStore.update {
            gameData = gameData.copy(worldMapSects = gameData.worldMapSects.map { sect ->
                if (sect.id == sectId) sect.copy(garrisonSlots = sect.garrisonSlots.map { slot -> if (slot.index == slotIndex) GarrisonSlot(index = slotIndex) else slot }) else sect
            })
        }
        if (currentDiscipleId.isNotEmpty()) {
            assignmentGate.release(currentDiscipleId)
        }
    }
}

// ── Attack world level ──────────────────────────────────────────────

suspend fun GameEngine.attackWorldLevel(levelId: String, discipleIds: List<String?>) {
    return engineContextDispatcher.withEngineContext {
        val data = stateStore.gameDataSnapshot
        val level = data.worldLevels.find { it.id == levelId } ?: return@withEngineContext
        if (level.defeated) return@withEngineContext
        val validIds = discipleIds.filterNotNull()
        if (validIds.isEmpty()) return@withEngineContext
        // 遭遇战检查：该妖兽已被 AI 宗门盯上，与 AI 打遭遇战，胜者进攻妖兽
        if (data.aiBeastEncounterTargets.containsKey(levelId)) {
            val allDisciples = stateStore.discipleTables.assembleAll()
            val combatDisciples = validIds.mapNotNull { id -> allDisciples.find { it.id == id && it.isAlive } }
            if (combatDisciples.isEmpty()) return@withEngineContext
            resolveBeastAttackFight(levelId, manualDefenders = combatDisciples)
            return@withEngineContext
        }
        val setup = buildWorldLevelBattle(data, level, validIds) ?: return@withEngineContext
        val hpMap = setup.result.battle.team.associate { it.id to (it.hp to it.mp) }
        val survivorIds = setup.result.battle.team.filter { !it.isDead }.map { it.id }.toSet()
        applyWorldLevelCasualties(hpMap, survivorIds)
        val combatDiscipleIds = setup.combatDisciples.map { it.id }.toSet()
        val deadIds = stateStore.discipleTables.ids.filter { it.toString() in combatDiscipleIds && stateStore.discipleTables.isAlive[it] == 0 }.map { it.toString() }.toSet()
        if (deadIds.isNotEmpty()) { try { combatService.processBattleCasualties(deadIds, emptyMap(), emptyMap(), isOutsideSect = true) } catch (e: Exception) { DomainLog.e("GameEngine", "processBattleCasualties failed for deadIds=$deadIds, continuing", e) } }
        val (log, teamMembers) = buildWorldLevelBattleLog(data, level, setup.result, survivorIds)
        val updatedLogs = (stateStore.battleLogsSnapshot + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
        if (setup.result.victory) {
            // TOCTOU 防护：合并魂魄/属性更新+defeated标记为单次原子事务，
            // 入口重新检查 defeated，防止并发线程（巡视塔等）重复发放奖励
            applyWorldLevelVictoryTransaction(levelId, survivorIds, updatedLogs)
            applyVictoryRewards(level, setup.result, log, teamMembers)
        } else {
            applyWorldLevelDefeat(log, teamMembers, updatedLogs)
        }
    }
}

/** 关卡战斗构建打包（attackWorldLevel 提取）；combatDisciples 为空返回 null */
private data class WorldLevelBattleSetup(
    val result: BattleSystemResult,
    val combatDisciples: List<Disciple>
)

/** 战前结算 + 战斗构建执行（attackWorldLevel 提取） */
private fun GameEngine.buildWorldLevelBattle(
    data: GameData,
    level: WorldLevel,
    validIds: List<String>
): WorldLevelBattleSetup? {
    stateStore.update {
        cultivationService.forceSettleDisciplesBeforeBattle(
            this, validIds
        )
    }
    val allDisciples = stateStore.discipleTables.assembleAll()
    val combatDisciples = validIds.mapNotNull { id -> allDisciples.find { it.id == id && it.isAlive } }
    if (combatDisciples.isEmpty()) return null
    val equipmentMap = stateStore.equipmentInstancesSnapshot.associateBy { it.id }
    val manualMap = stateStore.manualInstancesSnapshot.associateBy { it.id }
    val beastTypeName = if (level.isBeast) GameConfig.Beast.getType(level.beastType ?: 0).name else null
    val allProficiencies = data.manualProficiencies.mapValues { (_, list) -> list.associateBy { it.manualId } }
    val beastPreGenStats = if (level.isBeast && level.beastMaxHp > 0) BattleSystem.BeastPreGenStats(
        maxHp = level.beastMaxHp,
        maxMp = level.beastMaxMp,
        physicalAttack = level.beastPhysicalAttack,
        magicAttack = level.beastMagicAttack,
        physicalDefense = level.beastPhysicalDefense,
        magicDefense = level.beastMagicDefense,
        speed = level.beastSpeed,
        realmLayer = level.realmLayer
    ) else null
    val battle = battleSystem.createBattle(disciples = combatDisciples, equipmentMap = equipmentMap, manualMap = manualMap, beastLevel = level.realm, beastCount = level.count, beastType = beastTypeName, manualProficiencies = allProficiencies, beastPreGenStats = beastPreGenStats)
    // 严苛训练政策：玩家弟子伤害+5%（参数透传，替代原 @Volatile 单例字段）
    val playerDamageModifier = if (data.sectPolicies.strictTraining) {
        1.0 + GameConfig.PolicyConfig.STRICT_TRAINING_DAMAGE
    } else 1.0
    val result = battleSystem.executeBattle(battle, playerDamageModifier)
    return WorldLevelBattleSetup(result = result, combatDisciples = combatDisciples)
}

/** 战后 HP/MP 回写 + 死亡标记（attackWorldLevel 提取，单事务） */
private fun GameEngine.applyWorldLevelCasualties(hpMap: Map<String, Pair<Int, Int>>, survivorIds: Set<String>) {
    stateStore.update {
        for (id in discipleTables.ids) {
            val idStr = id.toString()
            val (hp, mp) = hpMap[idStr] ?: continue
            if (idStr !in survivorIds) {
                discipleTables.markDead(id, gameData.gameYear, "battle")
            } else {
                val maxHp = discipleTables.baseHps[id]
                val maxMp = discipleTables.baseMps[id]
                discipleTables.currentHps[id] = hp.coerceIn(0, maxHp)
                discipleTables.currentMps[id] = mp.coerceIn(0, maxMp)
            }
        }
    }
}

/** 关卡战报组装（attackWorldLevel 提取） */
private fun GameEngine.buildWorldLevelBattleLog(
    data: GameData,
    level: WorldLevel,
    result: BattleSystemResult,
    survivorIds: Set<String>
): Pair<BattleLog, List<BattleLogMember>> {
    val teamMembers = result.battle.team.map { m -> BattleLogMember(id = m.id, name = m.name, realm = m.realm, realmName = m.realmName, hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp, isAlive = !m.isDead, portraitRes = m.portraitRes) }
    val enemies = result.battle.beasts.map { b ->
        BattleLogEnemy(
            id = b.id, name = b.name,
            realm = b.realm, realmName = b.realmName,
            hp = b.hp, maxHp = b.maxHp,
            isAlive = !b.isDead, portraitRes = b.portraitRes
        )
    }
    val rounds = result.log.rounds.map { r -> BattleLogRound(roundNumber = r.roundNumber, actions = r.actions.map { a -> BattleLogAction(type = a.type, attacker = a.attacker, attackerType = a.attackerType, target = a.target, damage = a.damage, damageType = a.damageType, isCrit = a.isCrit, isKill = a.isKill, message = a.message) }) }
    val log = BattleLog(year = data.gameYear, month = data.gameMonth, type = BattleType.PVE, attackerName = "玩家队伍", defenderName = if (level.isBeast) level.beastName else level.guardianName, result = if (result.victory) BattleResult.WIN else BattleResult.LOSE, teamMembers = teamMembers, enemies = enemies, rounds = rounds, turns = result.turnCount, teamCasualties = teamMembers.count { !survivorIds.contains(it.name) }, beastsDefeated = if (result.victory) level.count else result.battle.beasts.count { it.isDead }, details = if (result.victory) "击败了${if (level.isBeast) level.beastName else level.guardianName}" else "被${if (level.isBeast) level.beastName else level.guardianName}击败")
    return log to teamMembers
}

/** 胜利原子事务（attackWorldLevel 提取）：入口重复 defeated 检查 + 魂魄/属性增长 + defeated 标记 */
private fun GameEngine.applyWorldLevelVictoryTransaction(
    levelId: String,
    survivorIds: Set<String>,
    updatedLogs: List<BattleLog>
) {
    stateStore.update {
        val currentLevel = gameData.worldLevels.find { it.id == levelId }
        if (currentLevel == null || currentLevel.defeated) return@update

        for (id in discipleTables.ids) {
            val idStr = id.toString()
            if (idStr in survivorIds && discipleTables.isAlive[id] == 1) {
                discipleTables.soulPowers[id] = discipleTables.soulPowers[id] + 1
                if (discipleTables.talentIds[id].any { tid -> TalentDatabase.getById(tid)?.effects?.containsKey("winBattleRandomAttrPlus") == true }) {
                    applyDeterministicWinAttr(id, this)
                }
            }
        }
        gameData = gameData.copy(
            worldLevels = gameData.worldLevels.map { l ->
                if (l.id == levelId) l.copy(defeated = true) else l
            }
        )
        battleLogs = updatedLogs
    }
}

/** 确定性随机属性增长（applyWorldLevelVictoryTransaction 提取；须在 update 事务内调用） */
@Suppress("CyclomaticComplexMethod") // 17 分支确定性分发表（0-16 可穷举，数据驱动会引入反射/映射样板）
private fun GameEngine.applyDeterministicWinAttr(id: Int, state: MutableGameState) {
    // 确定性随机：用弟子 ID 散列代替 kotlin.random.Random 确保读档一致性
    val r = ((id * 527 + 31) % 17).let { if (it < 0) -it else it }
    when (r) {
        0 -> discipleTables.intelligences[id] = discipleTables.intelligences[id] + 1
        1 -> discipleTables.comprehensions[id] = discipleTables.comprehensions[id] + 1
        2 -> discipleTables.charms[id] = discipleTables.charms[id] + 1
        3 -> discipleTables.loyalties[id] = discipleTables.loyalties[id] + 1
        4 -> discipleTables.artifactRefinings[id] = discipleTables.artifactRefinings[id] + 1
        5 -> discipleTables.pillRefinings[id] = discipleTables.pillRefinings[id] + 1
        6 -> discipleTables.spiritPlantings[id] = discipleTables.spiritPlantings[id] + 1
        7 -> discipleTables.minings[id] = discipleTables.minings[id] + 1
        8 -> discipleTables.teachings[id] = discipleTables.teachings[id] + 1
        9 -> {
            val newMoral = discipleTables.moralities[id] + 1
            discipleTables.moralities[id] = newMoral
            // 道德变化后即时触发偷盗判定（事务内版本）
            if (newMoral < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD) {
                lawEnforcementProcessor.processSingleDiscipleTheft(id, state)
            }
        }
        10 -> discipleTables.baseHps[id] = discipleTables.baseHps[id] + 1
        11 -> discipleTables.baseMps[id] = discipleTables.baseMps[id] + 1
        12 -> discipleTables.basePhysicalAttacks[id] = discipleTables.basePhysicalAttacks[id] + 1
        13 -> discipleTables.baseMagicAttacks[id] = discipleTables.baseMagicAttacks[id] + 1
        14 -> discipleTables.basePhysicalDefenses[id] = discipleTables.basePhysicalDefenses[id] + 1
        15 -> discipleTables.baseMagicDefenses[id] = discipleTables.baseMagicDefenses[id] + 1
        16 -> discipleTables.baseSpeeds[id] = discipleTables.baseSpeeds[id] + 1
    }
}

/** 胜利奖励发放 + 结算 UI 结果（attackWorldLevel 提取） */
private suspend fun GameEngine.applyVictoryRewards(
    level: WorldLevel,
    result: BattleSystemResult,
    log: BattleLog,
    teamMembers: List<BattleLogMember>
) {
    val allRewards = mutableListOf<BattleRewardItem>()
    if (level.isBeast) {
        allRewards.addAll(handleBeastLevelVictory(level))
        val engineSsRewards = result.rewards["spiritStones"] ?: 0
        if (engineSsRewards > 0) {
            addSpiritStones(engineSsRewards.toLong())
            allRewards.add(BattleRewardItem(name = ItemNames.SPIRIT_STONE, quantity = engineSsRewards, rarity = Rarity.COMMON.toInt(), type = "spiritStones"))
        }
    } else {
        allRewards.addAll(handleCaveLevelVictory(level))
    }
    stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = true, teamMembers = teamMembers, rewards = allRewards))
}

/** 失败结算 UI 结果 + 战报落库（attackWorldLevel 提取） */
private fun GameEngine.applyWorldLevelDefeat(
    log: BattleLog,
    teamMembers: List<BattleLogMember>,
    updatedLogs: List<BattleLog>
) {
    stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = false, teamMembers = teamMembers, rewards = emptyList()))
    stateStore.update { battleLogs = updatedLogs }
}

// ── Scout sect ──────────────────────────────────────────────────────

suspend fun GameEngine.scoutSect(sectId: String, memberIds: List<String>) {
    return engineContextDispatcher.withEngineContext {
        ensureHeavyDataLoaded()
        val data = stateStore.gameDataSnapshot
        val targetSect = data.worldMapSects.find { it.id == sectId } ?: return@withEngineContext
        if (memberIds.isNotEmpty()) {
            stateStore.update {
                cultivationService.forceSettleDisciplesBeforeBattle(
                    this, memberIds
                )
            }
        }
        val allDisciples = stateStore.discipleTables.assembleAll()
        val combatDisciples = memberIds.mapNotNull { id -> allDisciples.find { it.id == id && it.isAlive } }
        if (combatDisciples.isEmpty()) return@withEngineContext
        val aiDefenders = (data.aiSectDisciples[sectId] ?: emptyList()).filter { it.isAlive && it.realm in 7..9 }.take(8)
        val equipmentMap = stateStore.equipmentInstancesSnapshot.associateBy { it.id }
        val manualMap = stateStore.manualInstancesSnapshot.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) -> list.associateBy { it.manualId } }
        val playerCombatants = buildScoutPlayerCombatants(combatDisciples, equipmentMap, manualMap, allProficiencies)
        val aiCombatants = aiDefenders.map { d -> AISectAttackManager.convertToCombatant(d, CombatantSide.ATTACKER) }
        val battle = Battle(team = playerCombatants, beasts = aiCombatants, turn = 0, isFinished = false, winner = null, maxTurns = Int.MAX_VALUE)
        // 严苛训练政策：玩家弟子伤害+5%（参数透传）
        val playerDamageModifier = if (data.sectPolicies.strictTraining) {
            1.0 + GameConfig.PolicyConfig.STRICT_TRAINING_DAMAGE
        } else 1.0
        val result = battleSystem.executeBattle(battle, playerDamageModifier)
        val hpMap = result.battle.team.associate { it.id to (it.hp to it.mp) }
        val survivorIds = result.battle.team.filter { !it.isDead }.map { it.id }.toSet()
        applyScoutCasualties(hpMap, survivorIds)
        val scoutDiscipleIds = combatDisciples.map { it.id }.toSet()
        val scoutDeadIds = stateStore.discipleTables.ids.filter { it.toString() in scoutDiscipleIds && stateStore.discipleTables.isAlive[it] == 0 }.map { it.toString() }.toSet()
        if (scoutDeadIds.isNotEmpty()) combatService.processBattleCasualties(scoutDeadIds, emptyMap(), emptyMap(), isOutsideSect = true)
        val (log, teamMembers) = buildScoutBattleLog(data, targetSect, result, aiCombatants, survivorIds)
        val victory = result.victory
        stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = victory, teamMembers = teamMembers, rewards = emptyList()))
        if (victory) {
            applyScoutVictoryInfo(sectId, data, targetSect)
        }
    }
}

/** 探查玩家 Combatant 构建（scoutSect 提取：装备/功法/熟练度/技能） */
private fun GameEngine.buildScoutPlayerCombatants(
    combatDisciples: List<Disciple>,
    equipmentMap: Map<String, EquipmentInstance>,
    manualMap: Map<String, ManualInstance>,
    allProficiencies: Map<String, Map<String, ManualProficiencyData>>
): List<Combatant> {
    return combatDisciples.map { disciple ->
        val discipleEquipment = buildDiscipleEquipmentMap(disciple, equipmentMap)
        val discipleManuals = disciple.manualIds.mapNotNull { id -> manualMap[id]?.let { id to it } }.toMap()
        val discipleProficiencies = allProficiencies[disciple.id] ?: emptyMap()
        val stats = disciple.getFinalStats(discipleEquipment, discipleManuals, discipleProficiencies)
        val effectiveHp = if (disciple.combat.currentHp < 0) stats.maxHp else disciple.combat.currentHp.coerceAtMost(stats.maxHp)
        val effectiveMp = if (disciple.combat.currentMp < 0) stats.maxMp else disciple.combat.currentMp.coerceAtMost(stats.maxMp)
        val skills = buildDiscipleSkills(disciple, discipleManuals, discipleProficiencies)
        Combatant(id = disciple.id, name = disciple.name, side = CombatantSide.DEFENDER, hp = effectiveHp, maxHp = stats.maxHp, mp = effectiveMp, maxMp = stats.maxMp, physicalAttack = stats.physicalAttack, magicAttack = stats.magicAttack, physicalDefense = stats.physicalDefense, magicDefense = stats.magicDefense, speed = stats.speed, critRate = stats.critRate, skills = skills, realm = disciple.realm, realmName = disciple.realmName, element = disciple.spiritRoot.types.firstOrNull()?.trim() ?: "metal", portraitRes = disciple.portraitRes)
    }
}

/** 弟子已装备实例映射（buildScoutPlayerCombatants 提取） */
private fun buildDiscipleEquipmentMap(
    disciple: Disciple,
    equipmentMap: Map<String, EquipmentInstance>
): Map<String, EquipmentInstance> {
    return buildMap {
        disciple.equipment.weaponId?.let { id -> equipmentMap[id]?.let { put(id, it) } }
        disciple.equipment.armorId?.let { id -> equipmentMap[id]?.let { put(id, it) } }
        disciple.equipment.bootsId?.let { id -> equipmentMap[id]?.let { put(id, it) } }
        disciple.equipment.accessoryId?.let { id -> equipmentMap[id]?.let { put(id, it) } }
    }
}

/** 弟子技能构建（buildScoutPlayerCombatants 提取：熟练度乘区加成） */
private fun buildDiscipleSkills(
    disciple: Disciple,
    discipleManuals: Map<String, ManualInstance>,
    discipleProficiencies: Map<String, ManualProficiencyData>
): List<CombatSkill> {
    return disciple.manualIds.mapNotNull { manualId ->
        val manual = discipleManuals[manualId] ?: return@mapNotNull null
        val proficiencyData = discipleProficiencies[manualId]
        val masteryLevel = proficiencyData?.masteryLevel ?: 0
        val baseSkill = manual.skill ?: return@mapNotNull null
        val multiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(baseSkill.damageMultiplier, masteryLevel)
        baseSkill.copy(damageMultiplier = multiplier).toCombatSkill(manualName = manual.name)
    }
}

/** 探查战后 HP/MP 回写 + 死亡标记（scoutSect 提取） */
private fun GameEngine.applyScoutCasualties(hpMap: Map<String, Pair<Int, Int>>, survivorIds: Set<String>) {
    stateStore.update {
        for (id in discipleTables.ids) {
            val idStr = id.toString()
            val (hp, mp) = hpMap[idStr] ?: continue
            if (idStr !in survivorIds) {
                discipleTables.markDead(id, gameData.gameYear, "scout")
            } else {
                val maxHp = discipleTables.baseHps[id]
                val maxMp = discipleTables.baseMps[id]
                discipleTables.currentHps[id] = hp.coerceIn(0, maxHp)
                discipleTables.currentMps[id] = mp.coerceIn(0, maxMp)
            }
        }
    }
}

/** 探查战报组装 + 落库（scoutSect 提取） */
private fun GameEngine.buildScoutBattleLog(
    data: GameData,
    targetSect: WorldSect,
    result: BattleSystemResult,
    aiCombatants: List<Combatant>,
    survivorIds: Set<String>
): Pair<BattleLog, List<BattleLogMember>> {
    val teamMembers = result.battle.team.map { m -> BattleLogMember(id = m.id, name = m.name, realm = m.realm, realmName = m.realmName, hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp, isAlive = !m.isDead, portraitRes = m.portraitRes) }
    val postBattleBeasts = result.battle.beasts.associateBy { it.id }
    val enemies = aiCombatants.map { b ->
        val postState = postBattleBeasts[b.id]
        BattleLogEnemy(
            id = b.id,
            name = "${targetSect.name}弟子",
            realm = b.realm,
            realmName = b.realmName,
            hp = postState?.hp ?: 0,
            maxHp = b.maxHp,
            isAlive = postState?.isDead != true,
            portraitRes = b.portraitRes
        )
    }
    val rounds = result.log.rounds.map { r -> BattleLogRound(roundNumber = r.roundNumber, actions = r.actions.map { a -> BattleLogAction(type = a.type, attacker = a.attacker, attackerType = a.attackerType, target = a.target, damage = a.damage, damageType = a.damageType, isCrit = a.isCrit, isKill = a.isKill, message = a.message) }) }
    val victory = result.victory
    val log = BattleLog(year = data.gameYear, month = data.gameMonth, type = BattleType.SCOUT, attackerName = "探查队伍", defenderName = targetSect.name, result = if (victory) BattleResult.WIN else BattleResult.LOSE, teamMembers = teamMembers, enemies = enemies, rounds = rounds, turns = result.turnCount, teamCasualties = teamMembers.size - survivorIds.size, beastsDefeated = if (victory) aiCombatants.count { result.battle.beasts.any { b -> b.id == it.id && b.isDead } } else 0, details = if (victory) "成功探查了${targetSect.name}" else "探查${targetSect.name}失败")
    val existingLogs = stateStore.battleLogsSnapshot
    val updatedLogs = (existingLogs + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
    stateStore.update { battleLogs = updatedLogs }
    return log to teamMembers
}

/** 探查胜利情报写入（scoutSect 提取） */
private fun GameEngine.applyScoutVictoryInfo(sectId: String, data: GameData, targetSect: WorldSect) {
    val allSectDisciples = data.aiSectDisciples[sectId] ?: emptyList()
    val aliveSectDisciples = allSectDisciples.filter { it.isAlive }
    val realmDistribution = aliveSectDisciples.groupingBy { it.realm }.eachCount()
    val scoutInfo = SectScoutInfo(sectId = sectId, sectName = targetSect.name, scoutYear = data.gameYear, scoutMonth = data.gameMonth, discipleCount = aliveSectDisciples.size, maxRealm = aliveSectDisciples.minOfOrNull { it.realm } ?: 9, disciples = realmDistribution, isKnown = true, expiryYear = data.gameYear + 1, expiryMonth = data.gameMonth)
    updateGameDataSync { val existingDetail = it.sectDetails[sectId] ?: SectDetail(sectId = sectId); it.copy(sectDetails = it.sectDetails + (sectId to existingDetail.copy(scoutInfo = scoutInfo))) }
}

// ── Private: Victory rewards ────────────────────────────────────────

private suspend fun GameEngine.handleBeastLevelVictory(level: WorldLevel): List<BattleRewardItem> {
    val rewards = mutableListOf<BattleRewardItem>()
    val beastConfig = GameConfig.Beast.getType(level.beastType ?: 0)
    val tier = GameConfig.Realm.getMaxRarity(level.realm)
    for (i in 0 until level.count) {
        repeat(2) { // 每只妖兽固定 2 个材料（原 Random.nextInt(1,4) 期望值≈2）
            val beastMaterial = com.xianxia.sect.core.registry.BeastMaterialDatabase.getRandomMaterialByBeastType(beastConfig.name, tier)
            if (beastMaterial != null) {
                val material = Material(id = java.util.UUID.randomUUID().toString(), name = beastMaterial.name, rarity = beastMaterial.rarity, description = beastMaterial.description, category = beastMaterial.materialCategory, quantity = 1)
                val result = inventorySystem.withTrackingSource("beast_world") { inventorySystem.addMaterial(material) }
                when (result) {
                    is DomainResult.Success -> rewards.add(BattleRewardItem(itemId = material.id, name = material.name, quantity = 1, rarity = material.rarity, type = "material"))
                    is DomainResult.Partial -> {
                        DomainLog.w("GameEngine", "${material.name} 溢出 ${result.overflow} 个")
                        rewards.add(BattleRewardItem(itemId = material.id, name = material.name, quantity = 1, rarity = material.rarity, type = "material"))
                    }
                    is DomainResult.Failure -> DomainLog.w("GameEngine", "添加 ${material.name} 失败: ${result.error}")
                }
            }
        }
    }
    return rewards
}

private suspend fun GameEngine.handleCaveLevelVictory(level: WorldLevel): List<BattleRewardItem> {
    val rewards = mutableListOf<BattleRewardItem>()
    val config = LevelGenerator.getCaveReward(level.realm)
    // 基于关卡 ID 散列的确定性奖励（替代 kotlin.random.Random）
    val seed = (level.id.hashCode() * 31 + level.realm).let { if (it < 0) -it else it }
    val spiritMultiplier = 0.8 + (seed % 5) * 0.1 // 0.8/0.9/1.0/1.1/1.2
    val spiritStones = (config.baseSpiritStones * spiritMultiplier).toLong()
    addSpiritStones(spiritStones)
    if (spiritStones > 0) rewards.add(BattleRewardItem(name = ItemNames.SPIRIT_STONE, quantity = spiritStones.toInt(), rarity = Rarity.COMMON.toInt(), type = "spiritStones"))
    val (minRarity, maxRarity) = config.rarityRange
    val itemCount = 1 + (seed / 7) % 6 // 1~6
    repeat(itemCount) {
        val rarity = minRarity + (seed / (11 * (it + 1))) % (maxRarity - minRarity + 1)
        val typeIndex = (seed / (13 * (it + 1))) % 3 // 0=功法, 1=装备, 2=丹药
        when (typeIndex) {
            0 -> {
                val manual = com.xianxia.sect.core.registry.ManualDatabase.generateRandom(rarity)
                if (manual != null) {
                    val result = inventorySystem.withTrackingSource("cave_world") { inventorySystem.addManualStack(manual) }
                    when (result) {
                        is DomainResult.Success -> rewards.add(BattleRewardItem(itemId = manual.id, name = manual.name, quantity = 1, rarity = manual.rarity, type = "manual"))
                        is DomainResult.Partial -> {
                            DomainLog.w("GameEngine", "${manual.name} 溢出 ${result.overflow} 个")
                            rewards.add(BattleRewardItem(itemId = manual.id, name = manual.name, quantity = 1, rarity = manual.rarity, type = "manual"))
                        }
                        is DomainResult.Failure -> DomainLog.w("GameEngine", "添加 ${manual.name} 失败: ${result.error}")
                    }
                }
            }
            1 -> {
                val equip = com.xianxia.sect.core.registry.EquipmentDatabase.generateRandom(rarity)
                if (equip != null) {
                    val result = inventorySystem.withTrackingSource("cave_world") { inventorySystem.addEquipmentStack(equip) }
                    when (result) {
                        is DomainResult.Success -> rewards.add(BattleRewardItem(itemId = equip.id, name = equip.name, quantity = 1, rarity = equip.rarity, type = "equipment"))
                        is DomainResult.Partial -> {
                            DomainLog.w("GameEngine", "${equip.name} 溢出 ${result.overflow} 个")
                            rewards.add(BattleRewardItem(itemId = equip.id, name = equip.name, quantity = 1, rarity = equip.rarity, type = "equipment"))
                        }
                        is DomainResult.Failure -> DomainLog.w("GameEngine", "添加 ${equip.name} 失败: ${result.error}")
                    }
                }
            }
            else -> {
                val pill = com.xianxia.sect.core.registry.ItemDatabase.generateRandomPill(rarity)
                if (pill != null) {
                    val result = inventorySystem.withTrackingSource("cave_world") { inventorySystem.addPill(pill) }
                    when (result) {
                        is DomainResult.Success -> rewards.add(BattleRewardItem(itemId = pill.id, name = pill.name, quantity = 1, rarity = pill.rarity, type = "pill"))
                        is DomainResult.Partial -> {
                            DomainLog.w("GameEngine", "${pill.name} 溢出 ${result.overflow} 个")
                            rewards.add(BattleRewardItem(itemId = pill.id, name = pill.name, quantity = 1, rarity = pill.rarity, type = "pill"))
                        }
                        is DomainResult.Failure -> DomainLog.w("GameEngine", "添加 ${pill.name} 失败: ${result.error}")
                    }
                }
            }
        }
    }
    return rewards
}
