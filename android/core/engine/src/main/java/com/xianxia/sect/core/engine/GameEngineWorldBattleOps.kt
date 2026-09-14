package com.xianxia.sect.core.engine


import com.xianxia.sect.core.util.ItemNames
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.exploration.LevelGenerator
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.Rarity
import com.xianxia.sect.core.engine.domain.disciple.battleWritebackMaxHpMp


// ── Attack world level ──────────────────────────────────────────────

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
suspend fun GameEngine.attackWorldLevel(levelId: String, discipleIds: List<String?>) {
    return engineContextDispatcher.withEngineContext {
        val data = stateStore.gameDataSnapshot
        val level = data.worldLevels.find { it.id == levelId } ?: return@withEngineContext
        if (level.defeated) return@withEngineContext
        val validIds = discipleIds.filterNotNull()
        if (validIds.isEmpty()) return@withEngineContext
        // 遭遇战检查：该妖兽已被 AI 宗门盯上，与 AI 打遭遇战，胜者进攻妖兽
        if (resolveBeastEncounterIfAny(levelId, validIds)) return@withEngineContext
        // ── Native 臂（AUTHORITATIVE）：关卡校验链/战斗执行（BATTLE 分区同序）/
        // 伤亡写回经 C++；奖励生成（Random.Default 非镜像随机域）/胜利事务
        // （soulPowers/winAttr/defeated TOCTOU 原子块）/战报留 Kotlin（S5/S6 口径）。
        // flag 关/镜像不可用/失败信封 → false 回退 Kotlin 原路径（双实现并行契约）
        if (attackWorldLevelNative(level, validIds)) return@withEngineContext
        val setup = buildWorldLevelBattle(data, level, validIds) ?: return@withEngineContext
        val hpMap = setup.result.battle.team.associate { it.id to (it.hp to it.mp) }
        val survivorIds = setup.result.battle.team.filter { !it.isDead }.map { it.id }.toSet()
        applyWorldLevelCasualties(hpMap, survivorIds)
        val combatDiscipleIds = setup.combatDisciples.map { it.id }.toSet()
        /** 本场永久死亡弟子 ID（调用方事务外触发哀伤） */
        val deadIds = stateStore.discipleTables.ids.filter { it.toString() in combatDiscipleIds && stateStore
            .discipleTables.isAlive[it] == 0 }.map { it.toString() }.toSet()
        if (deadIds.isNotEmpty()) {
            try {
                combatService.processBattleCasualties(deadIds, emptyMap(), emptyMap(), isOutsideSect = true)
            } catch (e: CancellationException) {
                throw e // 取消穿透: 引擎重启/退出时中止剩余世界关卡结算, 不吞取消继续发奖
            } catch (e: Exception) {
                DomainLog.e("GameEngine", "processBattleCasualties failed for deadIds=$deadIds, continuing", e)
            }
        }
        val (log, teamMembers) = buildWorldLevelBattleLog(data, level, setup.result, survivorIds)
        val updatedLogs = (stateStore.battleLogsSnapshot + log).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
        if (setup.result.victory) {
            // TOCTOU 防护：合并魂魄/属性更新+defeated标记为单次原子事务，
            // 入口重新检查 defeated，防止并发线程（巡视塔等）重复发放奖励
            applyWorldLevelVictoryTransaction(levelId, survivorIds, updatedLogs)
            applyVictoryRewards(level, setup.result.rewards["spiritStones"] ?: 0, log, teamMembers)
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
    val battle = battleSystem.createBattle(
        disciples = combatDisciples, equipmentMap = equipmentMap, manualMap = manualMap,
        beastLevel = level.realm, beastCount = level.count, beastType = beastTypeName,
        manualProficiencies = allProficiencies, beastPreGenStats = beastPreGenStats,
        bloodRefinementMap = data.bloodRefinementPctTotals
    )
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
                // 死亡统一入口——袋物品物化回仓库（玩家保留）+ 清袋 + 标记死亡
                inventorySystem.materializeDiscipleBagAndMarkDead(this, id, gameData.gameYear, "battle")
            } else {
                val (finalMaxHp, finalMaxMp) = DiscipleStatCalculator.battleWritebackMaxHpMp(
                    this, discipleTables.assemble(id)
                )
                discipleTables.currentHps[id] = hp.coerceIn(0, finalMaxHp)
                discipleTables.currentMps[id] = mp.coerceIn(0, finalMaxMp)
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
    val teamMembers = result.battle.team.map { m -> BattleLogMember(id = m.id, name = m.name, realm = m.realm,
        realmName = m.realmName, hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp, isAlive = !m.isDead,
            portraitRes = m.portraitRes) }
    val enemies = result.battle.beasts.map { b ->
        BattleLogEnemy(
            id = b.id, name = b.name,
            realm = b.realm, realmName = b.realmName,
            hp = b.hp, maxHp = b.maxHp,
            isAlive = !b.isDead, portraitRes = b.portraitRes
        )
    }
    val rounds = result.log.rounds.map { r -> BattleLogRound(roundNumber = r.roundNumber,
        actions = r.actions.map { a -> BattleLogAction(type = a.type, attacker = a.attacker,
            attackerType = a.attackerType, target = a.target, damage = a.damage, damageType = a.damageType,
                isCrit = a.isCrit, isKill = a.isKill, message = a.message) }) }
    val log = BattleLog(year = data.gameYear, month = data.gameMonth, type = BattleType.PVE, attackerName = "玩家队伍",
        defenderName = if (level.isBeast) level.beastName else level.guardianName,
            result = if (result.victory) BattleResult.WIN else BattleResult.LOSE, teamMembers = teamMembers,
                enemies = enemies, rounds = rounds, turns = result.turnCount,
                    teamCasualties = teamMembers.count { !survivorIds.contains(it.name) },
                        beastsDefeated = if (result.victory) level.count else result.battle.beasts.count { it.isDead },
                            details = if (result
                                .victory) "击败了${if (level.isBeast) level.beastName else level.guardianName}" else
                                    "被${if (level.isBeast) level.beastName else level.guardianName}击败")
    return log to teamMembers
}

/** 胜利原子事务（attackWorldLevel 提取）：入口重复 defeated 检查 + 魂魄/属性增长 + defeated 标记。
 *
 * [skipNativeDomainWrites]：native 臂（1781 WORLD_VICTORY_REWARDS_TX）已由 C++
 * 授予魂力/winAttr（battle_residual_tx.h ②，含 TOCTOU 重查与偷盗钩子）时传
 * true——本函数只执行残差段（重查 + defeated 标记 + 战报落库）。Kotlin 回退臂
 * 传 false 保持原全量行为。🔴 defeated 两臂均由 Kotlin 写（batch-13 TOCTOU
 * 口径：C++ 不写 defeated）。
 */
internal fun GameEngine.applyWorldLevelVictoryTransaction(
    levelId: String,
    survivorIds: Set<String>,
    updatedLogs: List<BattleLog>,
    skipNativeDomainWrites: Boolean = false
) {
    stateStore.update {
        val currentLevel = gameData.worldLevels.find { it.id == levelId }
        if (currentLevel == null || currentLevel.defeated) return@update

        if (!skipNativeDomainWrites) {
            for (id in discipleTables.ids) {
                val idStr = id.toString()
                if (idStr in survivorIds && discipleTables.isAlive[id] == 1) {
                    discipleTables.soulPowers[id] = discipleTables.soulPowers[id] + 1
                    if (discipleTables.talentIds[id].any { tid -> TalentDatabase
                        .getById(tid)?.effects?.containsKey("winBattleRandomAttrPlus") == true }) {
                        applyDeterministicWinAttr(id, this)
                    }
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
    // 技能属性（0-9）clamp 到基础属性上限（忠诚 100 例外）；战斗属性（10-16）不 clamp
    when (r) {
        0 -> discipleTables.intelligences[id] =
            minOf(discipleTables.intelligences[id] + 1, GameConfig.Disciple.SKILL_MAX)
        1 -> discipleTables.comprehensions[id] =
            minOf(discipleTables.comprehensions[id] + 1, GameConfig.Disciple.SKILL_MAX)
        2 -> discipleTables.charms[id] =
            minOf(discipleTables.charms[id] + 1, GameConfig.Disciple.SKILL_MAX)
        3 -> discipleTables.loyalties[id] =
            minOf(discipleTables.loyalties[id] + 1, GameConfig.Disciple.MAX_LOYALTY)
        4 -> discipleTables.artifactRefinings[id] =
            minOf(discipleTables.artifactRefinings[id] + 1, GameConfig.Disciple.SKILL_MAX)
        5 -> discipleTables.pillRefinings[id] =
            minOf(discipleTables.pillRefinings[id] + 1, GameConfig.Disciple.SKILL_MAX)
        6 -> discipleTables.spiritPlantings[id] =
            minOf(discipleTables.spiritPlantings[id] + 1, GameConfig.Disciple.SKILL_MAX)
        7 -> discipleTables.minings[id] =
            minOf(discipleTables.minings[id] + 1, GameConfig.Disciple.SKILL_MAX)
        8 -> discipleTables.teachings[id] =
            minOf(discipleTables.teachings[id] + 1, GameConfig.Disciple.SKILL_MAX)
        9 -> {
            val newMoral = minOf(
                discipleTables.moralities[id] + 1, GameConfig.Disciple.SKILL_MAX
            )
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

/** 胜利奖励发放 + 结算 UI 结果（attackWorldLevel 提取；灵石奖励参数化——
 * Kotlin 回退臂取 BattleSystemResult.rewards，native 臂取 C++ 战斗奖励段，
 * 奖励生成（Random.Default 非镜像随机域）两臂同形留 Kotlin） */
internal suspend fun GameEngine.applyVictoryRewards(
    level: WorldLevel,
    spiritStonesReward: Int,
    log: BattleLog,
    teamMembers: List<BattleLogMember>
) {
    val allRewards = mutableListOf<BattleRewardItem>()
    if (level.isBeast) {
        allRewards.addAll(handleBeastLevelVictory(level))
        if (spiritStonesReward > 0) {
            addSpiritStones(spiritStonesReward.toLong())
            allRewards.add(BattleRewardItem(name = ItemNames.SPIRIT_STONE, quantity = spiritStonesReward,
                rarity = Rarity.COMMON.toInt(), type = "spiritStones"))
        }
    } else {
        allRewards.addAll(handleCaveLevelVictory(level))
    }
    stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = true,
        teamMembers = teamMembers, rewards = allRewards))
}

/** 失败结算 UI 结果 + 战报落库（attackWorldLevel 提取） */
internal fun GameEngine.applyWorldLevelDefeat(
    log: BattleLog,
    teamMembers: List<BattleLogMember>,
    updatedLogs: List<BattleLog>
) {
    stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = false,
        teamMembers = teamMembers, rewards = emptyList()))
    stateStore.update { battleLogs = updatedLogs }
}

// ── Private: Victory rewards ────────────────────────────────────────

private suspend fun GameEngine.handleBeastLevelVictory(level: WorldLevel): List<BattleRewardItem> {
    val rewards = mutableListOf<BattleRewardItem>()
    val beastConfig = GameConfig.Beast.getType(level.beastType ?: 0)
    val tier = GameConfig.Realm.getMaxRarity(level.realm)
    repeat(level.count) { // 每只妖兽固定 2 个材料（原 Random.nextInt(1,4) 期望值≈2）
            val beastMaterial = com.xianxia.sect.core.registry.BeastMaterialDatabase
                .getRandomMaterialByBeastType(beastConfig.name, tier)
            if (beastMaterial != null) {
                val material = Material(id = java.util.UUID.randomUUID().toString(), name = beastMaterial.name,
                    rarity = beastMaterial.rarity, description = beastMaterial.description,
                        category = beastMaterial.materialCategory, quantity = 1)
                val result = inventorySystem.withTrackingSource("beast_world") { inventorySystem.addMaterial(material) }
                when (result) {
                    is DomainResult.Success -> rewards.add(BattleRewardItem(itemId = material.id, name = material.name,
                        quantity = 1, rarity = material.rarity, type = "material"))
                    is DomainResult.Partial -> {
                        DomainLog.w("GameEngine", "${material.name} 溢出 ${result.overflow} 个")
                        rewards.add(BattleRewardItem(itemId = material.id, name = material.name, quantity = 1,
                            rarity = material.rarity, type = "material"))
                    }
                    is DomainResult.Failure -> DomainLog.w("GameEngine", "添加 ${material.name} 失败: ${result.error}")
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
    if (spiritStones > 0) rewards.add(BattleRewardItem(name = ItemNames.SPIRIT_STONE, quantity = spiritStones.toInt(),
        rarity = Rarity.COMMON.toInt(), type = "spiritStones"))
    val (minRarity, maxRarity) = config.rarityRange
    val itemCount = 1 + (seed / 7) % 6 // 1~6
    repeat(itemCount) {
        grantSingleCaveReward(
            rewards = rewards,
            seed = seed,
            index = it,
            minRarity = minRarity,
            maxRarity = maxRarity
        )
    }
    return rewards
}

/** 单次洞穴奖励掉落：按确定性类型索引分发功法/装备/丹药 */
private fun GameEngine.grantSingleCaveReward(
    rewards: MutableList<BattleRewardItem>,
    seed: Int,
    index: Int,
    minRarity: Int,
    maxRarity: Int
) {
    val rarity = minRarity + (seed / (11 * (index + 1))) % (maxRarity - minRarity + 1)
    val typeIndex = (seed / (13 * (index + 1))) % 3 // 0=功法, 1=装备, 2=丹药
    when (typeIndex) {
        0 -> {
            val manual = com.xianxia.sect.core.registry.ManualDatabase.generateRandom(rarity)
            val result = inventorySystem.withTrackingSource("cave_world") { inventorySystem.addManualStack(manual) }
            when (result) {
                is DomainResult.Success -> rewards.add(BattleRewardItem(itemId = manual.id, name = manual.name,
                    quantity = 1, rarity = manual.rarity, type = "manual"))
                is DomainResult.Partial -> {
                    DomainLog.w("GameEngine", "${manual.name} 溢出 ${result.overflow} 个")
                    rewards.add(BattleRewardItem(itemId = manual.id, name = manual.name, quantity = 1,
                        rarity = manual.rarity, type = "manual"))
                }
                is DomainResult.Failure -> DomainLog.w("GameEngine", "添加 ${manual.name} 失败: ${result.error}")
            }
        }
        1 -> {
            val equip = com.xianxia.sect.core.registry.EquipmentDatabase.generateRandom(rarity)
            val result = inventorySystem.withTrackingSource("cave_world") { inventorySystem.addEquipmentStack(equip) }
            when (result) {
                is DomainResult.Success -> rewards.add(BattleRewardItem(itemId = equip.id, name = equip.name,
                    quantity = 1, rarity = equip.rarity, type = "equipment"))
                is DomainResult.Partial -> {
                    DomainLog.w("GameEngine", "${equip.name} 溢出 ${result.overflow} 个")
                    rewards.add(BattleRewardItem(itemId = equip.id, name = equip.name, quantity = 1,
                        rarity = equip.rarity, type = "equipment"))
                }
                is DomainResult.Failure -> DomainLog.w("GameEngine", "添加 ${equip.name} 失败: ${result.error}")
            }
        }
        else -> {
            val pill = com.xianxia.sect.core.registry.ItemDatabase.generateRandomPill(rarity)
            val result = inventorySystem.withTrackingSource("cave_world") { inventorySystem.addPill(pill) }
            when (result) {
                is DomainResult.Success -> rewards.add(BattleRewardItem(itemId = pill.id, name = pill.name,
                    quantity = 1, rarity = pill.rarity, type = "pill"))
                is DomainResult.Partial -> {
                    DomainLog.w("GameEngine", "${pill.name} 溢出 ${result.overflow} 个")
                    rewards.add(BattleRewardItem(itemId = pill.id, name = pill.name, quantity = 1, rarity = pill.rarity,
                        type = "pill"))
                }
                is DomainResult.Failure -> DomainLog.w("GameEngine", "添加 ${pill.name} 失败: ${result.error}")
            }
        }
    }
}
