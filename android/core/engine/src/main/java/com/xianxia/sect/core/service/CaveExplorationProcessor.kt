package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.CancellationException
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.exploration.CaveExplorationSystem
import com.xianxia.sect.core.engine.domain.exploration.CaveRewardItem
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.util.AnalyticsTracker
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 洞府探索 completion loop 中 mutable 累加器的容器。
 * 因 completion loop 和 error handler 均需修改 finalCaves/finalAITeams 的引用，
 * 使用 data class + var 字段替代闭包捕获，使提取的 private fun 可修改调用方状态。
 */
private data class CaveCompletionState(
    var finalCaves: List<CultivatorCave>,
    var finalAITeams: List<AICaveTeam>,
    val finalExplorationTeams: MutableList<CaveExplorationTeam>,
    val teamsWithMissingCave: MutableList<CaveExplorationTeam>,
    val teamsWithError: MutableList<CaveExplorationTeam>
)

@Singleton
@GameService("CaveExplorationProcessor")
class CaveExplorationProcessor @Inject constructor(
    // D3（2026-08-05）：AI 攻防域（热控修炼/宗门等级/攻打玩家/AI-vs-AI/防守战）
    // 迁至 [AISectBattleProcessor]；thermalMonitor/attackWarningService/
    // sectWarehouseManager/cultivationService/rngManager/scopeProvider 随之移出
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val battleSystem: BattleSystem,
    private val eventProcessor: CultivationEventProcessor,
    private val analyticsTracker: AnalyticsTracker,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val deathHandler: DiscipleDeathHandler,
    private val aiSectBattleProcessor: AISectBattleProcessor
) {
    companion object {
        private const val TAG = "CaveExplorationProc"

        /** 探索战斗日志展示截断上限（与内存释放策略 [GameEngineCoordination] 的保留数独立） */
        private const val BATTLE_LOG_DISPLAY_LIMIT = 49
    }

    // ── 洞府探索 ──────────────────────────────────────────────────────

    fun processCaveLifecycle(year: Int, month: Int) {
        // Phase 1: 重置过期洞府的探索队伍（在 stateStore.update 外部进行）
        val initialExpiredCaveIds = resetExpiredCaveTeams(year, month)

        // Phase 2: 剩余逻辑在单个 stateStore.update 事务内完成
        stateStore.update {
            val caves = gameData.cultivatorCaves
            val explorationTeams = gameData.caveExplorationTeams
            val sects = gameData.worldMapSects
            val details = gameData.sectDetails

            val activeCaves = caves.filter { cave ->
                !cave.isExpired(year, month) && cave.status != CaveStatus.EXPLORED
            }

            var updatedSectsForAI = sects.toMutableList()
            val updatedSectDetails = details.toMutableMap()

            val teamsToComplete = explorationTeams.filter {
                it.status == CaveExplorationStatus.EXPLORING
            }

            val completionState = CaveCompletionState(
                finalCaves = activeCaves.toList(),
                finalAITeams = gameData.aiCaveTeams,
                finalExplorationTeams = explorationTeams.filter {
                    it.caveId !in initialExpiredCaveIds
                }.toMutableList(),
                teamsWithMissingCave = mutableListOf(),
                teamsWithError = mutableListOf()
            )
            teamsToComplete.forEach { processSingleTeamCompletion(it, completionState) }

            handleExplorationErrors(completionState)

            gameData = gameData.copy(
                cultivatorCaves = completionState.finalCaves,
                caveExplorationTeams = completionState.finalExplorationTeams,
                worldMapSects = updatedSectsForAI,
                sectDetails = updatedSectDetails
            )
        }
    }

    private fun executeCaveExploration(
        team: CaveExplorationTeam,
        cave: CultivatorCave,
        currentAITeams: List<AICaveTeam>
    ): Triple<List<CultivatorCave>, List<AICaveTeam>, Boolean> {
        val teamMembers = assembleTeamMembers(team)
        if (teamMembers.isEmpty()) {
            return handleEmptyTeam(cave, currentAITeams)
        }

        val data = stateStore.gameData.value
        val equipmentMap = stateStore.equipmentInstances.value.associateBy { it.id }
        val manualMap = stateStore.manualInstances.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        // 移除 AI 队伍战斗 — 玩家团队直接对战守护兽
        val battleResult = executeBattleForTeam(
            teamMembers, equipmentMap, manualMap, allProficiencies,
            cave
        )

        val deadDisciples = processBattleCasualties(team, battleResult)
        deadDisciples.forEach { eventProcessor.handleDiscipleDeath(
            it, isOutsideSect = true
        ) }

        if (!battleResult.victory) {
            val updatedAITeams = currentAITeams.filter { it.caveId != cave.id }
            return Triple(stateStore.gameData.value.cultivatorCaves, updatedAITeams, true)
        }

        val victorMembers = battleResult.log.teamMembers.filter { it.isAlive }
        val survivorIds = victorMembers.map { it.id }.toSet()
        awardVictorySoulPower(survivorIds)

        val battleRewardItems = grantBattleRewards(cave)
        val battleLog = buildAndStoreBattleLog(
            data, team, cave, battleResult, battleRewardItems
        )
        stateStore.setPendingBattleResult(BattleResultUIData(
            battleLogId = battleLog.id,
            victory = battleResult.victory,
            teamMembers = battleLog.teamMembers,
            rewards = battleRewardItems
        ))
        trackBattleAnalytics(battleResult, cave)

        return cleanupAfterCaveExploration(cave, currentAITeams)
    }

    /** 执行洞府战斗：玩家团队 vs 守护妖兽 */
    private fun executeBattleForTeam(
        teamMembers: List<Disciple>,
        equipmentMap: Map<String, EquipmentInstance>,
        manualMap: Map<String, ManualInstance>,
        allProficiencies: Map<String, Map<String, ManualProficiencyData>>,
        cave: CultivatorCave
    ): BattleSystemResult {
        return battleSystem.executeBattle(
            CaveExplorationSystem.createGuardianBattle(
                playerDisciples = teamMembers,
                playerEquipmentMap = equipmentMap,
                playerManualMap = manualMap,
                playerManualProficiencies = allProficiencies,
                cave = cave
            )
        )
    }

    fun findNearbySects(cave: CultivatorCave, range: Float): List<WorldSect> {
        val data = stateStore.gameData.value
        return data.worldMapSects.filter { sect ->
            !sect.isPlayerSect &&
            kotlin.math.sqrt(
                (cave.x - sect.x) * (cave.x - sect.x) +
                (cave.y - sect.y) * (cave.y - sect.y)
            ) <= range
        }
    }

    fun resetCaveExplorationTeamMembersStatus(team: CaveExplorationTeam) {
        val memberIds = team.memberIds.toList()
        stateStore.update {
            val idsToReset = memberIds.filter { memberId ->
                val d = discipleTables.assembleAll().find { it.id == memberId }
                d != null && d.status == DiscipleStatus.IN_TEAM
            }
            if (idsToReset.isNotEmpty()) {
                val newList = discipleTables.assembleAll().map {
                    if (it.id in idsToReset) it.copy(status = DiscipleStatus.IDLE) else it
                }
                discipleTables.replaceAll(newList)
            }
        }
    }

    // ── AI 宗门 ──────────────────────────────────────────────────────

    /** AI 宗门攻防月度结算（D3 委托至 [AISectBattleProcessor]；签名不变，调用方零改动） */
    fun processAISectOperations(year: Int, month: Int) =
        aiSectBattleProcessor.processAISectOperations(year, month)

    /** AI 宗门攻防月度结算（D3 委托至 [AISectBattleProcessor]；签名不变，调用方零改动） */
    fun processAISectOperations(year: Int, month: Int, state: MutableGameState) =
        aiSectBattleProcessor.processAISectOperations(year, month, state)

    /**
     * AI 弟子热控分批：根据手机发热程度决定结算间隔。
     * - 常温 → 每月结算
     * - 发热(shouldReduceWorkload) → 每 6 月结算一次
     * - 发热严重(shouldEmergencySave) → 每 12 月结算一次
     */
    fun processSectDisciplesYearlyRecruitment(year: Int, state: MutableGameState) {
        val data = state.gameData
        var updatedAiDisciples = data.aiSectDisciples.toMutableMap()
        var updatedRecruitList = data.recruitList

        for ((sectId, disciples) in data.aiSectDisciples) {
            val sect = data.worldMapSects.find { it.id == sectId } ?: continue
            if (sect.isPlayerSect) continue

            val newRecruits = AISectDiscipleManager.generateYearlyRecruits(
                sect.name, disciples, sect.level
            )
            when {
                sect.isPlayerOccupied -> {
                    updatedRecruitList = updatedRecruitList + newRecruits
                }
                sect.occupierSectId.isNotEmpty() -> {
                    val occupierDisciples = updatedAiDisciples[sect.occupierSectId] ?: emptyList()
                    updatedAiDisciples[sect.occupierSectId] =
                        AISectDiscipleManager.truncateToLimit(occupierDisciples + newRecruits)
                }
                else -> {
                    updatedAiDisciples[sectId] =
                        AISectDiscipleManager.truncateToLimit(disciples + newRecruits)
                }
            }
        }
        // 直接基于事务 buffer 写回：年变单事务内前序事件（如 refreshRecruitList）
        // 对 buffer 的修改必须保留，禁止读已提交快照覆盖（招募列表不刷新 MNG 修复）。
        state.gameData = state.gameData.copy(
            aiSectDisciples = updatedAiDisciples,
            recruitList = updatedRecruitList
        )
        // 被占领AI宗门产生新弟子后立即执行自动招募检查 + 重置惰性
        RecruitService.RecruitLazyState.autoRecruitIdle = false
        RecruitService.RecruitLazyState.autoRejectIdle = false
        RecruitService.processAutoRecruit(state)
    }

    fun processSectDisciplesAging(year: Int, state: MutableGameState) {
        val data = state.gameData
        val updatedAiDisciples = data.aiSectDisciples.mapValues { (sectId, disciples) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) return@mapValues disciples
            AISectDiscipleManager.processAging(disciples)
        }
        // 年度老化仅修改年龄，不改变境界，无需同步宗门等级。
        // 基于事务 buffer 写回，保留同事务前序事件对 aiSectDisciples 的修改
        // （禁止读已提交快照覆盖，招募列表不刷新 MNG 修复）。
        state.gameData = state.gameData.copy(aiSectDisciples = updatedAiDisciples)
    }

    // ── processCaveLifecycle 辅助方法 ──────────────────────────────────

    private fun resetExpiredCaveTeams(year: Int, month: Int): Set<String> {
        val initialExpiredCaveIds = stateStore.gameData.value.cultivatorCaves.filter { cave ->
            cave.isExpired(year, month) || cave.status == CaveStatus.EXPLORED
        }.map { it.id }.toSet()
        initialExpiredCaveIds.forEach { caveId ->
            val affectedTeams = stateStore.gameData.value.caveExplorationTeams.filter {
                it.caveId == caveId && it.status == CaveExplorationStatus.TRAVELING
            }
            affectedTeams.forEach { team ->
                resetCaveExplorationTeamMembersStatus(team)
            }
        }
        return initialExpiredCaveIds
    }

    private fun processSingleTeamCompletion(
        team: CaveExplorationTeam,
        state: CaveCompletionState
    ) {
        val cave = state.finalCaves.find { it.id == team.caveId }
        if (cave == null) {
            state.teamsWithMissingCave.add(team)
            return
        }
        try {
            val result = executeCaveExploration(team, cave, state.finalAITeams)
            state.finalCaves = result.first
            state.finalAITeams = result.second
            if (result.third) {
                state.finalExplorationTeams.removeAll { it.id == team.id }
            }
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            DomainLog.e(TAG, "Error processing cave exploration for team ${team.id}", e)
            state.teamsWithError.add(team)
        }
    }

    private fun handleExplorationErrors(state: CaveCompletionState) {
        state.teamsWithMissingCave.forEach { team ->
            resetCaveExplorationTeamMembersStatus(team)
            state.finalExplorationTeams.removeAll { it.id == team.id }
        }
        state.teamsWithError.forEach { team ->
            resetCaveExplorationTeamMembersStatus(team)
            state.finalCaves = state.finalCaves.map { cave ->
                if (cave.id == team.caveId && cave.status == CaveStatus.EXPLORING) {
                    cave.copy(status = CaveStatus.AVAILABLE)
                } else {
                    cave
                }
            }
            state.finalExplorationTeams.removeAll { it.id == team.id }
        }
    }

    // ── executeCaveExploration 辅助方法 ─────────────────────────────────

    private fun assembleTeamMembers(team: CaveExplorationTeam): List<Disciple> {
        return team.memberIds.mapNotNull { id ->
            stateStore.disciples.value.find { it.id == id }
        }.filter { it.isAlive }
    }

    private fun handleEmptyTeam(
        cave: CultivatorCave,
        currentAITeams: List<AICaveTeam>
    ): Triple<List<CultivatorCave>, List<AICaveTeam>, Boolean> {
        return Triple(
            stateStore.gameData.value.cultivatorCaves,
            currentAITeams.filter { it.caveId != cave.id },
            true
        )
    }

    private fun processBattleCasualties(
        team: CaveExplorationTeam,
        battleResult: com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
    ): List<Disciple> {
        val survivorIds = battleResult.log.teamMembers.filter { it.isAlive }.map { it.id }.toSet()
        val survivorHpMap = battleResult.log.teamMembers.filter { it.isAlive }
            .associate { it.id to it.hp }
        val survivorMpMap = battleResult.log.teamMembers.filter { it.isAlive }
            .associate { it.id to it.mp }
        val deadDisciples = mutableListOf<Disciple>()
        stateStore.update {
            val newList = discipleTables.assembleAll().map { disciple ->
                if (disciple.id in team.memberIds) {
                    if (disciple.id in survivorIds) {
                        val hp = survivorHpMap[disciple.id] ?: disciple.combat.currentHp
                        val mp = survivorMpMap[disciple.id] ?: disciple.combat.currentMp
                        disciple.copy(
                            status = DiscipleStatus.IDLE,
                            combat = disciple.combat.copy(currentHp = hp, currentMp = mp)
                        )
                    } else {
                        deadDisciples.add(disciple)
                        disciple.copy(isAlive = false, status = DiscipleStatus.DEAD)
                    }
                } else disciple
            }
            discipleTables.replaceAll(newList)
            // 死亡年份由 DiscipleDeathHandler 统一补写（replaceAll 已清空列写入）
            deathHandler.backfillDeathYears(
                discipleTables, newList, stateStore.gameData.value.gameYear
            )
        }
        return deadDisciples
    }

    private fun awardVictorySoulPower(survivorIds: Set<String>) {
        stateStore.update {
            val newList = discipleTables.assembleAll().map { disciple ->
                if (disciple.id in survivorIds && disciple.isAlive) {
                    disciple.copy(soulPower = disciple.soulPower + 1)
                } else {
                    disciple
                }
            }
            discipleTables.replaceAll(newList)
        }
    }

    private fun grantBattleRewards(cave: CultivatorCave): List<BattleRewardItem> {
        val rewards = CaveExplorationSystem.generateVictoryRewards(cave)
        val battleRewardItems = mutableListOf<BattleRewardItem>()
        rewards.items.forEach { reward ->
            when (reward.type) {
                "spiritStones" -> grantSpiritStoneReward(reward, battleRewardItems)
                "equipment" -> grantEquipmentReward(reward, battleRewardItems)
                "manual" -> grantManualReward(reward, battleRewardItems)
                "pill" -> grantPillReward(reward, battleRewardItems)
            }
        }
        return battleRewardItems
    }

    private fun grantSpiritStoneReward(
        reward: CaveRewardItem,
        battleRewardItems: MutableList<BattleRewardItem>
    ) {
        stateStore.update { spiritStoneWallet.add(this,
            amount = reward.quantity.toLong(),
            grade = SpiritStoneGrade.LOW,
            source = SpiritStoneSource.Cave
        ) }
        battleRewardItems.add(BattleRewardItem(
            itemId = reward.itemId,
            name = reward.name,
            quantity = reward.quantity,
            rarity = reward.rarity,
            type = reward.type
        ))
    }

    private fun grantEquipmentReward(
        reward: CaveRewardItem,
        battleRewardItems: MutableList<BattleRewardItem>
    ) {
        val template = EquipmentDatabase.getById(reward.itemId)
        if (template != null) {
            val equipment = EquipmentDatabase.createFromTemplate(template).copy(
                rarity = reward.rarity,
                quantity = reward.quantity
            )
            val result = inventorySystem.withTrackingSource("cave") { inventorySystem.addEquipmentStack(equipment) }
            when (val r = result) {
                is DomainResult.Success -> battleRewardItems.add(BattleRewardItem(
                    itemId = reward.itemId,
                    name = reward.name,
                    quantity = reward.quantity,
                    rarity = reward.rarity,
                    type = reward.type
                ))
                is DomainResult.Partial -> {
                    battleRewardItems.add(BattleRewardItem(
                        itemId = reward.itemId,
                        name = reward.name,
                        quantity = reward.quantity,
                        rarity = reward.rarity,
                        type = reward.type
                    ))
                    DomainLog.w(TAG, "${reward.name} 溢出 ${r.overflow} 个")
                }
                is DomainResult.Failure -> DomainLog.w(TAG, "装备添加失败: ${r.error}")
            }
        }
    }

    private fun grantManualReward(
        reward: CaveRewardItem,
        battleRewardItems: MutableList<BattleRewardItem>
    ) {
        val template = ManualDatabase.getById(reward.itemId)
        if (template != null) {
            val manual = ManualDatabase.createFromTemplate(template).copy(
                rarity = reward.rarity,
                quantity = reward.quantity
            )
            // 修复 P10：isSuccess 对 Partial 误判为成功——改用穷尽 when；
            // Partial 时溢出已转邮件（自动类路径），物品总量不丢失，卡片照常展示
            val result = inventorySystem.withTrackingSource("cave") {
                inventorySystem.addManualStack(manual)
            }
            when (result) {
                is DomainResult.Success -> {
                    battleRewardItems.add(BattleRewardItem(
                        itemId = reward.itemId,
                        name = reward.name,
                        quantity = reward.quantity,
                        rarity = reward.rarity,
                        type = reward.type
                    ))
                }
                is DomainResult.Partial -> {
                    DomainLog.w(TAG, "洞府功法 ${manual.name} 溢出 ${result.overflow} 个（已转邮件）")
                    battleRewardItems.add(BattleRewardItem(
                        itemId = reward.itemId,
                        name = reward.name,
                        quantity = reward.quantity,
                        rarity = reward.rarity,
                        type = reward.type
                    ))
                }
                is DomainResult.Failure -> {
                    DomainLog.w(TAG, "洞府功法 ${manual.name} 发放失败: ${result.error}")
                }
            }
        }
    }

    private fun grantPillReward(
        reward: CaveRewardItem,
        battleRewardItems: MutableList<BattleRewardItem>
    ) {
        val template = PillRecipeDatabase.getRecipeById(reward.itemId)
        if (template != null) {
            val pill = Pill(
                id = java.util.UUID.randomUUID().toString(),
                name = template.name,
                rarity = template.rarity,
                quantity = reward.quantity,
                description = template.description,
                category = template.category,
                effects = PillEffect(
                    breakthroughChance = template.breakthroughChance,
                    targetRealm = template.targetRealm,
                    cultivationSpeedPercent = template.cultivationSpeedPercent,
                    duration = template.duration,
                    cultivationAdd = template.cultivationAdd,
                    skillExpAdd = template.skillExpAdd,
                    nurtureAdd = template.nurtureAdd,
                    extendLife = template.extendLife,
                    physicalAttackAdd = template.physicalAttackAdd,
                    magicAttackAdd = template.magicAttackAdd,
                    physicalDefenseAdd = template.physicalDefenseAdd,
                    magicDefenseAdd = template.magicDefenseAdd,
                    hpAdd = template.hpAdd,
                    mpAdd = template.mpAdd,
                    speedAdd = template.speedAdd,
                    critRateAdd = template.critRateAdd,
                    critEffectAdd = template.critEffectAdd,
                    intelligenceAdd = template.intelligenceAdd,
                    charmAdd = template.charmAdd,
                    loyaltyAdd = template.loyaltyAdd,
                    comprehensionAdd = template.comprehensionAdd,
                    artifactRefiningAdd = template.artifactRefiningAdd,
                    pillRefiningAdd = template.pillRefiningAdd,
                    spiritPlantingAdd = template.spiritPlantingAdd,
                    teachingAdd = template.teachingAdd,
                    moralityAdd = template.moralityAdd
                ),
                minRealm = GameConfig.Realm.getMinRealmForRarity(template.rarity)
            )
            val result = inventorySystem.withTrackingSource("cave") { inventorySystem.addPill(pill) }
            when (val r = result) {
                is DomainResult.Success -> battleRewardItems.add(BattleRewardItem(
                    itemId = reward.itemId,
                    name = reward.name,
                    quantity = reward.quantity,
                    rarity = reward.rarity,
                    type = reward.type
                ))
                is DomainResult.Partial -> {
                    battleRewardItems.add(BattleRewardItem(
                        itemId = reward.itemId,
                        name = reward.name,
                        quantity = reward.quantity,
                        rarity = reward.rarity,
                        type = reward.type
                    ))
                    DomainLog.w(TAG, "${reward.name} 溢出 ${r.overflow} 个")
                }
                is DomainResult.Failure -> DomainLog.w(TAG, "丹药添加失败: ${r.error}")
            }
        }
    }

    private fun buildAndStoreBattleLog(
        data: GameData,
        team: CaveExplorationTeam,
        cave: CultivatorCave,
        battleResult: com.xianxia.sect.core.engine.domain.battle.BattleSystemResult,
        battleRewardItems: List<BattleRewardItem>
    ): BattleLog {
        val battleLog = BattleLog(
            timestamp = System.currentTimeMillis(),
            year = data.gameYear,
            month = data.gameMonth,
            type = BattleType.CAVE_EXPLORATION,
            attackerName = team.caveName,
            defenderName = cave.name,
            result = BattleResult.WIN,
            details = "洞府探索",
            dungeonName = cave.name,
            teamId = team.id,
            teamMembers = battleResult.log.teamMembers.map { member ->
                BattleLogMember(
                    id = member.id, name = member.name,
                    realm = member.realm, realmName = member.realmName,
                    hp = member.hp, maxHp = member.maxHp,
                    mp = member.mp, maxMp = member.maxMp,
                    isAlive = member.isAlive, portraitRes = member.portraitRes
                )
            },
            enemies = battleResult.log.enemies.map { enemy ->
                BattleLogEnemy(
                    id = enemy.id, name = "守护兽",
                    realm = enemy.realm, realmName = enemy.realmName,
                    realmLayer = enemy.realmLayer,
                    hp = enemy.hp, maxHp = enemy.maxHp,
                    isAlive = enemy.isAlive, portraitRes = enemy.portraitRes
                )
            },
            rounds = battleResult.log.rounds.map { round ->
                BattleLogRound(
                    roundNumber = round.roundNumber,
                    actions = round.actions.map { action ->
                        BattleLogAction(
                            type = action.type, attacker = action.attacker,
                            attackerType = action.attackerType, target = action.target,
                            damage = action.damage, damageType = action.damageType,
                            isCrit = action.isCrit, isKill = action.isKill,
                            message = action.message, skillName = action.skillName
                        )
                    }
                )
            },
            turns = battleResult.turnCount,
            battleResult = BattleLogResult(
                winner = if (battleResult.victory) "team" else "beasts",
                isPlayerWin = battleResult.victory,
                turns = battleResult.turnCount,
                rounds = battleResult.log.rounds.size,
                teamCasualties = battleResult.log.teamMembers.count { !it.isAlive },
                beastsDefeated = battleResult.log.enemies.count { !it.isAlive }
            )
        )
        stateStore.update { battleLogs = listOf(battleLog) + battleLogs.take(BATTLE_LOG_DISPLAY_LIMIT) }
        return battleLog
    }

    private fun trackBattleAnalytics(
        battleResult: com.xianxia.sect.core.engine.domain.battle.BattleSystemResult,
        cave: CultivatorCave
    ) {
        analyticsTracker.trackEvent(
            "battle_end",
            mapOf(
                "outcome" to if (battleResult.victory) "win" else "lose",
                "enemy_type" to cave.name,
                "turns" to battleResult.turnCount,
                "team_size" to battleResult.log.teamMembers.size
            )
        )
    }

    private fun cleanupAfterCaveExploration(
        cave: CultivatorCave,
        currentAITeams: List<AICaveTeam>
    ): Triple<List<CultivatorCave>, List<AICaveTeam>, Boolean> {
        val updatedCaves = stateStore.gameData.value.cultivatorCaves.map { c ->
            if (c.id == cave.id) c.copy(status = CaveStatus.EXPLORED) else c
        }
        val updatedAITeams = currentAITeams.filter { it.caveId != cave.id }
        return Triple(updatedCaves, updatedAITeams, true)
    }

    // ── executePlayerDefenseBattle 辅助方法 ─────────────────────────────

}
