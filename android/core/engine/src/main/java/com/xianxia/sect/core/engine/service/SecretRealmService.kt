package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.battle.BattleLogData
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmBattleHelper
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmChoiceResult
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmBattleOutcome
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmBeastChoiceResolution
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmEndReason
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmEventGenerator
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.core.model.SecretRealmEventParams
import com.xianxia.sect.core.model.SecretRealmEventRecord
import com.xianxia.sect.core.model.SecretRealmEventType
import com.xianxia.sect.core.model.SecretRealmExplorationSession
import com.xianxia.sect.core.model.SecretRealmMemberState
import com.xianxia.sect.core.model.SecretRealmState
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.recordGameEvent
import com.xianxia.sect.core.state.recordPlayerBattle
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远古秘境玩法主服务——刷新/出发/选择/结束/结算。
 *
 * 所有方法均在 [MutableGameState] 事务内执行（由 GameEngine 扩展入口调用）。
 */
@Singleton
@GameService("SecretRealmService")
// 领域聚合服务：刷新/出发/选择/战斗/结算操作较多，拆分为独立服务反而割裂事务内状态流转
@Suppress("TooManyFunctions")
class SecretRealmService @Inject constructor(
    private val rngManager: GameRngManager,
    private val battleSystem: BattleSystem,
    private val inventorySystem: InventorySystem,
    private val spiritStoneWallet: SpiritStoneWallet
) {

    // ── 年变刷新 ──────────────────────────────────────────────────────

    /**
     * 年变刷新：0.8% 概率 + 50 年冷却差值判据（对照 MerchantAndRecruitService）。
     */
    @Suppress("ReturnCount")
    fun processYearlySpawn(year: Int, state: MutableGameState) {
        val data = state.gameData
        if (data.secretRealmState.exists) return
        // 篡改档防御：负冷却年视为从未出现（clamp 到 0），避免绕过冷却判据
        val cooldown = data.secretRealmCooldownYear.coerceAtLeast(0)
        if (cooldown > 0 && year - cooldown < GameConfig.SecretRealm.COOLDOWN_YEARS) return
        val rng = rngManager.getRng(RngPartition.SECRET_REALM)
        if (rng.nextDouble() >= GameConfig.SecretRealm.SPAWN_PROBABILITY_PER_YEAR) return

        val (x, y) = findFreePosition(rng, data)
        val realm = SecretRealmState(
            id = UUID.randomUUID().toString(),
            x = x.toFloat(),
            y = y.toFloat(),
            spawnYear = year,
            spawnMonth = data.gameMonth,
            spriteIndex = rng.nextInt(GameConfig.SecretRealm.SPRITE_VARIANT_COUNT)
        )
        state.gameData = data.copy(secretRealmState = realm)
        state.recordGameEvent(
            GameEventCategory.SECT,
            GameEventType.SECRET_REALM,
            "远古秘境现世！传说中上古大能陨落之地，藏有无数机缘与凶险"
        )
    }

    /**
     * 在世界地图范围内找空闲位置（避开所有宗门）。
     */
    private fun findFreePosition(rng: DeterministicRng, data: GameData): Pair<Int, Int> {
        val border = GameConfig.WorldMap.BORDER_PADDING
        val minDist = GameConfig.WorldMap.SECT_RADIUS +
            GameConfig.SecretRealm.SECT_CLEARANCE
        repeat(GameConfig.SecretRealm.POSITION_ATTEMPTS) {
            val x = border + rng.nextInt(GameConfig.WorldMap.MAP_WIDTH - border * 2)
            val y = border + rng.nextInt(GameConfig.WorldMap.MAP_HEIGHT - border * 2)
            val nearSect = data.worldMapSects.any { sect ->
                val dx = sect.x - x
                val dy = sect.y - y
                dx * dx + dy * dy < minDist * minDist
            }
            if (!nearSect) return x to y
        }
        // 兜底：取与所有宗门最远点
        var best = border to border
        var bestDist = -1f
        for (x in border until GameConfig.WorldMap.MAP_WIDTH - border step
            GameConfig.SecretRealm.FALLBACK_SCAN_STEP
        ) {
            for (y in border until GameConfig.WorldMap.MAP_HEIGHT - border step
                GameConfig.SecretRealm.FALLBACK_SCAN_STEP
            ) {
                val dist = data.worldMapSects.minOfOrNull { sect ->
                    val dx = sect.x - x
                    val dy = sect.y - y
                    dx * dx + dy * dy
                } ?: 0f
                if (dist > bestDist) {
                    bestDist = dist
                    best = x to y
                }
            }
        }
        return best
    }

    // ── 出发 ──────────────────────────────────────────────────────────

    /**
     * 出发探索：校验队伍（满 4 人、存活、空闲）并写入会话 + 初始妖兽事件。
     */
    @Suppress("ReturnCount")
    fun startSession(memberIds: List<String>, state: MutableGameState): DomainResult<Unit> {
        val data = state.gameData
        if (!data.secretRealmState.exists) {
            return DomainResult.Failure(AppError.Domain.GameState.NotFound("远古秘境已消失"))
        }
        if (data.secretRealmSession.isActive) {
            return DomainResult.Failure(AppError.Domain.GameState.InvalidState("探索队伍已在秘境中"))
        }
        if (memberIds.size != GameConfig.SecretRealm.TEAM_SIZE ||
            memberIds.toSet().size != GameConfig.SecretRealm.TEAM_SIZE
        ) {
            return DomainResult.Failure(
                AppError.Domain.Validation.InvalidInput("需要 4 名不同的弟子组成探索队伍")
            )
        }
        val allDisciples = state.discipleTables.assembleAll()
        val selected = memberIds.mapNotNull { id -> allDisciples.find { it.id == id } }
        if (selected.size != GameConfig.SecretRealm.TEAM_SIZE) {
            return DomainResult.Failure(AppError.Domain.GameState.NotFound("弟子不存在"))
        }
        val invalid = selected.firstOrNull { !it.isAlive || it.status != DiscipleStatus.IDLE }
        if (invalid != null) {
            return DomainResult.Failure(
                AppError.Domain.Validation.InvalidInput("弟子「${invalid.name}」当前无法参战")
            )
        }

        val rng = rngManager.getRng(RngPartition.SECRET_REALM)
        val playerAvgRealm = selected.map { it.realm }.average().toInt()
        val event = SecretRealmEventGenerator.generateBeastEvent(rng, playerAvgRealm)

        val session = SecretRealmExplorationSession(
            secretRealmId = data.secretRealmState.id,
            members = selected.map { d ->
                SecretRealmMemberState(
                    discipleId = d.id,
                    name = d.name,
                    portraitRes = d.portraitRes,
                    realm = d.realm,
                    realmName = d.realmName,
                    // 初始参考值（未战斗，基础口径）；战斗后由写回维护为战斗口径
                    maxHp = d.maxHp
                )
            },
            stamina = GameConfig.SecretRealm.STAMINA_MAX,
            currentEvent = event,
            startYear = data.gameYear,
            startMonth = data.gameMonth
        )
        state.gameData = data.copy(secretRealmSession = session)
        return DomainResult.Success(Unit)
    }

    // ── 选择选项 ──────────────────────────────────────────────────────

    /**
     * 玩家选择事件选项：扣体力 → 结算效果（战斗/远离/方向）→ 生成下一事件。
     * 体力耗尽或队伍全灭时自动结束探索。
     */
    @Suppress("ReturnCount")
    fun chooseOption(optionIndex: Int, state: MutableGameState): SecretRealmChoiceResult {
        val data = state.gameData
        val session = data.secretRealmSession
        val event = session.currentEvent
        val error = validateChoice(session, event, optionIndex)
        if (error != null) return error
        val activeEvent = event
            ?: return SecretRealmChoiceResult.Error(message = "当前无进行中的事件")

        val rng = rngManager.getRng(RngPartition.SECRET_REALM)
        // 篡改档防御：体力 clamp 到正常范围（对抗性审查 B11/D3；Long 运算防 MIN_VALUE 回绕）
        val newStamina = (session.stamina.toLong() - GameConfig.SecretRealm.STAMINA_COST_PER_CHOICE)
            .coerceIn(0, GameConfig.SecretRealm.STAMINA_MAX.toLong())
            .toInt()
        val markedEvent = activeEvent.copy(chosenOptionIndex = optionIndex)

        val eventType = runCatching { SecretRealmEventType.valueOf(activeEvent.eventType) }
            .getOrDefault(SecretRealmEventType.BRIDGE)
        // 篡改档非法 eventType 回退 BRIDGE 分支（getOrDefault 兜底）
        val resolution = when (eventType) {
            SecretRealmEventType.BEAST_ENCOUNTER ->
                resolveBeastEncounter(optionIndex, state, session, activeEvent, rng)
            SecretRealmEventType.REST_AREA ->
                resolveRestArea(optionIndex, state, session)
            SecretRealmEventType.BRIDGE ->
                resolveBridgeChoice(optionIndex, session, rng)
        }

        val allDead = resolution.members.isNotEmpty() && resolution.members.all { it.isDead }
        val sessionEnded = allDead || newStamina <= 0

        val updatedSession = session.copy(
            stamina = newStamina,
            members = resolution.members,
            backpack = resolution.backpack,
            currentEvent = resolution.nextEvent,
            eventHistory = session.eventHistory + markedEvent.copy(
                resultText = resolution.resultText,
                params = resolution.params
            ),
            resultMessage = resolution.resultText
        )
        state.gameData = data.copy(secretRealmSession = updatedSession)

        if (sessionEnded) {
            endSession(state, if (allDead) SecretRealmEndReason.WIPEOUT else SecretRealmEndReason.EXHAUSTED)
            return SecretRealmChoiceResult.Success(
                message = if (allDead) "队伍全军覆没！" else "体力耗尽，被传送出秘境",
                sessionEnded = true,
                enteredCombat = resolution.enteredCombat,
                combatLog = resolution.combatLog,
                victory = resolution.victory,
                deadIds = resolution.deadIds,
                releasedMemberIds = session.members.map { it.discipleId }.toSet(),
                ambushSucceeded = resolution.params.ambushSucceeded
            )
        }

        return SecretRealmChoiceResult.Success(
            message = resolution.resultText,
            enteredCombat = resolution.enteredCombat,
            combatLog = resolution.combatLog,
            victory = resolution.victory,
            deadIds = resolution.deadIds,
            ambushSucceeded = resolution.params.ambushSucceeded
        )
    }

    /** 选择前置校验：返回错误结果或 null（通过） */
    @Suppress("ReturnCount")
    private fun validateChoice(
        session: SecretRealmExplorationSession,
        event: SecretRealmEventRecord?,
        optionIndex: Int
    ): SecretRealmChoiceResult? {
        if (!session.isActive) {
            return SecretRealmChoiceResult.Error(message = "探索会话不存在")
        }
        if (event == null) {
            return SecretRealmChoiceResult.Error(message = "当前无进行中的事件")
        }
        if (event.chosenOptionIndex != -1) {
            return SecretRealmChoiceResult.Error(message = "事件已处理，请勿重复选择")
        }
        if (optionIndex !in event.options.indices) {
            return SecretRealmChoiceResult.Error(message = "无效的选项")
        }
        return null
    }

    /** 妖兽事件分支：远离/战斗/偷袭 + 分支结算 */
    private fun resolveBeastEncounter(
        optionIndex: Int,
        state: MutableGameState,
        session: SecretRealmExplorationSession,
        event: SecretRealmEventRecord,
        rng: DeterministicRng
    ): SecretRealmBeastChoiceResolution {
        return when (optionIndex) {
            // ① 远离妖兽：30% 被察觉 → 战斗；否则成功远离
            0 -> {
                val detected = rng.nextDouble() < GameConfig.SecretRealm.FLEE_DETECT_CHANCE
                if (detected) {
                    toResolution(runBeastBattle(state, session, event.params, rng))
                } else {
                    bridgeResolution("你方悄然绕行，成功避开了妖兽的注意", session)
                }
            }
            // ② 发起战斗
            1 -> toResolution(runBeastBattle(state, session, event.params, rng))
            // ③ 尝试偷袭：50% 成功（妖兽血量 -10%）；失败被察觉
            else -> {
                val ambushSucceeded =
                    rng.nextDouble() >= GameConfig.SecretRealm.AMBUSH_DETECT_CHANCE
                toResolution(
                    runBeastBattle(state, session, event.params.copy(ambushSucceeded = ambushSucceeded), rng)
                )
            }
        }
    }

    /** 衔接事件分支：选择方向 → 下一事件（30% 概率空地，否则妖兽） */
    private fun resolveBridgeChoice(
        optionIndex: Int,
        session: SecretRealmExplorationSession,
        rng: DeterministicRng
    ): SecretRealmBeastChoiceResolution {
        val directionName = when (optionIndex) {
            0 -> "左路"
            1 -> "直线"
            else -> "右路"
        }
        val resultText = "你方沿${directionName}继续前行"
        val aliveRealms = session.members.filter { !it.isDead }.map { it.realm }
        val playerAvgRealm = if (aliveRealms.isEmpty()) {
            GameConfig.SecretRealm.REALM_MAX
        } else {
            aliveRealms.average().toInt()
        }
        return SecretRealmBeastChoiceResolution(
            resultText = resultText,
            members = session.members,
            nextEvent = SecretRealmEventGenerator.rollNextEvent(rng, playerAvgRealm)
        )
    }

    /** 空地事件分支：休整恢复全队 40% 最大生命（含濒死）；继续前进则成员不变 */
    private fun resolveRestArea(
        optionIndex: Int,
        state: MutableGameState,
        session: SecretRealmExplorationSession
    ): SecretRealmBeastChoiceResolution {
        if (optionIndex == 0) {
            val (newMembers, resultText) = applyRestRecovery(state, session)
            return SecretRealmBeastChoiceResolution(
                resultText = resultText,
                members = newMembers,
                nextEvent = SecretRealmEventGenerator.generateBridgeEvent(resultText)
            )
        }
        return bridgeResolution("你方不做停留，继续探索", session)
    }

    /**
     * 原地休整：存活成员恢复 maxHp×REST_RECOVERY_RATIO（满血封顶），濒死成员脱离濒死并写回弟子表；
     * 死亡 / 表中找不到 / 表级已死亡 / 满血 / 异常数据的成员跳过。
     *
     * 口径说明：上限取成员战斗口径 maxHp（战斗写回维护，含装备/功法加成），旧档 0 回退基础装配值；
     * 上限不低于当前血量，防止装备加成导致"恢复反而降血"（对抗性审查）。
     *
     * @return 恢复后的成员列表 + 结果描述（成为衔接事件前缀）
     */
    private fun applyRestRecovery(
        state: MutableGameState,
        session: SecretRealmExplorationSession
    ): Pair<List<SecretRealmMemberState>, String> {
        val tables = state.discipleTables
        val allDisciples = tables.assembleAll()
        val newMembers = session.members.map { ms ->
            if (ms.isDead) return@map ms
            // 篡改档防御：非数字 id / 表级已死亡 / 表中不存在 → 跳过
            val idInt = ms.discipleId.toIntOrNull()
            if (idInt == null || tables.isAlive[idInt] != 1) return@map ms
            val baseMaxHp = allDisciples.find { it.id == ms.discipleId }?.maxHp ?: return@map ms
            if (baseMaxHp <= 0) return@map ms
            // 战斗口径 maxHp 优先（战斗写回维护），旧档 0 回退基础装配值
            val maxHp = ms.maxHp.takeIf { it > 0 } ?: baseMaxHp
            // 濒死成员血量归一（-1 矛盾数据按濒死保底 1 血处理）；其余负值视为满血跳过
            val curHp = if (ms.isDying && ms.currentHp < 0) 1 else ms.currentHp
            if (curHp < 0) return@map ms
            // 篡改档防御：超过已知上限的血量按上限处理（战斗写回口径下 curHp 永不超过 maxHp，
            // 正常流程不会降血；旧档缺战斗 maxHp 时以基础口径收敛，不产生垃圾值）
            val safeCur = curHp.coerceAtMost(maxHp)
            val heal = (maxHp * GameConfig.SecretRealm.REST_RECOVERY_RATIO).toInt()
            // Long 运算防 Int 溢出回绕（对抗性审查：currentHp 为篡改档极大值时写坏真值表）
            val newHp = (safeCur.toLong() + heal).coerceAtMost(maxHp.toLong()).toInt()
            // 只增不减：防篡改档成员声称值低于表值时把表级血量写低
            tables.currentHps[idInt] = maxOf(newHp, tables.currentHps[idInt])
            ms.copy(
                currentHp = if (newHp >= maxHp) -1 else newHp,
                isDying = false
            )
        }
        return newMembers to "你方原地休整，全队恢复生命状态"
    }

    /** 战斗结果 → 分支结算载体（衔接事件前缀 = 战斗结果文本） */
    private fun toResolution(
        outcome: SecretRealmBattleOutcome
    ): SecretRealmBeastChoiceResolution = SecretRealmBeastChoiceResolution(
        resultText = outcome.resultText,
        enteredCombat = true,
        combatLog = outcome.log,
        victory = outcome.victory,
        backpack = outcome.backpack,
        members = outcome.members,
        deadIds = outcome.deadIds,
        params = outcome.params,
        nextEvent = SecretRealmEventGenerator.generateBridgeEvent(outcome.resultText)
    )

    /** 无战斗分支结算载体（成员不变） */
    private fun bridgeResolution(
        resultText: String,
        session: SecretRealmExplorationSession
    ): SecretRealmBeastChoiceResolution = SecretRealmBeastChoiceResolution(
        resultText = resultText,
        members = session.members,
        nextEvent = SecretRealmEventGenerator.generateBridgeEvent(resultText)
    )

    // ── 战斗执行（事务内） ────────────────────────────────────────────

    private fun runBeastBattle(
        state: MutableGameState,
        session: SecretRealmExplorationSession,
        eventParams: SecretRealmEventParams,
        rng: DeterministicRng
    ): SecretRealmBattleOutcome {
        val result = buildAndExecuteBattle(state, session, eventParams, rng)
            ?: return SecretRealmBattleOutcome(
                victory = false, log = null, backpack = session.backpack,
                members = session.members, deadIds = emptySet(), params = eventParams,
                resultText = "队伍已无战力，战斗不战而败"
            )

        val year = state.gameData.gameYear
        val (newMembers, deadIds) = writeBackBattleMembers(state, session, result, year)
        val beastName = recordBattleLog(state, result, eventParams, session, newMembers)
        val (backpack, params, resultText) = settleBattleRewards(
            session.backpack, eventParams, result, rng, beastName
        )

        return SecretRealmBattleOutcome(
            victory = result.victory,
            log = result.log,
            backpack = backpack,
            members = newMembers,
            deadIds = deadIds,
            params = params,
            resultText = resultText
        )
    }

    /**
     * 构建参战弟子（存活成员 + 表级存活双检查；濒死成员血量强制 1）
     * 并执行战斗。无战力时返回 null（不战而败）。
     */
    private fun buildAndExecuteBattle(
        state: MutableGameState,
        session: SecretRealmExplorationSession,
        eventParams: SecretRealmEventParams,
        rng: DeterministicRng
    ): BattleSystemResult? {
        val data = state.gameData
        val tables = state.discipleTables
        val allDisciples = tables.assembleAll()
        val combatDisciples = session.members.filter { !it.isDead }.mapNotNull { ms ->
            val idInt = ms.discipleId.toIntOrNull()
            val d = allDisciples.find { it.id == ms.discipleId } ?: return@mapNotNull null
            if (idInt != null && tables.isAlive[idInt] != 1) return@mapNotNull null
            if (ms.isDying) d.copy(combat = d.combat.copy(currentHp = 1)) else d
        }
        if (combatDisciples.isEmpty()) return null

        val equipmentMap = state.equipmentInstances.all().associateBy { it.id }
        val manualMap = state.manualInstances.all().associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        val beastPreGenStats = SecretRealmEventGenerator.buildBeastPreGenStats(
            rng, eventParams.beastRealm, eventParams.beastTypeName, eventParams.ambushSucceeded,
            beastLayer = eventParams.beastLayer
        )
        // 篡改档防御：妖兽数量 clamp 到正常范围（对抗性审查 B6）
        val beastCount = eventParams.beastCount.coerceIn(
            GameConfig.SecretRealm.BEAST_COUNT_MIN,
            GameConfig.SecretRealm.BEAST_COUNT_MAX
        )
        val battle = battleSystem.createBattle(
            disciples = combatDisciples,
            equipmentMap = equipmentMap,
            manualMap = manualMap,
            beastLevel = eventParams.beastRealm,
            beastCount = beastCount,
            beastType = eventParams.beastTypeName,
            manualProficiencies = allProficiencies,
            beastPreGenStats = beastPreGenStats
        )
        return battleSystem.executeBattleWithTimeout(battle)
    }

    /**
     * 战斗写回：幸存者 HP 写表（clamp 上限用战斗最终 maxHp，含装备/功法/丹药加成——
     * 对抗性审查 B1）；首次阵亡 → 重伤濒死（保命）；濒死再阵亡 → 永久死亡（统一入口）。
     */
    private fun writeBackBattleMembers(
        state: MutableGameState,
        session: SecretRealmExplorationSession,
        result: BattleSystemResult,
        year: Int
    ): Pair<List<SecretRealmMemberState>, Set<String>> {
        val tables = state.discipleTables
        val survivorIds = result.battle.team.filter { !it.isDead }.map { it.id }.toSet()
        val hpMap = result.battle.team.associate { it.id to (it.hp to it.maxHp) }
        val deadIds = mutableSetOf<String>()
        val newMembers = session.members.map { ms ->
            if (ms.isDead) return@map ms
            val idInt = ms.discipleId.toIntOrNull()
            val (hp, maxHp) = hpMap[ms.discipleId] ?: return@map ms
            if (ms.discipleId in survivorIds) {
                val clamped = hp.coerceIn(0, maxHp)
                idInt?.let { tables.currentHps[it] = clamped }
                // 记录战斗口径 maxHp（含装备/功法加成），供休整恢复/血条显示使用
                ms.copy(currentHp = if (clamped >= maxHp) -1 else clamped, maxHp = maxHp)
            } else {
                if (ms.isDying) {
                    idInt?.let { tables.markDead(it, year, "battle") }
                    deadIds.add(ms.discipleId)
                    ms.copy(isDead = true)
                } else {
                    idInt?.let { tables.currentHps[it] = 1 }
                    ms.copy(isDying = true, currentHp = 1, maxHp = maxHp)
                }
            }
        }
        return newMembers to deadIds
    }

    /** 战斗日志（玩家战斗记录统一入口），返回妖兽显示名 */
    private fun recordBattleLog(
        state: MutableGameState,
        result: BattleSystemResult,
        eventParams: SecretRealmEventParams,
        session: SecretRealmExplorationSession,
        newMembers: List<SecretRealmMemberState>
    ): String {
        val beastConfig = GameConfig.Beast.TYPES.firstOrNull { it.name == eventParams.beastTypeName }
        val beastName = "${beastConfig?.prefix.orEmpty()}${eventParams.beastTypeName}"
        state.recordPlayerBattle(
            year = state.gameData.gameYear,
            month = state.gameData.gameMonth,
            type = BattleType.PVE,
            attackerName = "玩家探索队伍",
            defenderName = "$beastName × ${eventParams.beastCount}",
            result = if (result.victory) BattleResult.WIN else BattleResult.LOSE,
            teamMembers = result.battle.team.map { m ->
                BattleLogMember(
                    id = m.id, name = m.name, realm = m.realm, realmName = m.realmName,
                    hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp,
                    isAlive = !m.isDead, portraitRes = m.portraitRes
                )
            },
            enemies = result.battle.beasts.map { b ->
                BattleLogEnemy(
                    id = b.id, name = b.name, realm = b.realm, realmName = b.realmName,
                    hp = b.hp, maxHp = b.maxHp, isAlive = !b.isDead, portraitRes = b.portraitRes
                )
            },
            rounds = result.log.rounds.map { r ->
                BattleLogRound(
                    roundNumber = r.roundNumber,
                    actions = r.actions.map { a ->
                        BattleLogAction(
                            type = a.type, attacker = a.attacker, attackerType = a.attackerType,
                            target = a.target, damage = a.damage, damageType = a.damageType,
                            isCrit = a.isCrit, isKill = a.isKill, message = a.message
                        )
                    }
                )
            },
            turns = result.turnCount,
            details = if (result.victory) "秘境探索：击败了$beastName × ${eventParams.beastCount}"
                else "秘境探索：被${beastName}击败",
            beastsDefeated = if (result.victory) eventParams.beastCount
                else result.battle.beasts.count { it.isDead },
            teamCasualties = newMembers.count { it.isDying || it.isDead } -
                session.members.count { it.isDying }
        )
        return beastName
    }

    /** 战斗奖励/损失结算：胜利掉落（每妖兽 2 材料 + 灵石）入背包；失败丢失 20%~45% */
    private fun settleBattleRewards(
        backpack: SecretRealmBackpack,
        eventParams: SecretRealmEventParams,
        result: BattleSystemResult,
        rng: DeterministicRng,
        beastName: String
    ): Triple<SecretRealmBackpack, SecretRealmEventParams, String> {
        if (result.victory) {
            val loot = SecretRealmEventGenerator.rollBeastLoot(
                rng, eventParams.beastTypeName, eventParams.beastRealm, eventParams.beastCount
            )
            // 预构建 id → 模板映射，避免逐件线性查找
            val templatesById = BeastMaterialDatabase.getAllMaterials()
                .associateBy { it.id }
            val materials = loot.map { item ->
                val template = templatesById[item.itemId]
                Material(
                    id = UUID.randomUUID().toString(),
                    name = item.name,
                    rarity = item.rarity,
                    description = template?.description ?: "",
                    category = template?.materialCategory
                        ?: com.xianxia.sect.core.model.MaterialCategory.BEAST_HIDE,
                    quantity = 1
                )
            }
            val stoneReward = result.rewards["spiritStones"] ?: 0
            val newBackpack = backpack.copy(
                materials = backpack.materials + materials,
                spiritStones = backpack.spiritStones + stoneReward
            )
            val newParams = eventParams.copy(
                itemRewards = loot,
                spiritStones = stoneReward.toLong()
            )
            val text = if (materials.isEmpty() && stoneReward <= 0) {
                "战斗结束！你方击退了${eventParams.beastCount}只$beastName"
            } else {
                "战斗结束！你方击退了${eventParams.beastCount}只$beastName，" +
                    "获得材料 ×${materials.size}、灵石 $stoneReward"
            }
            return Triple(newBackpack, newParams, text)
        }

        val loss = SecretRealmBattleHelper.applyLootLoss(backpack, rng)
        val newParams = eventParams.copy(lostItemCount = loss.lostItemCount)
        val lostParts = buildList {
            if (loss.lostItemCount > 0) add("物品 ×${loss.lostItemCount}")
            if (loss.lostSpiritStones > 0) add("灵石 ${loss.lostSpiritStones}")
        }
        val text = "战斗结束！你方不敌妖兽，仓促撤退，" +
            (if (lostParts.isEmpty()) "所幸所得未受损失"
            else "丢失了${lostParts.joinToString("、")}")
        return Triple(loss.backpack, newParams, text)
    }

    // ── 结束 / 结算 ───────────────────────────────────────────────────

    /**
     * 结束探索（主动结束/体力耗尽/全灭统一入口）：结算背包 → 秘境消失 + 冷却年 + AI 队伍清场。
     * 幂等：会话与秘境均已不存在时直接返回。
     */
    fun endSession(state: MutableGameState, reason: SecretRealmEndReason = SecretRealmEndReason.EXPLORER_END) {
        val data = state.gameData
        if (!data.secretRealmSession.isActive && !data.secretRealmState.exists) return
        val session = data.secretRealmSession
        if (session.isActive) {
            settleBackpack(state, session.backpack)
        }
        // 基于结算后的最新 gameData 清空秘境（settleBackpack 内部会更新灵石/年度统计等，
        // 用旧快照 copy 会覆盖这些写入导致奖励丢失——对抗性审查 S1）
        val year = state.gameData.gameYear
        state.gameData = state.gameData.copy(
            secretRealmState = SecretRealmState(),
            secretRealmCooldownYear = year,
            secretRealmSession = SecretRealmExplorationSession(),
            secretRealmAITeams = emptyList()
        )
        state.recordGameEvent(
            GameEventCategory.SECT,
            GameEventType.SECRET_REALM,
            when (reason) {
                SecretRealmEndReason.EXPLORER_END -> "远古秘境已关闭，探索队伍带着收获返回了宗门"
                SecretRealmEndReason.EXHAUSTED -> "探索队伍体力耗尽，被传送出了远古秘境"
                SecretRealmEndReason.WIPEOUT -> "探索队伍全军覆没，远古秘境随之消散"
            }
        )
    }

    /**
     * 探索背包结算：灵石入钱包，物品经 withTrackingSource("secret_realm") 入宗门仓库
     * （溢出自动转邮件，守卫测试强制来源注册）。
     */
    fun settleBackpack(state: MutableGameState, backpack: SecretRealmBackpack) {
        if (backpack.spiritStones > 0) {
            spiritStoneWallet.add(
                state, backpack.spiritStones,
                grade = SpiritStoneGrade.LOW,
                source = SpiritStoneSource.SecretRealm
            )
        }
        if (backpack.totalItemCount == 0) return
        inventorySystem.withTrackingSource("secret_realm") {
            backpack.equipment.forEach { item ->
                settleItem(item.name, item.rarity, "equipment", inventorySystem.addEquipmentStack(item))
            }
            backpack.manuals.forEach { item ->
                settleItem(item.name, item.rarity, "manual", inventorySystem.addManualStack(item))
            }
            backpack.pills.forEach { item ->
                settleItem(item.name, item.rarity, "pill", inventorySystem.addPill(item))
            }
            backpack.materials.forEach { item ->
                settleItem(item.name, item.rarity, "material", inventorySystem.addMaterial(item))
            }
            backpack.herbs.forEach { item ->
                settleItem(item.name, item.rarity, "herb", inventorySystem.addHerb(item))
            }
        }
    }

    /**
     * 单件物品结算结果处理：Partial 溢出已自动转邮件；
     * Failure（如篡改档非法物品）转邮件补偿，防止物品静默丢失（对抗性审查 D2）。
     */
    private fun settleItem(
        itemName: String,
        itemRarity: Int,
        itemType: String,
        result: DomainResult<*>
    ) {
        when (result) {
            is DomainResult.Success -> Unit
            is DomainResult.Partial -> DomainLog.w(
                TAG, "秘境结算 $itemName 溢出 ${result.overflow} 个（已转邮件）"
            )
            is DomainResult.Failure -> {
                DomainLog.w(TAG, "秘境结算 $itemName 失败: ${result.error}，转邮件补偿")
                inventorySystem.sendOverflowMail(
                    "secret_realm", itemType, itemName, itemRarity, 1
                )
            }
        }
    }

    companion object {
        private const val TAG = "SecretRealmService"
    }
}
