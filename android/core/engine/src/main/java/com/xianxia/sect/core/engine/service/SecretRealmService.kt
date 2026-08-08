package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmBattleHelper
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmChoiceResult
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmBattleOutcome
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmBeastChoiceResolution
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmEndReason
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmEventGenerator
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmRuinsResolver
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
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
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
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
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
// （事件结算已拆至 SecretRealmEventGenerator / SecretRealmRuinsResolver，此处保留事务编排）
@Suppress("TooManyFunctions", "LargeClass")
class SecretRealmService @Inject constructor(
    private val rngManager: GameRngManager,
    private val battleSystem: BattleSystem,
    private val inventorySystem: InventorySystem,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val overflowMailSender: OverflowMailSender,
    private val assignmentGate: DiscipleAssignmentGate
) {

    // ── 年变刷新 ──────────────────────────────────────────────────────

    /**
     * 年变刷新：确定性开启——距上次消失（或开档，cooldownYear=0）满
     * COOLDOWN_YEARS（50）年必现世（"每50年开启一次"），位置/精灵变体仍随机。
     */
    @Suppress("ReturnCount")
    fun processYearlySpawn(year: Int, state: MutableGameState) {
        val data = state.gameData
        if (data.secretRealmState.exists) return
        // 篡改档防御：负冷却年视为从未出现（clamp 到 0），避免绕过冷却判据
        val cooldown = data.secretRealmCooldownYear.coerceAtLeast(0)
        // 统一判据：首次（cooldown=0）第 50 年现世；之后每次消失后再过 50 年
        if (year - cooldown < GameConfig.SecretRealm.COOLDOWN_YEARS) return
        val rng = rngManager.getRng(RngPartition.SECRET_REALM)

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
        // 存活校验（死亡弟子不可出发）。状态 IDLE 校验已移除：换岗语义下引擎入口
        // （startSecretRealmExploration）先于此处校验通过后执行 releaseDiscipleToIdleInside
        // 清空岗位再出发，校验在清理前会错误拒绝在岗弟子（原校验已因清理前置成为死代码）
        val dead = selected.firstOrNull { !it.isAlive }
        if (dead != null) {
            return DomainResult.Failure(
                AppError.Domain.Validation.InvalidInput("弟子「${dead.name}」已死亡")
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
     * 玩家选择事件选项：扣体力 → 结算效果（战斗/远离/搜寻/休整）→ 进入探索方向事件；
     * 选择方向后生成下一真实事件。体力耗尽或队伍全灭时自动结束探索。
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
        val newStamina = calculateNewStamina(session, activeEvent, optionIndex)
        val markedEvent = activeEvent.copy(chosenOptionIndex = optionIndex)
        // 篡改档非法 eventType / 旧档 BRIDGE 回退方向事件分支（getOrDefault 兜底，无战斗更安全）
        val resolution = when (resolveEventType(activeEvent.eventType)) {
            SecretRealmEventType.BEAST_ENCOUNTER ->
                resolveBeastEncounter(optionIndex, state, session, activeEvent, rng)
            SecretRealmEventType.REST_AREA ->
                resolveRestArea(optionIndex, state, session)
            SecretRealmEventType.RUIN_EXPLORE ->
                SecretRealmRuinsResolver.resolveRuinsExplore(optionIndex, session, rng)
            SecretRealmEventType.RUIN_RESULT ->
                SecretRealmRuinsResolver.resolveRuinsResult(session, activeEvent)
            SecretRealmEventType.DIRECTION_CHOICE ->
                resolveDirectionChoice(optionIndex, state, session, rng)
            SecretRealmEventType.AI_SECT_ENCOUNTER ->
                resolveAISectEncounter(optionIndex, state, session, activeEvent, rng)
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
        // 基于最新 state.gameData 合并（resolver 可能已就地更新 aiSectDisciples/队伍等字段，
        // 用旧 data 引用 copy 会把这些修改覆盖回旧值）
        state.gameData = state.gameData.copy(secretRealmSession = updatedSession)

        if (sessionEnded) {
            endSession(state, if (allDead) SecretRealmEndReason.WIPEOUT else SecretRealmEndReason.EXHAUSTED)
            return buildEndedResult(resolution, allDead, session)
        }
        return buildContinueResult(resolution)
    }

    /** 会话结束结果（体力耗尽/全灭）：附带释放 gate 占用的成员 ID */
    private fun buildEndedResult(
        resolution: SecretRealmBeastChoiceResolution,
        allDead: Boolean,
        session: SecretRealmExplorationSession
    ): SecretRealmChoiceResult.Success = SecretRealmChoiceResult.Success(
        message = if (allDead) "队伍全军覆没！" else "体力耗尽，被传送出秘境",
        sessionEnded = true,
        enteredCombat = resolution.enteredCombat,
        combatLog = resolution.combatLog,
        victory = resolution.victory,
        deadIds = resolution.deadIds,
        releasedMemberIds = session.members.map { it.discipleId }.toSet(),
        ambushSucceeded = resolution.params.ambushSucceeded
    )

    /** 会话继续结果（战斗播放数据透传） */
    private fun buildContinueResult(
        resolution: SecretRealmBeastChoiceResolution
    ): SecretRealmChoiceResult.Success = SecretRealmChoiceResult.Success(
        message = resolution.resultText,
        enteredCombat = resolution.enteredCombat,
        combatLog = resolution.combatLog,
        victory = resolution.victory,
        deadIds = resolution.deadIds,
        ambushSucceeded = resolution.params.ambushSucceeded
    )

    /** 事件类型字符串解析（篡改档非法值 / 旧档 BRIDGE 回退方向事件分支） */
    private fun resolveEventType(eventType: String): SecretRealmEventType =
        runCatching { SecretRealmEventType.valueOf(eventType) }
            .getOrDefault(SecretRealmEventType.DIRECTION_CHOICE)

    /**
     * 计算选择选项后的体力：按选项自身体力消耗扣除（默认 1）。
     * 篡改档防御：非法消耗（0/负数/超大值）clamp 到 1..STAMINA_MAX——选项永不免费、
     * 单次最多耗尽整管体力；Long 运算防 MIN_VALUE 回绕（对抗性审查 B11/D3）。
     */
    private fun calculateNewStamina(
        session: SecretRealmExplorationSession,
        event: SecretRealmEventRecord,
        optionIndex: Int
    ): Int {
        val optionCost = event.options.getOrNull(optionIndex)?.staminaCost
            ?: GameConfig.SecretRealm.STAMINA_COST_PER_CHOICE
        val safeCost = optionCost.coerceIn(
            GameConfig.SecretRealm.STAMINA_COST_PER_CHOICE,
            GameConfig.SecretRealm.STAMINA_MAX
        )
        return (session.stamina.toLong() - safeCost)
            .coerceIn(0, GameConfig.SecretRealm.STAMINA_MAX.toLong())
            .toInt()
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
        // 篡改档防御：体力已耗尽时拒绝结算，防 0 体力白嫖事件收益（对抗性审查 M1）
        if (session.stamina <= 0) {
            return SecretRealmChoiceResult.Error(message = "体力已耗尽，探索结束")
        }
        // 篡改档防御：体力不足所选选项消耗时拒绝——防"仔细搜寻"等高费选项在体力不足时
        // 按低费扣费全额结算，违背"所见即所扣"承诺（对抗性审查 M2）
        val optionCost = event.options.getOrNull(optionIndex)?.staminaCost
            ?: GameConfig.SecretRealm.STAMINA_COST_PER_CHOICE
        if (session.stamina < optionCost) {
            return SecretRealmChoiceResult.Error(message = "体力不足，无法选择该选项")
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
                    directionResolution(
                        "你方悄然绕行，成功避开了妖兽的注意", session
                    )
                }
            }
            // ② 发起战斗
            1 -> toResolution(runBeastBattle(state, session, event.params, rng))
            // ③ 尝试偷袭：50% 成功（妖兽血量 -10%）；失败被察觉
            else -> {
                val ambushSucceeded =
                    rng.nextDouble() >= GameConfig.SecretRealm.AMBUSH_DETECT_CHANCE
                toResolution(
                    runBeastBattle(
                        state, session,
                        event.params.copy(ambushSucceeded = ambushSucceeded), rng
                    )
                )
            }
        }
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
                // 携带会话背包（休整不改变背包，防 chooseOption 空覆盖——对抗性审查发现）
                backpack = session.backpack,
                // 休整结算后进入探索方向事件
                nextEvent = SecretRealmEventGenerator.generateDirectionEvent(resultText)
            )
        }
        return directionResolution("你方不做停留，继续探索", session)
    }

    /**
     * 方向事件分支（结束选项）：按选项索引取方向名 → 结算文本 → 消费一次 RNG 生成下一真实事件。
     *
     * 方向纯过渡：不触碰任何概率配置，rollNextEvent 四分段（30% 空地 / 20% 遗迹 /
     * 15% AI 遭遇 / 妖兽）不变。RNG 消费时机与旧 BRIDGE 一致（方向选择时才消费
     * nextDouble），读档重放序列不变。
     */
    private fun resolveDirectionChoice(
        optionIndex: Int,
        state: MutableGameState,
        session: SecretRealmExplorationSession,
        rng: DeterministicRng
    ): SecretRealmBeastChoiceResolution {
        val directionName = when (optionIndex) {
            0 -> "左路"
            1 -> "中路"
            // 篡改档防御：超出 0/1/2 的选项一律按右路处理
            else -> "右路"
        }
        val resultText = "你方沿${directionName}继续前行"
        return SecretRealmBeastChoiceResolution(
            resultText = resultText,
            members = session.members,
            // 携带会话背包（方向选择不改变背包，防 chooseOption 空覆盖——对抗性审查发现）
            backpack = session.backpack,
            nextEvent = SecretRealmEventGenerator.rollNextEvent(
                rng,
                SecretRealmEventGenerator.playerAvgRealm(session.members),
                state.gameData.secretRealmAITeams
            )
        )
    }

    // ── AI 宗门遭遇（事务内） ────────────────────────────────────────

    /**
     * AI 宗门遭遇事件分支：向左/向右避让必成功（成功远离，不消费 RNG）；
     * 与之交战进入 PvP 战斗（胜利得 1~15 件品阶按宗门等级判定的物品，战败与妖兽战斗
     * 一致——损失背包物品、成员可能死亡、全灭 WIPEOUT 结束）。
     */
    private fun resolveAISectEncounter(
        optionIndex: Int,
        state: MutableGameState,
        session: SecretRealmExplorationSession,
        event: SecretRealmEventRecord,
        rng: DeterministicRng
    ): SecretRealmBeastChoiceResolution {
        val sectName = event.params.aiSectName.ifEmpty { "对方" }
        return when (optionIndex) {
            // ① 向左避让（必成功）
            0 -> directionResolution(
                "你方悄然向左避让，与${sectName}的探索队伍擦肩而过", session
            )
            // ② 与之交战
            1 -> toResolution(runAISectBattle(state, session, event.params, rng))
            // ③ 向右避让（必成功）；篡改档防御：超出 0/1/2 的选项一律按向右避让处理
            else -> directionResolution(
                "你方悄然向右避让，与${sectName}的探索队伍擦肩而过", session
            )
        }
    }

    /**
     * AI 宗门探索队伍交战（PvP）：战斗前按 [aiSectDisciples] 现况过滤存活成员，
     * 对方全灭/不存在 → 直通"无力应战"（无战斗无奖励）。
     */
    private fun runAISectBattle(
        state: MutableGameState,
        session: SecretRealmExplorationSession,
        eventParams: SecretRealmEventParams,
        rng: DeterministicRng
    ): SecretRealmBattleOutcome {
        val sectDisciples = state.gameData.aiSectDisciples[eventParams.aiSectId]
        val aiDisciples = eventParams.aiMembers.mapNotNull { m ->
            sectDisciples?.firstOrNull { it.id == m.discipleId && it.isAlive }
        }
        if (aiDisciples.isEmpty()) {
            return noBattleOutcome(session, eventParams, "对方的探索队伍已无力应战，你方绕过继续前行")
        }
        return settleAiEncounterBattle(state, session, eventParams, aiDisciples, rng)
    }

    /** 无战斗直通结果（对方全灭 / 我方无战力，不扣体力之外的任何代价） */
    private fun noBattleOutcome(
        session: SecretRealmExplorationSession,
        eventParams: SecretRealmEventParams,
        resultText: String
    ): SecretRealmBattleOutcome = SecretRealmBattleOutcome(
        victory = false, log = null, backpack = session.backpack,
        members = session.members, deadIds = emptySet(), params = eventParams,
        resultText = resultText
    )

    /** AI 遭遇战主结算：战斗 → 成员写回 → 战报 → 奖励/损失 → 战死标记（仅 1 处 return） */
    private fun settleAiEncounterBattle(
        state: MutableGameState,
        session: SecretRealmExplorationSession,
        eventParams: SecretRealmEventParams,
        aiDisciples: List<com.xianxia.sect.core.model.Disciple>,
        rng: DeterministicRng
    ): SecretRealmBattleOutcome {
        val result = buildAndExecuteAISectBattle(state, session, aiDisciples)
            ?: return noBattleOutcome(session, eventParams, "队伍已无战力，战斗不战而败")

        val year = state.gameData.gameYear
        val (newMembers, deadIds) = writeBackBattleMembers(state, session, result, year)
        recordAISectBattleLog(state, result, eventParams, session, newMembers)
        val (backpack, params, resultText) = settleAISectBattleRewards(
            session.backpack, eventParams, result, rng
        )
        if (result.victory) {
            val aiDead = result.battle.beasts.filter { it.isDead }.map { it.id }.toSet()
            markAiTeamDefeated(state, eventParams.aiSectId, aiDead)
        }
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
     * 构建 PvP 战斗并执行：玩家侧（存活成员 + 濒死强制 1 血，沿用妖兽战斗口径）vs
     * AI 侧（模拟装备/功法，满血）。无战力时返回 null（不战而败）。
     */
    private fun buildAndExecuteAISectBattle(
        state: MutableGameState,
        session: SecretRealmExplorationSession,
        aiDisciples: List<com.xianxia.sect.core.model.Disciple>
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
        val aiPrepared = AISectDiscipleManager.prepareDisciplesForBattle(aiDisciples)
        val team = combatDisciples.map { d ->
            battleSystem.convertDiscipleToCombatant(
                disciple = d,
                equipmentMap = equipmentMap,
                manualMap = manualMap,
                manualProficiencies = allProficiencies,
                side = CombatantSide.DEFENDER,
                fullHeal = false,
                bloodRefinementPct = data.bloodRefinementPctTotals[d.id]
            )
        }
        val beasts = aiPrepared.disciples.map { d ->
            battleSystem.convertDiscipleToCombatant(
                disciple = d,
                equipmentMap = aiPrepared.equipmentMapByDisciple[d.id] ?: emptyMap(),
                manualMap = aiPrepared.manualMap,
                manualProficiencies = aiPrepared.proficiencies,
                side = CombatantSide.ATTACKER,
                fullHeal = true
            )
        }
        val battle = Battle(
            team = team,
            beasts = beasts,
            maxTurns = GameConfig.Battle.MAX_TURNS
        )
        return battleSystem.executeBattleWithTimeout(battle)
    }

    /** AI 遭遇战斗日志（BattleType.PVP），镜像 [recordBattleLog] 的字段口径 */
    private fun recordAISectBattleLog(
        state: MutableGameState,
        result: BattleSystemResult,
        eventParams: SecretRealmEventParams,
        session: SecretRealmExplorationSession,
        newMembers: List<SecretRealmMemberState>
    ) {
        val sectName = eventParams.aiSectName.ifEmpty { "对方" }
        state.recordPlayerBattle(
            year = state.gameData.gameYear,
            month = state.gameData.gameMonth,
            type = BattleType.PVP,
            attackerName = "玩家探索队伍",
            defenderName = "${sectName}探索队伍",
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
            details = if (result.victory) "秘境探索：击败了${sectName}的探索队伍"
                else "秘境探索：被${sectName}的探索队伍击败",
            beastsDefeated = result.battle.beasts.count { it.isDead },
            teamCasualties = newMembers.count { it.isDying || it.isDead } -
                session.members.count { it.isDying }
        )
    }

    /**
     * AI 遭遇战斗奖励/损失结算：
     * 胜利 → 按宗门等级品阶区间生成 1~15 件奖励（六类随机，无灵石）入背包；
     * 战败 → 丢失背包 20%~45%（与妖兽战斗一致）。
     */
    private fun settleAISectBattleRewards(
        backpack: SecretRealmBackpack,
        eventParams: SecretRealmEventParams,
        result: BattleSystemResult,
        rng: DeterministicRng
    ): Triple<SecretRealmBackpack, SecretRealmEventParams, String> {
        if (result.victory) {
            val rarityRange = GameConfig.SecretRealm.AI_REWARD_RARITY_RANGES
                .getOrElse(eventParams.aiSectLevel) {
                    GameConfig.SecretRealm.AI_REWARD_RARITY_RANGES[0]
                }
            val rewards = SecretRealmEventGenerator.generateRuinsTreasure(
                rng,
                GameConfig.SecretRealm.AI_REWARD_COUNT_RANGE.first,
                GameConfig.SecretRealm.AI_REWARD_COUNT_RANGE.last,
                rarityRange.first,
                rarityRange.last
            )
            val newBackpack = SecretRealmRuinsResolver.instantiateRuinsRewards(rewards, backpack)
            val newParams = eventParams.copy(itemRewards = rewards)
            val sectName = eventParams.aiSectName.ifEmpty { "对方" }
            val text = if (rewards.isEmpty()) {
                "战斗结束！你方击败了${sectName}的探索队伍"
            } else {
                "战斗结束！你方击败了${sectName}的探索队伍，缴获物品 ×${rewards.size}"
            }
            return Triple(newBackpack, newParams, text)
        }

        val loss = SecretRealmBattleHelper.applyLootLoss(backpack, rng)
        val newParams = eventParams.copy(lostItemCount = loss.lostItemCount)
        val sectName = eventParams.aiSectName.ifEmpty { "对方" }
        val lostParts = buildList {
            if (loss.lostItemCount > 0) add("物品 ×${loss.lostItemCount}")
            if (loss.lostSpiritStones > 0) add("灵石 ${loss.lostSpiritStones}")
        }
        val text = "战斗结束！你方不敌${sectName}的探索队伍，仓促撤退，" +
            (if (lostParts.isEmpty()) "所幸所得未受损失"
            else "丢失了${lostParts.joinToString("、")}")
        return Triple(loss.backpack, newParams, text)
    }

    /**
     * 交战击败后处理：战死 AI 弟子写 isAlive=false（对齐 PatrolBattleSystem.markAiDeaths），
     * 并从 [secretRealmAITeams] 移除该队伍（月结会为仍有存活弟子的宗门重新派遣）。
     */
    private fun markAiTeamDefeated(state: MutableGameState, aiSectId: String, aiDead: Set<String>) {
        val data = state.gameData
        val updatedAi = if (aiDead.isEmpty()) {
            data.aiSectDisciples
        } else {
            data.aiSectDisciples + (aiSectId to (data.aiSectDisciples[aiSectId]?.map { d ->
                if (d.id in aiDead) d.copy(isAlive = false, status = DiscipleStatus.DEAD) else d
            } ?: emptyList()))
        }
        state.gameData = data.copy(
            aiSectDisciples = updatedAi,
            secretRealmAITeams = data.secretRealmAITeams.filter { it.sectId != aiSectId }
        )
    }

    /**
     * 原地休整：存活成员恢复 maxHp×REST_RECOVERY_RATIO（满血封顶），濒死成员脱离濒死并写回弟子表；
     * 死亡 / 表中找不到 / 表级已死亡 / 满血 / 异常数据的成员跳过。
     *
     * 口径说明：上限取成员战斗口径 maxHp（战斗写回维护，含装备/功法加成），旧档 0 回退基础装配值；
     * 上限不低于当前血量，防止装备加成导致"恢复反而降血"（对抗性审查）。
     *
     * @return 恢复后的成员列表 + 结果描述（成为方向事件描述前缀）
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

    /** 战斗结果 → 分支结算载体（结算后进入探索方向事件，resultText 成为方向事件描述前缀） */
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
        nextEvent = SecretRealmEventGenerator.generateDirectionEvent(outcome.resultText)
    )

    /** 无战斗分支结算载体（成员不变，携带会话背包防清空——对抗性审查发现；结算后进入探索方向事件） */
    private fun directionResolution(
        resultText: String,
        session: SecretRealmExplorationSession
    ): SecretRealmBeastChoiceResolution = SecretRealmBeastChoiceResolution(
        resultText = resultText,
        members = session.members,
        backpack = session.backpack,
        nextEvent = SecretRealmEventGenerator.generateDirectionEvent(resultText)
    )

    // ── 战斗执行（事务内） ────────────────────────────────────────────

    private fun runBeastBattle(
        state: MutableGameState,
        session: SecretRealmExplorationSession,
        eventParams: SecretRealmEventParams,
        rng: DeterministicRng
    ): SecretRealmBattleOutcome {
        // 篡改档防御：妖兽数量 clamp 到配置范围——buildAndExecuteBattle 已 clamp 战斗构建，
        // 但 rollBeastLoot 的 repeat(beastCount*2) 与战斗日志文本仍用原始值，
        // Int.MAX 会溢出负数崩溃 / 上亿次循环卡死引擎线程（对抗性审查 M3）
        val safeParams = eventParams.copy(
            beastCount = eventParams.beastCount.coerceIn(
                GameConfig.SecretRealm.BEAST_COUNT_MIN,
                GameConfig.SecretRealm.BEAST_COUNT_MAX
            )
        )
        val result = buildAndExecuteBattle(state, session, safeParams, rng)
            ?: return SecretRealmBattleOutcome(
                victory = false, log = null, backpack = session.backpack,
                members = session.members, deadIds = emptySet(), params = safeParams,
                resultText = "队伍已无战力，战斗不战而败"
            )

        val year = state.gameData.gameYear
        val (newMembers, deadIds) = writeBackBattleMembers(state, session, result, year)
        val beastName = recordBattleLog(state, result, safeParams, session, newMembers)
        val (backpack, params, resultText) = settleBattleRewards(
            session.backpack, safeParams, result, rng, beastName
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
            beastPreGenStats = beastPreGenStats,
            bloodRefinementMap = data.bloodRefinementPctTotals
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
                    // D-03：死亡统一入口——袋物品物化回仓库（玩家保留）+ 清袋 + markDead
                    idInt?.let { inventorySystem.materializeDiscipleBagAndMarkDead(state, it, year, "battle") }
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

    // ── 现世期满自动关闭 ──────────────────────────────────────────────

    /**
     * 月结到期检查：秘境现世满 [GameConfig.SecretRealm.OPEN_YEARS] 年 → 自动关闭。
     * 幂等（无秘境/未到期直接返回）。探索界面打开时时间冻结，月结不运行——
     * 本检查只会在"会话存在但玩家不在探索界面"时触发。
     */
    internal fun processMonthlyExpiryCheck(state: MutableGameState, year: Int) {
        val realm = state.gameData.secretRealmState
        if (!realm.exists) return
        if (year < realm.spawnYear + GameConfig.SecretRealm.OPEN_YEARS) return
        closeSecretRealmByExpiry(state)
    }

    /**
     * 到期关闭：灵石入钱包 → 背包物品转邮件 → 清空背包 → endSession(EXPIRED) → 释放 gate。
     * 幂等：秘境与会话均已不存在时直接返回。
     * 邮件经 OverflowMailSender.sendDirectMail 异步落库（非 suspend，事务内安全）；
     * 关闭后进入现有 50 年冷却逻辑。
     */
    internal fun closeSecretRealmByExpiry(state: MutableGameState) {
        val data = state.gameData
        val session = data.secretRealmSession
        if (!data.secretRealmState.exists && !session.isActive) return
        val memberIds = session.members.map { it.discipleId }.toSet()
        if (session.backpack.spiritStones > 0) {
            spiritStoneWallet.add(
                state, session.backpack.spiritStones,
                grade = SpiritStoneGrade.LOW,
                source = SpiritStoneSource.SecretRealm
            )
        }
        val mail = buildExpiryCloseMail(data.currentSlot, session.backpack, System.currentTimeMillis())
        // 先清空背包再 endSession：settleBackpack 对空背包 no-op，杜绝邮件+入仓双发放
        state.gameData = data.copy(secretRealmSession = session.copy(backpack = SecretRealmBackpack()))
        endSession(state, SecretRealmEndReason.EXPIRED)
        // 纯内存 registry 操作，事务内安全
        memberIds.forEach { assignmentGate.release(it) }
        mail?.let { overflowMailSender.sendDirectMail(it) }
    }

    /**
     * 构造秘境关闭邮件：背包六类物品逐件转为附件（itemId 按名称+品阶解析模板，
     * 未命中为 null 由 MailService 回退品阶随机）。空背包返回 null（不产生无附件邮件）。
     */
    internal fun buildExpiryCloseMail(
        slotId: Int,
        backpack: SecretRealmBackpack,
        now: Long
    ): MailEntity? {
        val attachments = buildList {
            addAll(buildMailAttachments("equipment", backpack.equipment) { Triple(it.name, it.rarity, it.quantity) })
            addAll(buildMailAttachments("manual", backpack.manuals) { Triple(it.name, it.rarity, it.quantity) })
            addAll(buildMailAttachments("pill", backpack.pills) { Triple(it.name, it.rarity, it.quantity) })
            addAll(buildMailAttachments("material", backpack.materials) { Triple(it.name, it.rarity, it.quantity) })
            addAll(buildMailAttachments("herb", backpack.herbs) { Triple(it.name, it.rarity, it.quantity) })
            addAll(buildMailAttachments("seed", backpack.seeds) { Triple(it.name, it.rarity, it.quantity) })
        }
        if (attachments.isEmpty()) return null
        val itemLines = attachments.joinToString("\n") { "• ${it.name} ×${it.quantity}" }
        return MailEntity(
            id = UUID.randomUUID().toString(),
            slotId = slotId,
            source = "secret_realm",
            mailType = "secret_realm_close",
            title = "远古秘境已关闭",
            content = "远古秘境已关闭，这些物品是远古秘境中获得的物品：\n\n$itemLines\n\n" +
                "（邮件自发送起 ${CLOSE_MAIL_EXPIRE_DAYS} 天内有效，逾期删除）\n——天道意志",
            senderName = "天道意志",
            sendTime = now,
            expireTime = now + CLOSE_MAIL_EXPIRE_MS,
            hasAttachment = true,
            attachments = mailJson.encodeToString(serializer<List<MailAttachment>>(), attachments)
        )
    }

    /** 按类别将背包条目转为邮件附件（数量 >0 的条目，itemId 按名称+品阶解析模板） */
    private fun <T> buildMailAttachments(
        type: String,
        items: List<T>,
        fields: (T) -> Triple<String, Int, Int>
    ): List<MailAttachment> = items.map { fields(it) }
        .filter { (_, _, quantity) -> quantity > 0 }
        .map { (name, rarity, quantity) ->
            MailAttachment(
                type = type, name = name, quantity = quantity, rarity = rarity,
                itemId = resolveTemplateIdByName(type, name, rarity)
            )
        }

    /**
     * 按名称 + 品阶解析模板 ID（邮件附件精确发放；未命中返回 null，MailService 回退品阶随机）。
     * 材料类先查普通材料再查妖兽材料（秘境妖兽掉落材料也入背包）。
     */
    private fun resolveTemplateIdByName(type: String, name: String, rarity: Int): String? =
        when (type) {
            "equipment" ->
                EquipmentDatabase.getByRarity(rarity).firstOrNull { it.name == name }?.id
            "manual" ->
                if (ManualDatabase.isInitialized) {
                    ManualDatabase.getByRarity(rarity).firstOrNull { it.name == name }?.id
                } else {
                    null
                }
            "pill" ->
                ItemDatabase.getPillsByRarity(rarity).firstOrNull { it.name == name }?.id
            "material" ->
                ItemDatabase.allMaterials.values.filter { it.rarity == rarity }
                    .firstOrNull { it.name == name }?.id
                    ?: BeastMaterialDatabase.getAllMaterials()
                        .firstOrNull { it.name == name }?.id
            "herb" ->
                HerbDatabase.getByRarity(rarity).firstOrNull { it.name == name }?.id
            "seed" ->
                HerbDatabase.getSeedsByRarity(rarity).firstOrNull { it.name == name }?.id
            else -> null
        }

    // ── 结束 / 结算 ───────────────────────────────────────────────────

    /**
     * 结束探索（主动结束/体力耗尽/全灭/期满统一入口）：结算背包 → 秘境消失 + 冷却年 + AI 队伍清场。
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
                SecretRealmEndReason.EXPIRED -> "远古秘境现世期满，已自动关闭，探索所得已通过邮件送回"
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
            // 篡改档防御：非正数量物品在调用 addXxx 前过滤——addXxx 对非法数量行为未定义，
            // 抛异常会导致 endSession 回滚 → 方向选择重试吞 RNG 的软锁（对抗性审查 B-L2）
            backpack.equipment.filter { it.quantity > 0 }.forEach { item ->
                settleItem(item.name, item.rarity, "equipment", item.quantity,
                    inventorySystem.addEquipmentStack(item))
            }
            backpack.manuals.filter { it.quantity > 0 }.forEach { item ->
                settleItem(item.name, item.rarity, "manual", item.quantity,
                    inventorySystem.addManualStack(item))
            }
            backpack.pills.filter { it.quantity > 0 }.forEach { item ->
                settleItem(item.name, item.rarity, "pill", item.quantity,
                    inventorySystem.addPill(item))
            }
            backpack.materials.filter { it.quantity > 0 }.forEach { item ->
                settleItem(item.name, item.rarity, "material", item.quantity,
                    inventorySystem.addMaterial(item))
            }
            backpack.herbs.filter { it.quantity > 0 }.forEach { item ->
                settleItem(item.name, item.rarity, "herb", item.quantity,
                    inventorySystem.addHerb(item))
            }
            backpack.seeds.filter { it.quantity > 0 }.forEach { item ->
                settleItem(item.name, item.rarity, "seed", item.quantity,
                    inventorySystem.addSeed(item))
            }
        }
    }

    /**
     * 单件物品结算结果处理：Partial 溢出已自动转邮件；
     * Failure 中仓库满（Inventory.Full）已由 addXxx 内部按整件转邮件，此处不再重复补偿
     * （对抗性审查：双重发放）；其余 Failure（如 SlotNotFound 基础设施错误）按实际数量
     * 转邮件补偿，防止物品静默丢失（对抗性审查 D2）。
     */
    private fun settleItem(
        itemName: String,
        itemRarity: Int,
        itemType: String,
        itemQuantity: Int,
        result: DomainResult<*>
    ) {
        when (result) {
            is DomainResult.Success -> Unit
            is DomainResult.Partial -> DomainLog.w(
                TAG, "秘境结算 $itemName 溢出 ${result.overflow} 个（已转邮件）"
            )
            is DomainResult.Failure -> {
                DomainLog.w(TAG, "秘境结算 $itemName 失败: ${result.error}，转邮件补偿")
                if (result.error is AppError.Domain.Inventory.Full) return
                inventorySystem.sendOverflowMail(
                    "secret_realm", itemType, itemName, itemRarity, itemQuantity
                )
            }
        }
    }

    companion object {
        private const val TAG = "SecretRealmService"

        /** 关闭邮件有效期（天）——与溢出邮件同口径，10 年保障领取窗口 */
        private const val CLOSE_MAIL_EXPIRE_DAYS = 3650L
        private const val CLOSE_MAIL_EXPIRE_MS = CLOSE_MAIL_EXPIRE_DAYS * 24 * 60 * 60 * 1000L
        private val mailJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}
