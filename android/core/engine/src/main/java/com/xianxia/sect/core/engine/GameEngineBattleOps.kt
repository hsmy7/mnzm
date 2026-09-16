package com.xianxia.sect.core.engine


import com.xianxia.sect.core.util.ItemNames
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.SectBattleRecord
import com.xianxia.sect.core.model.SectBattleType
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.state.recordGameEvent
import com.xianxia.sect.core.engine.domain.battle.AIBattleResult
import com.xianxia.sect.core.engine.domain.battle.AIBattleWinner
import com.xianxia.sect.core.engine.domain.battle.generateWarRewards
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.battle.WarRewards
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.model.Rarity





// ── 宗门战战利品数量（分区 RNG，可存档复现）──

/** 宗门战胜利战利品数量：攻占 80~130，击溃 20~60（分区 RNG，可存档复现） */
internal fun sectBattleRewardCount(canOccupy: Boolean, rng: DeterministicRng): Int {
    val range = if (canOccupy) SECT_BATTLE_OCCUPY_REWARD_COUNT else SECT_BATTLE_ROUT_REWARD_COUNT
    return range.first + rng.nextInt(range.last - range.first + 1)
}

private val SECT_BATTLE_OCCUPY_REWARD_COUNT = 80..130
private val SECT_BATTLE_ROUT_REWARD_COUNT = 20..60

// ── Attack sect ─────────────────────────────────────────────────────

suspend fun GameEngine.attackSect(sectId: String, attackSlots: List<Pair<Int, DiscipleAggregate>>) {
    return engineContextDispatcher.withEngineContext {
        ensureHeavyDataLoaded()
        val data = stateStore.gameDataSnapshot
        val targetSect = data.worldMapSects.find { it.id == sectId } ?: return@withEngineContext

        // 不能攻击空 ID、自己的宗门或已由玩家占领的宗门（避免重复俘虏）
        if (sectId.isBlank() || targetSect.isPlayerSect || targetSect.isPlayerOccupied) return@withEngineContext
        // 不能攻击自己的附属宗门
        if (data.vassalContracts.any { it.vassalSectId == sectId }) return@withEngineContext

        val combatIds = attackSlots.map { it.second.id }
        // 战前结算（1782 native 臂——与 w3-06 ExplorationNativeOps:134 同一事务
        // 界面；降级回退 Kotlin 原路径）
        if (combatIds.isNotEmpty() && !forceSettleDisciplesBeforeBattleNative(combatIds)) {
            stateStore.update { cultivationService.forceSettleDisciplesBeforeBattle(this, combatIds) }
        }
        val allDisciples = stateStore.discipleTables.assembleAll()
        val attackers = attackSlots.mapNotNull { (_, agg) -> allDisciples.find { it.id == agg.id && it.isAlive } }
        if (attackers.isEmpty()) return@withEngineContext
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val setup = buildSectAttackSetup(data, targetSect, sectId, playerSect)
        // 玩家进攻方必须按实例语义构建 Combatant（与 scoutSect/PlayerDefenseProcessor 一致）：
        // 玩家弟子的装备/功法字段是实例 id（UUID），若经 AISectAttackManager.convertToCombatant
        // （AI 模板 id 语义，模板表 key 为 "windBoots" 等模板 id）查询必然 miss，
        // 玩家将裸装、无功法技能参战——高境界打低境界也必败（2026-XX 回归根因）。
        val combatAttackers = buildSectAttackCombatants(data, attackers)
        val battleResult = AISectAttackManager.executeSectBattleWithCombatantAttackers(
            combatAttackers, targetSect, setup.defenderDisciples, setup.fullDefenderPool,
            rngManager = gameRngManager
        )
        val deadPlayerIds = battleResult.deadAttackerIds.toSet()
        combatService.processBattleCasualties(deadMemberIds = deadPlayerIds, survivorHpMap = battleResult.survivorHpMap,
            survivorMpMap = battleResult.survivorMpMap, isOutsideSect = true)
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
        recordSectBattleRecord(battleRecordTypeFor(battleResult), updatedLogs)

        if (battleResult.winner == AIBattleWinner.ATTACKER) {
            val rewards = requireNotNull(warRewards) { "warRewards must be set when ATTACKER wins" }
            val sectSurvivorIds = attackers.filter { it.id !in deadPlayerIds }.map { it.id }.toSet()
            grantWarSoulPowers(sectSurvivorIds)
            if (battleResult.canOccupy) {
                occupySectRewards(sectId, data, attackers, deadPlayerIds, rewards)
            } else {
                crushSectRewards(rewards)
                // w3-13 通道关闭配套（§2.80）：碾压奖励写面（9 类集合 + sectDetails 等）
                // 发生后全量重建 native 基线（占领路径由 occupySectRewards 尾部 §2.78
                // 接线承载，无需重复）
                rebaselineNativeMirror("攻宗碾压奖励")
            }
            applyVictoryPendingResult(log, teamMembers, rewards)
        } else {
            applyDefeatPendingResult(log, teamMembers)
            // w3-13 通道关闭配套（§2.80）：败北路径的战史写面（sectBattleRecords，
            // recordSectBattleRecord 先于本分支）发生后全量重建 native 基线回导
            // C++（countRecentBattleRecords 消费方语义保持）
            rebaselineNativeMirror("攻宗败北")
        }
    }
}

