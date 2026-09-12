package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.CancellationException
import com.xianxia.sect.core.model.AICaveTeam
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogResult
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.CaveExplorationStatus
import com.xianxia.sect.core.model.CaveExplorationTeam
import com.xianxia.sect.core.model.CaveStatus
import com.xianxia.sect.core.model.CultivatorCave
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.exploration.CaveExplorationSystem
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.util.AnalyticsEvents
import com.xianxia.sect.core.util.AnalyticsTracker
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
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
    internal val stateStore: GameStateStore,
    internal val inventorySystem: InventorySystem,
    private val battleSystem: BattleSystem,
    private val eventProcessor: CultivationEventProcessor,
    private val analyticsTracker: AnalyticsTracker,
    internal val spiritStoneWallet: SpiritStoneWallet,
    private val deathHandler: DiscipleDeathHandler,
    private val aiSectBattleProcessor: AISectBattleProcessor
) {
    companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
        internal const val TAG = "CaveExplorationProc"

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
            cave, data.bloodRefinementPctTotals
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
        cave: CultivatorCave,
        bloodRefinementMap: Map<String, BloodRefinementPctTotal> = emptyMap()
    ): BattleSystemResult {
        return battleSystem.executeBattle(
            CaveExplorationSystem.createGuardianBattle(
                playerDisciples = teamMembers,
                playerEquipmentMap = equipmentMap,
                playerManualMap = manualMap,
                playerManualProficiencies = allProficiencies,
                cave = cave,
                bloodRefinementMap = bloodRefinementMap
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

    /**
     * AI 宗门月度运营（3 参版）——AUTHORITATIVE 回退链子事件 6 的 Kotlin 面
     * （仓库清场 + 热控分批修炼 + 宗门等级同步，纯运营无战斗）。
     * 战斗编排面已入 C++ 月结子事件 6b/6c（P2-18）；2 参版委托随
     * 征伐环下沉删除（全仓唯一调用方为已删的休眠链）。
     */
    fun processAISectOperations(year: Int, month: Int, state: MutableGameState) =
        aiSectBattleProcessor.processAISectOperations(year, month, state)

    /** 当前热档批量上界（AUTHORITATIVE 月结推送 C++ 用） */
    @Suppress("UnusedParameter") // year: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
    internal fun currentAiThermalBatchSize(): Int =
        aiSectBattleProcessor.currentAiThermalBatchSize()

    /**
     * AI 宗门弟子周期性招募结算：为每个非玩家宗门生成一批新弟子并按占领路由分发。
     * 在年变单事务内评估，实际由 runSectRecruitmentIfDue 差值判据每 3 年触发一次
     * （非招募年不调用本函数）；批次数量见 AISectDiscipleManager.generateYearlyRecruits。
     */
    @Suppress("UnusedParameter") // year: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
    fun processSectDisciplesYearlyRecruitment(year: Int, state: MutableGameState) {
        val data = state.gameData
        var updatedAiDisciples = data.aiSectDisciples.toMutableMap()
        var updatedRecruitList = data.recruitList

        for ((sectId, disciples) in data.aiSectDisciples) {
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) continue

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
        // 被占领AI宗门产生新弟子后立即执行自动招募检查 + 重置惰性（同步 C++ 惰性门）
        RecruitService.resetAutoRecruitIdle()
        RecruitService.RecruitLazyState.autoRejectIdle = false
        RecruitService.processAutoRecruit(state)
    }

    @Suppress("UnusedParameter") // year: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
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

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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

    /**
     * Process battle casualties - update disciples status and handle deaths.
     *
     * 所有状态写入（弟子标记、装备、槽位、HP/MP）在单次 [stateStore.update] 事务中完成，
     * 避免中途失败导致数据不一致。
     */
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

    @Suppress("UnusedParameter") // battleRewardItems: 语义形参：签名表达 API 决策域（调用点可读性与协议完整性优先），当前策略不消费
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
            AnalyticsEvents.BATTLE_END,
            mapOf(
                AnalyticsEvents.PROP_OUTCOME to if (battleResult.victory) "win" else "lose",
                AnalyticsEvents.PROP_ENEMY_TYPE to cave.name,
                AnalyticsEvents.PROP_TURNS to battleResult.turnCount,
                AnalyticsEvents.PROP_TEAM_SIZE to battleResult.log.teamMembers.size
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
