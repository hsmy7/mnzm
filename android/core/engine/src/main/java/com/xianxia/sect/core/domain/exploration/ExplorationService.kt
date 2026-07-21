package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.domain.battle.EncounterAttacker
import com.xianxia.sect.core.domain.battle.EncounterBattleService
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.exploration.BeastAttackDetector
import com.xianxia.sect.core.exploration.ExplorationTeamManager
import com.xianxia.sect.core.exploration.LootCalculator
import com.xianxia.sect.core.exploration.PatrolBattleSystem
import com.xianxia.sect.core.exploration.WorldLevelManager
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 探索系统 Facade — 委派具体职责到各子领域系统。
 *
 * 保留的对外接口：
 * - [processMonthlyWorldLevels] — 由 ExplorationTickSystem 调用
 * - [resolveBeastAttackPayTribute] / [resolveBeastAttackFight] — 玩家主动操作
 * - [consumePendingPatrolResults] — UI 层定时消费
 * - [getTeams] — UI 层读取
 */
@Singleton
class ExplorationService @Inject constructor(
    private val stateStore: GameStateStore,
    private val battleSystem: BattleSystem,
    private val inventorySystem: InventorySystem,
    private val cultivationService: CultivationService,
    private val spiritStoneWallet: SpiritStoneWallet,
    // 子领域系统
    private val worldLevelManager: WorldLevelManager,
    private val beastAttackDetector: BeastAttackDetector,
    private val patrolBattleSystem: PatrolBattleSystem,
    private val lootCalculator: LootCalculator,
    private val explorationTeamManager: ExplorationTeamManager,
    private val rngManager: GameRngManager,
    private val encounterBattleService: EncounterBattleService
) {
    /** 妖兽防守战斗结果弹窗缓存（resolveBeastFightInternal 写，UI 层读） */
    companion object {
        private const val TAG = "ExplorationService"
    }

    // ── 月度处理（由 ExplorationTickSystem 调用） ───────────────────────────

    /**
     * 月度世界事件处理 — 委派给子领域系统。
     *
     * 1. WorldLevelManager — 关卡刷新/过期清理/妖兽移动
     * 2. PatrolBattleSystem — 巡视楼自动攻击（先于检测，避免已击败妖兽产生预警）
     * 3. BeastAttackDetector — 检测妖兽攻击（仅检测巡视楼未击败的剩余妖兽）
     */
    fun processMonthlyWorldLevels(state: MutableGameState) {
        // 计算玩家存活弟子的平均境界，传给 LevelGenerator 做安全兜底
        val disciples = state.discipleTables.assembleAll()
        val aliveDisciples = disciples.filter { it.isAlive }
        val playerAvgRealm = if (aliveDisciples.isNotEmpty()) {
            aliveDisciples.map { it.realm }.average().toInt()
        } else null

        // Step 1: 世界关卡惰性管理（纯函数）
        try {
            state.gameData = worldLevelManager.processMonthly(state.gameData, playerAvgRealm)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "processMonthlyWorldLevels: worldLevelManager failed", e)
        }

        // Step 2: 巡视楼自动攻击（先于攻击检测，避免已击败妖兽产生无效预警）
        try {
            patrolBattleSystem.executePatrolRound(state)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "processMonthlyWorldLevels: patrolBattle failed", e)
        }

        // Step 3: 妖兽攻击检测（仅检测巡视楼未击败的剩余妖兽）
        try {
            val attacks = beastAttackDetector.detectAttacks(state.gameData)
            if (attacks.isNotEmpty()) {
                stateStore.setPendingBeastAttacks(attacks)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "processMonthlyWorldLevels: detectAttacks failed", e)
        }
    }

    // ── 巡视塔战斗结果 ─────────────────────────────────────────────────────

    /** 消费未展示的战斗结果弹窗（巡视塔 + 妖兽防守），由 GameEngineCore 每 tick 调用 */
    suspend fun consumePendingPatrolResults(): List<BattleResultUIData> {
        val patrolResults = patrolBattleSystem.consumePendingPatrolResults()
        val defenseResults = stateStore.gameData.value
            .pendingPatrolBattleResults
        if (defenseResults.isNotEmpty()) {
            stateStore.update {
                gameData = gameData.copy(
                    pendingPatrolBattleResults = emptyList()
                )
            }
        }
        return patrolResults + defenseResults
    }

    // ── 妖兽袭击处理（玩家主动操作） ──────────────────────────────────────

    suspend fun resolveBeastAttackPayTribute(beastLevelId: String): Boolean {
        val gd = stateStore.gameData.value
        val level = gd.worldLevels.find { it.id == beastLevelId } ?: return false
        if (level.defeated) return false
        val targetSect = gd.worldMapSects.find {
            it.isPlayerSect || it.isPlayerOccupied
        }
        val tribute = (gd.spiritStones *
            GameConfig.WorldMap.BEAST_TRIBUTE_RATIO).toLong()
            .coerceAtLeast(GameConfig.WorldMap.BEAST_TRIBUTE_MIN)

        var paid = false
        stateStore.update {
            val deductResult = spiritStoneWallet.deduct(
                this, tribute, SpiritStoneGrade.LOW,
                SpiritStoneReason.BeastTribute,
                SpiritStoneSource.Internal
            )
            if (deductResult !is DeductResult.Success) return@update
            paid = true
            gameData = gameData.copy(
                worldLevels = gameData.worldLevels.map {
                    if (it.id == beastLevelId) it.copy(defeated = true) else it
                }
            )
            battleLogs = (battleLogs + BattleLog(
                year = gameData.gameYear, month = gameData.gameMonth,
                type = BattleType.PVE,
                attackerName = level.beastName.ifEmpty { "妖兽" },
                defenderName = targetSect?.name ?: "",
                result = BattleResult.WIN,
                details = "上交${tribute}灵石，妖兽退去"
            )).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
        }
        return paid
    }

    suspend fun resolveBeastAttackFight(
        beastLevelId: String,
        manualDefenders: List<Disciple>? = null
    ): Boolean {
        val snapshot = stateStore.gameData.value
        val level = snapshot.worldLevels.find { it.id == beastLevelId } ?: return false
        if (level.defeated) return false
        var handled = false
        stateStore.update {
            // 锁内二次检查 defeated（防 TOCTOU：锁外快照可能已过时）
            val currentLevel = gameData.worldLevels.find { it.id == beastLevelId }
            if (currentLevel == null || currentLevel.defeated) return@update

            // 遭遇战检查：妖兽附近有 AI 宗门拦截
            val aiSectId = gameData.aiBeastEncounterTargets[beastLevelId]
            if (aiSectId != null) {
                val aiSect = gameData.worldMapSects.find { it.id == aiSectId }
                val targetSect = gameData.worldMapSects.find {
                    it.isPlayerSect || it.isPlayerOccupied
                }
                if (aiSect != null && targetSect != null) {
                    // 使用手动选择的弟子（世界地图进攻）或自动选择（弹窗迎战）
                    // 手动选择路径在锁内重新查询弟子状态，避免锁外快照的 isAlive 过期
                    val defenders = if (manualDefenders != null) {
                        val manualIds = manualDefenders.map { it.id }.toSet()
                        discipleTables.assembleAll().filter { it.id in manualIds && it.isAlive }
                    } else {
                        val allAlive = discipleTables.assembleAll().filter { it.isAlive }
                        val pids = gameData.patrolSlots
                            .filter { it.discipleId.isNotEmpty() }
                            .map { it.discipleId }.toSet()
                        val patrol = allAlive.filter { it.id in pids }
                        val excludeStatuses = setOf(
                            DiscipleStatus.ON_MISSION,
                            DiscipleStatus.REFLECTING,
                            DiscipleStatus.REFINING
                        )
                        val remaining = allAlive.filter {
                            it.id !in pids && it.status !in excludeStatuses
                        }.sortedByDescending { it.realmLayer }
                        (patrol + remaining).take(8)
                    }
                    val defenderIds = defenders.map { it.id }.toSet()
                    prepareBeastDefenders(defenderIds)
                    if (defenders.isNotEmpty()) {
                        val aiTeam = gameData.aiSectDisciples[aiSectId]
                            ?.filter { it.isAlive }
                            ?.take(GameConfig.AI.TEAM_SIZE) ?: emptyList()
                        if (aiTeam.isNotEmpty()) {
                            encounterBattleService.encounter(
                                state = this,
                                attackerA = EncounterAttacker(
                                    sectId = targetSect.id,
                                    sectName = targetSect.name,
                                    isPlayer = true,
                                    teamDisciples = defenders
                                ),
                                attackerB = EncounterAttacker(
                                    sectId = aiSect.id,
                                    sectName = aiSect.name,
                                    isPlayer = false,
                                    teamDisciples = aiTeam
                                ),
                                beast = level,
                                year = gameData.gameYear,
                                month = gameData.gameMonth
                            )
                            gameData = gameData.copy(
                                aiBeastEncounterTargets =
                                    gameData.aiBeastEncounterTargets - beastLevelId
                            )
                            handled = true
                            return@update
                        }
                    }
                }
            }
            // 无遭遇战，走正常妖兽战斗路径
            handled = true
            resolveBeastFightInternal(beastLevelId, level)
        }
        return handled
    }

    // ── 内部战斗编排（≤60 行，委派各子阶段） ─────────────────────────────

    private fun MutableGameState.resolveBeastFightInternal(
        beastLevelId: String, level: WorldLevel
    ) {
        val gd = gameData
        val targetSect = gd.worldMapSects.find {
            it.isPlayerSect || it.isPlayerOccupied
        } ?: return

        // 自动选择防守弟子：优先巡视塔已分配弟子，其次宗门内其他弟子
        // 排除任务中/思过中/血炼中的弟子，其余均可参战
        var disciples = discipleTables.assembleAll()
        val allAlive = disciples.filter { it.isAlive }
        val patrolDiscipleIds = gd.patrolSlots
            .filter { it.discipleId.isNotEmpty() }
            .map { it.discipleId }
            .toSet()
        val patrolDefenders = allAlive.filter { it.id in patrolDiscipleIds }
        val excludeStatuses = setOf(
            DiscipleStatus.ON_MISSION,
            DiscipleStatus.REFLECTING,
            DiscipleStatus.REFINING
        )
        val remainingAlive = allAlive.filter {
            it.id !in patrolDiscipleIds && it.status !in excludeStatuses
        }.sortedByDescending { it.realmLayer }
        val defenders = (patrolDefenders + remainingAlive).take(8)
        val defenderIds = defenders.map { it.id }.toSet()

        prepareBeastDefenders(defenderIds)

        markBeastDefeated(beastLevelId)

        if (defenders.isEmpty()) {
            handleNoBeastDefenders(level, targetSect, disciples)
            return
        }

        val result = createBeastBattle(defenders, level)
        val (processedDisciples, survivorIds) = processBeastCasualties(
            result, targetSect, disciples
        )
        disciples = processedDisciples

        val allRewards = mutableListOf<BattleRewardItem>()
        if (result.victory) {
            disciples = applyBeastVictoryBonuses(disciples)
            allRewards += collectBeastFightRewards(level, result)
        } else {
            applyBeastDefeatLoot()
        }

        buildBeastDefenseBattleLog(
            result, level, targetSect, survivorIds, allRewards
        )
        finalizeBeastDisciples(disciples)
    }

    // ── 战前准备 ───────────────────────────────────────────────────────────

    private fun MutableGameState.prepareBeastDefenders(
        garrisonIds: Set<String>
    ) {
        if (garrisonIds.isNotEmpty()) {
            cultivationService.forceSettleDisciplesBeforeBattle(
                this, garrisonIds.toList()
            )
        }
    }

    private fun MutableGameState.markBeastDefeated(
        beastLevelId: String
    ) {
        gameData = gameData.copy(
            worldLevels = gameData.worldLevels.map {
                if (it.id == beastLevelId) it.copy(defeated = true) else it
            }
        )
    }

    // ── 无守卫处理 ─────────────────────────────────────────────────────────

    private fun MutableGameState.handleNoBeastDefenders(
        level: WorldLevel, targetSect: WorldSect,
        disciples: List<Disciple>
    ) {
        val loot = lootCalculator.computeLootPlan(gameData, this)
        lootCalculator.applyLoot(this, loot)
        battleLogs = (battleLogs + BattleLog(
            year = gameData.gameYear, month = gameData.gameMonth,
            type = BattleType.PVE,
            attackerName = level.beastName.ifEmpty { "妖兽" },
            defenderName = if (targetSect.isPlayerSect) "玩家宗门"
                else targetSect.name,
            result = BattleResult.LOSE,
            details = loot.toDetailString(level.beastName)
        )).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
        // 妖兽防守结果始终显示，不受 patrolBattleResultPopup 设置影响
        gameData = gameData.copy(
            pendingPatrolBattleResults =
                gameData.pendingPatrolBattleResults +
                BattleResultUIData(
                    battleLogId = "", victory = false,
                    teamMembers = emptyList(),
                    rewards = emptyList(),
                    lootedItems = loot.toRewardItems(),
                    isBeastDefense = true
                )
        )
        discipleTables.replaceAll(disciples)
    }

    // ── 战斗执行 ───────────────────────────────────────────────────────────

    private fun MutableGameState.createBeastBattle(
        defenders: List<Disciple>, level: WorldLevel
    ): BattleSystemResult {
        val equipMap = equipmentInstances.associateBy { it.id }
        val manMap = manualInstances.associateBy { it.id }
        val profMap = gameData.manualProficiencies.mapValues {
            (_, list) -> list.associateBy { it.manualId }
        }
        val beastPreGenStats = if (level.beastMaxHp > 0) BattleSystem.BeastPreGenStats(
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
            defenders, equipMap, manMap,
            level.realm, level.count,
            GameConfig.Beast.getType(level.beastType ?: 0).name,
            profMap,
            beastPreGenStats = beastPreGenStats
        )
        return battleSystem.executeBattle(battle)
    }

    // ── 战后伤亡处理 ───────────────────────────────────────────────────────

    private fun MutableGameState.processBeastCasualties(
        result: BattleSystemResult, targetSect: WorldSect,
        disciples: List<Disciple>
    ): Pair<List<Disciple>, Set<String>> {
        val garrisonIds = targetSect.garrisonSlots
            .filter { it.discipleId.isNotEmpty() }
            .map { it.discipleId }.toSet()

        val hpMap = result.battle.team.associate {
            it.id to (it.hp to it.mp)
        }
        val survivorIds = result.battle.team.filter { !it.isDead }
            .map { it.id }.toSet()
        val deadDefenders = disciples.filter {
            it.id in garrisonIds && it.id !in survivorIds
        }

        var processed = disciples.map { d ->
            val (hp, mp) = hpMap[d.id] ?: return@map d
            if (d.id !in survivorIds) {
                d.copy(
                    isAlive = false, status = DiscipleStatus.DEAD
                )
            } else {
                d.copy(combat = d.combat.copy(
                    currentHp = hp.coerceIn(0, d.maxHp),
                    currentMp = mp.coerceIn(0, d.maxMp)
                ))
            }
        }

        if (deadDefenders.isNotEmpty()) {
            processed = DiscipleStatCalculator.applyGriefToRelatives(
                processed, deadDefenders, gameData.gameYear
            )
        }

        val deadIds = processed.filter { !it.isAlive }
            .map { it.id }.toSet()
        if (deadIds.isNotEmpty()) {
            gameData = gameData.copy(
                worldMapSects = gameData.worldMapSects.map { sect ->
                    if (sect.id == targetSect.id) {
                        sect.copy(
                            garrisonSlots =
                                sect.garrisonSlots.map { slot ->
                                    if (slot.discipleId in deadIds) {
                                        GarrisonSlot(index = slot.index)
                                    } else slot
                                }
                        )
                    } else sect
                }
            )
        }

        return processed to survivorIds
    }

    // ── 胜利奖励：神魂+随机属性 ──────────────────────────────────────────

    private fun MutableGameState.applyBeastVictoryBonuses(
        disciples: List<Disciple>
    ): List<Disciple> {
        return disciples.map { d ->
            if (d.isAlive) {
                var m = d.copy(soulPower = d.soulPower + 1)
                if (m.talentIds.any { id ->
                    TalentDatabase.getById(id)?.effects
                        ?.containsKey("winBattleRandomAttrPlus") == true
                }) {
                    val r = rngManager.getRng(RngPartition.BATTLE)
                        .nextInt(17)
                    val sk = m.skills; val cb = m.combat
                    when (r) {
                        0 -> sk.intelligence++; 1 -> sk.comprehension++
                        2 -> sk.charm++; 3 -> sk.loyalty++
                        4 -> sk.artifactRefining++; 5 -> sk.pillRefining++
                        6 -> sk.spiritPlanting++; 7 -> sk.mining++
                        8 -> sk.teaching++; 9 -> sk.morality++
                        10 -> cb.baseHp++; 11 -> cb.baseMp++
                        12 -> cb.basePhysicalAttack++
                        13 -> cb.baseMagicAttack++
                        14 -> cb.basePhysicalDefense++
                        15 -> cb.baseMagicDefense++
                        16 -> cb.baseSpeed++
                    }
                }
                m
            } else d
        }
    }

    // ── 胜利奖励：妖兽材料+灵石 ──────────────────────────────────────────

    private fun MutableGameState.collectBeastFightRewards(
        level: WorldLevel, result: BattleSystemResult
    ): List<BattleRewardItem> {
        val allRewards = mutableListOf<BattleRewardItem>()
        val beastConfig = GameConfig.Beast.getType(level.beastType ?: 0)
        val tier = GameConfig.Realm.getMaxRarity(level.realm)
        for (i in 0 until level.count) {
            repeat(rngManager.getRng(RngPartition.BATTLE).nextInt(3) + 1) {
                val mat = BeastMaterialDatabase
                    .getRandomMaterialByBeastType(beastConfig.name, tier)
                if (mat != null) {
                    val material = Material(
                        id = UUID.randomUUID().toString(),
                        name = mat.name, rarity = mat.rarity,
                        description = mat.description,
                        category = mat.materialCategory, quantity = 1
                    )
                    val addR = inventorySystem.addMaterial(material)
                    if (addR.isSuccess) {
                        allRewards.add(BattleRewardItem(
                            itemId = material.id,
                            name = material.name, quantity = 1,
                            rarity = material.rarity,
                            type = "material"
                        ))
                    }
                }
            }
        }

        val sr = result.rewards["spiritStones"] ?: 0
        if (sr > 0) {
            spiritStoneWallet.add(
                this, sr.toLong(),
                SpiritStoneGrade.LOW, SpiritStoneSource.Battle
            )
            allRewards.add(BattleRewardItem(
                name = "灵石", quantity = sr, rarity = 1,
                type = "spiritStones"
            ))
        }

        return allRewards
    }

    // ── 击败战利品 ─────────────────────────────────────────────────────────

    private fun MutableGameState.applyBeastDefeatLoot() {
        val loot = lootCalculator.computeLootPlan(gameData, this)
        lootCalculator.applyLoot(this, loot)
    }

    // ── 战斗日志+UI结果 ────────────────────────────────────────────────────

    private fun MutableGameState.buildBeastDefenseBattleLog(
        result: BattleSystemResult, level: WorldLevel,
        targetSect: WorldSect, survivorIds: Set<String>,
        allRewards: List<BattleRewardItem>
    ) {
        val teamMems = result.battle.team.map { m ->
            BattleLogMember(
                id = m.id, name = m.name, realm = m.realm,
                realmName = m.realmName, hp = m.hp,
                maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp,
                isAlive = !m.isDead, portraitRes = m.portraitRes
            )
        }
        val enems = result.battle.beasts.map { b ->
            BattleLogEnemy(
                name = b.name, realm = b.realm,
                realmName = b.realmName,
                portraitRes = b.portraitRes
            )
        }
        val rds = result.log.rounds.map { r ->
            BattleLogRound(
                roundNumber = r.roundNumber,
                actions = r.actions.map { a -> BattleLogAction(
                    type = a.type, attacker = a.attacker,
                    attackerType = a.attackerType,
                    target = a.target, damage = a.damage,
                    damageType = a.damageType,
                    isCrit = a.isCrit, isKill = a.isKill,
                    message = a.message
                )}
            )
        }

        battleLogs = (battleLogs + BattleLog(
            year = gameData.gameYear,
            month = gameData.gameMonth, type = BattleType.PVE,
            attackerName = level.beastName.ifEmpty { "妖兽" },
            defenderName = if (targetSect.isPlayerSect) "玩家宗门"
                else targetSect.name,
            result = if (result.victory) BattleResult.WIN
                else BattleResult.LOSE,
            teamMembers = teamMems, enemies = enems,
            rounds = rds,
            turns = result.turnCount,
            teamCasualties = teamMems.count {
                !survivorIds.contains(it.id)
            },
            beastsDefeated = if (result.victory) level.count
                else result.battle.beasts.count { it.isDead },
            details = if (result.victory)
                "成功抵御${level.beastName}袭击"
                else "被${level.beastName}击败，宗门受损"
        )).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)

        appendPendingBeastDefenseResult(
            result.victory, teamMems, allRewards
        )
    }

    private fun MutableGameState.appendPendingBeastDefenseResult(
        victory: Boolean, teamMems: List<BattleLogMember>,
        allRewards: List<BattleRewardItem>
    ) {
        // 妖兽防守结果始终显示，不受 patrolBattleResultPopup 设置影响
        val looted = if (victory) emptyList()
            else lootCalculator.computeLootPlan(gameData, this)
                .toRewardItems()
        gameData = gameData.copy(
            pendingPatrolBattleResults =
                gameData.pendingPatrolBattleResults +
                BattleResultUIData(
                    battleLogId = "", victory = victory,
                    teamMembers = teamMems,
                    rewards = allRewards,
                    lootedItems = looted,
                    isBeastDefense = true
                )
        )
    }

    // ── 最终写回 ───────────────────────────────────────────────────────────

    private fun MutableGameState.finalizeBeastDisciples(
        disciples: List<Disciple>
    ) {
        discipleTables.replaceAll(disciples)
        disciples.filter { !it.isAlive }.forEach {
            val idInt = it.id.toIntOrNull()
            if (idInt != null &&
                !discipleTables.deathYears.contains(idInt)
            ) {
                discipleTables.deathYears[idInt] =
                    gameData.gameYear
            }
        }
    }

    // ── 掠夺计算已迁移到 LootCalculator ─────────────────────────────────────

    // ── 探索队伍管理（委派到 ExplorationTeamManager） ──────────────────────

    /** 获取探索队伍列表 */
    fun getTeams(): StateFlow<List<ExplorationTeam>> =
        stateStore.teams

    /** 从探索队伍撤回弟子 */
    suspend fun recallDiscipleFromTeam(
        teamId: String, discipleId: String
    ): Boolean =
        explorationTeamManager.recallDiscipleFromTeam(
            teamId, discipleId
        )

    /** 完成探索（成功或失败） */
    suspend fun completeExploration(
        teamId: String, success: Boolean,
        survivorIds: List<String>
    ) = explorationTeamManager.completeExploration(
        teamId, success, survivorIds
    )

    // ── 死亡标记已迁移到 DiscipleDeathHandler ────────────────────────────────
    // ── 弟子状态更新已迁移到 DiscipleFacade ─────────────────────────────────────
}