/** 战报类型推导（attackSect 提取——detekt 复杂度拆分单元） */
private fun battleRecordTypeFor(battleResult: AIBattleResult): SectBattleType =
    when (battleResult.winner) {
        AIBattleWinner.ATTACKER ->
            if (battleResult.canOccupy) SectBattleType.CONQUEST else SectBattleType.BATTLE_WIN
        else -> SectBattleType.BATTLE_LOSS
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
        targetSect.garrisonSlots.filter { it.discipleId.isNotEmpty() }.mapNotNull { slot -> occupierDisciples
            .find { d -> d.id == slot.discipleId && d.isAlive } }
    } else {
        val sectDisciplePool = data.aiSectDisciples[sectId] ?: emptyList()
        sectDisciplePool.filter { it.isAlive }.sortedBy { it.realm }.take(AISectAttackManager.TEAM_SIZE)
    }
    val defenderPoolSectId = if (isAiOccupied) targetSect.occupierSectId else sectId
    val fullDefenderPool = data.aiSectDisciples[sectId] ?: emptyList()
    return SectAttackSetup(defenderDisciples, defenderPoolSectId, fullDefenderPool)
}

/**
 * 玩家进攻方 Combatant 构建（attackSect 提取）：实例表语义，与 scoutSect/PlayerDefenseProcessor 一致。
 *
 * 玩家弟子的装备/功法字段是实例 id（UUID），必须从装备/功法实例快照（equipmentInstancesSnapshot /
 * manualInstancesSnapshot）构建映射；禁止走 [AISectAttackManager.convertToCombatant]
 * （AI 模板 id 语义，实例 id 查模板表必然 miss，玩家将裸装无技能参战——高境界打低境界也必败）。
 */
private fun GameEngine.buildSectAttackCombatants(
    data: GameData,
    attackers: List<Disciple>
): List<Combatant> {
    val equipmentMap = stateStore.equipmentInstancesSnapshot.associateBy { it.id }
    val manualMap = stateStore.manualInstancesSnapshot.associateBy { it.id }
    val allProficiencies = data.manualProficiencies.mapValues { (_, list) -> list.associateBy { it.manualId } }
    return attackers.map { d ->
        battleSystem.convertDiscipleToCombatant(
            d, equipmentMap, manualMap, allProficiencies,
            CombatantSide.ATTACKER,
            bloodRefinementPct = data.bloodRefinementPctTotals[d.id]
        )
    }
}

/** AI 阵亡守军清理（attackSect 提取） */
private fun GameEngine.removeDeadDefenders(sectId: String, defenderPoolSectId: String, deadDefenderIds: Set<String>) {
    // native 臂（batch-20b）：C++ 承 aiSectDisciples 段过滤 + 目标宗门驻军槽清空
    // （零 RNG 纯确定性变换）；成功即完成，失败/降级走下方 Kotlin 原实现。
    if (removeDeadDefendersNative(sectId, defenderPoolSectId, deadDefenderIds)) return
    stateStore.update {
        gameData = gameData.copy(
            aiSectDisciples = gameData.aiSectDisciples.mapValues { (sId,
                d) -> if (sId == defenderPoolSectId) d.filter { it.id !in deadDefenderIds } else d },
            worldMapSects = gameData.worldMapSects.map { sect ->
                if (sect.id == sectId) sect.copy(garrisonSlots = sect.garrisonSlots.map { slot -> if (slot
                    .discipleId in deadDefenderIds) GarrisonSlot(index = slot.index) else slot }) else sect
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
    val teamMembers = attackers.map { d -> BattleLogMember(id = d.id, name = d.name, realm = d.realm,
        realmName = d.realmName, hp = battleResult.survivorHpMap[d.id] ?: 0, maxHp = d.maxHp, mp = battleResult
            .survivorMpMap[d.id] ?: 0, maxMp = d.maxMp, isAlive = d.id !in deadPlayerIds, portraitRes = d.portraitRes) }
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
    val winResult = when (battleResult.winner) { AIBattleWinner.ATTACKER -> BattleResult.WIN; AIBattleWinner
        .DEFENDER -> BattleResult.LOSE; AIBattleWinner.DRAW -> BattleResult.DRAW }

    // 预计算战利品（用于日志 drops 显示）
    var warRewards: WarRewards? = null
    if (battleResult.winner == AIBattleWinner.ATTACKER) {
        val rewardCount = sectBattleRewardCount(
            battleResult.canOccupy, gameRngManager.getRng(RngPartition.BATTLE)
        )
        warRewards = generateWarRewards(
            targetSect.level, rewardCount, gameRngManager.getRng(RngPartition.BATTLE)
        )
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
        // 战史按消费窗口裁剪（审计 P2-6 / 方案 D3）：追加时只保留
        // year >= gameYear - BATTLE_RECORD_WINDOW_YEARS——消费方
        //（countRecentBattleRecords，C++ sect_attack_decision.h 同窗口 3 年）
        // 只看近 3 年，窗口外条目零消费者纯开销
        val windowFloor = gameData.gameYear - BATTLE_RECORD_WINDOW_YEARS
        gameData = gameData.copy(
            sectBattleRecords = (gameData.sectBattleRecords + SectBattleRecord(
                year = gameData.gameYear,
                type = battleType
            )).filter { it.year >= windowFloor }
        )
        battleLogs = updatedLogs
    }
}

/** 战史消费窗口（年）：与 C++ countRecentBattleRecords（sect_attack_decision.h）
 *  的 year >= gameYear - 3 同源常量（审计 P2-6，改值须双端同步） */
private const val BATTLE_RECORD_WINDOW_YEARS = 3

/** 胜方存活弟子魂魄+1（attackSect 提取） */
private fun GameEngine.grantWarSoulPowers(sectSurvivorIds: Set<String>) {
    // native 臂（batch-20b）：C++ 承 rowOf 行序 + 存活性过滤 + 逐行自增（零 RNG）；
    // 成功即完成，失败/降级走下方 Kotlin 原实现。
    if (grantWarSoulPowersNative(sectSurvivorIds)) return
    stateStore.update { discipleTables.ids.filter { it.toString() in sectSurvivorIds && discipleTables
        .isAlive[it] == 1 }.forEach { id -> discipleTables.soulPowers[id] = discipleTables.soulPowers[id] + 1 } }
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
        if (index < survivors.size) { val d = survivors[index]; GarrisonSlot(index = index, discipleId = d.id,
            discipleName = d.name, discipleRealm = d.realmName, discipleSpiritRootColor = d.spiritRoot.countColor,
                portraitRes = d.portraitRes) } else GarrisonSlot(index = index)
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
    // 捕获豁免（updateMirror，§2.81）：事务内 grantWarRewardsInside 写 9 类实体集合
    // （第三段已关闭回导，值等值收敛不适用于集合通道的引用比较检测）——写入经尾部
    // 基线重建回导 C++
    stateStore.updateMirror {
        gameData = gameData.copy(
            worldMapSects = gameData.worldMapSects.toList().map { sect -> if (sect.id == sectId) sect
                .copy(isPlayerOccupied = true, occupierSectId = playerSect?.id ?: "",
                    garrisonSlots = garrisonSlots) else sect },
            recruitList = gameData.recruitList.toList() + acceptedCaptives,
            aiSectDisciples = gameData.aiSectDisciples.toMutableMap().apply { this[sectId] = emptyList() },
            // 宗门被占领后与其相关的所有附属关系一并清除
            vassalContracts = gameData.vassalContracts.filter { it.vassalSectId != sectId },
            suzerainSectId = if (gameData.suzerainSectId == sectId)
                "" else gameData.suzerainSectId,
            // 记录"玩家持有（占领）宗门"权威标记（isOwned）——独立于
            // worldMapSects，随存档持久化且不被世界重生清除，供归一化/净化/重生保留判定。
            sectDetails = gameData.sectDetails + (sectId to (
                gameData.sectDetails[sectId] ?: SectDetail(sectId = sectId)
            ).copy(isOwned = true))
        )
        grantWarRewardsInside(this, rewards)
        recordGameEvent(
            GameEventCategory.WORLD, GameEventType.SECT_OCCUPY,
            "玩家宗门占领了${targetSect.name}"
        )
    }
    // w3-13 通道关闭配套：占领写面（worldMapSects/aiSectDisciples 清池 + recruitList
    // 俘虏）已关闭回导——写入完成即重建 native 基线（§2.75④ "自愈后全量重建基线"）
    rebaselineNativeMirror("攻宗占领")
}

/** 击溃奖励入账（attackSect 提取） */
private fun GameEngine.crushSectRewards(rewards: WarRewards) {
    // 捕获豁免（updateMirror，§2.81）：grantWarRewardsInside 写 9 类实体集合
    // （已关闭回导）——写入经调用方尾部基线重建（attackSect "攻宗碾压奖励"）回导 C++
    stateStore.updateMirror {
        grantWarRewardsInside(this, rewards)
    }
}

// 战利品簇共享（WarRewardOps 调用）
internal fun warRewardsToBattleRewardItems(rewards: WarRewards): List<BattleRewardItem> {
    val items = mutableListOf<BattleRewardItem>()
    if (rewards.spiritStones > 0) items.add(BattleRewardItem(name = ItemNames.SPIRIT_STONE,
        quantity = rewards.spiritStones.toInt(), rarity = Rarity.COMMON.toInt(), type = "spiritStones"))
    rewards.equipmentStacks.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity,
        rarity = it.rarity, type = "equipment")) }
    rewards.manualStacks.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity,
        rarity = it.rarity, type = "manual")) }
    rewards.pills.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity,
        rarity = it.rarity, type = "pill")) }
    rewards.materials.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity,
        rarity = it.rarity, type = "material")) }
    rewards.herbs.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity,
        rarity = it.rarity, type = "herb")) }
    rewards.seeds.forEach { items.add(BattleRewardItem(itemId = it.id, name = it.name, quantity = it.quantity,
        rarity = it.rarity, type = "seed")) }
    return items
}
