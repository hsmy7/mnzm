@file:Suppress("MatchingDeclarationName")  // 文件持转发器对象 + 其扩展（S6 native 域聚合）

package com.xianxia.sect.core.engine


import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.battle.BattleExecutionRouter
import com.xianxia.sect.core.engine.domain.battle.BattleLogData
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmChoiceResult
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.state.recordPlayerBattle
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** 秘境队伍占用的 gate 槽位类型名（与 GameEngineSecretRealmOps 一致，非持久化） */
private const val SECRET_REALM_SLOT_TYPE = "secret_realm"
/** 秘境队伍占用的 gate 槽位 ID */
private const val SECRET_REALM_SLOT_ID = "secret_realm_session"


// GameEngineSecretRealmNativeOps.kt — 秘境交互会话 native
// 转发域：SecretRealmNativeForward 转发器 +
// start/choose/end 三入口的 native 分支辅助（战报重建/公共事务外收尾/
// 团队收尾）。主入口见 GameEngineSecretRealmOps.kt；Kotlin 回退路径语义不变。

internal object SecretRealmNativeForward {

    /** 尝试经 C++ 执行秘境会话动作；成功时投递溢出邮件草稿后返回 data。 */
    @Suppress("ReturnCount")  // 多 return 为降级契约（flag 关/镜像不可用/成功路径逐级返回）
    internal fun tryForward(
        gameEngine: GameEngine,
        actionId: Int,
        paramsBuilder: JsonObjectBuilder.() -> Unit
    ): JsonElement? {
        if (!NativeEngineFlag.authoritative) return null
        // 防御性空安全：测试 mock（未 stub stateSyncServiceRef）返回 null——
        // 先过滤再进 native 调用（与 InventoryNativeForward 同守卫）
        val sync: StateSyncService? = gameEngine.stateSyncService
        if (sync == null) return null
        return GameEngineNativeOps.tryExecuteNative(
            stateSyncService = sync,
            actionId = actionId,
            paramsJson = params(paramsBuilder)
        )?.also { data -> deliverOverflowDrafts(gameEngine, data) }
    }

    /** 溢出邮件投递（战斗死亡袋物化/背包结算草稿 → Kotlin 同一投递通道）。 */
    private fun deliverOverflowDrafts(gameEngine: GameEngine, data: JsonElement) {
        val drafts = (data as? JsonObject)?.get("overflowDrafts") as? JsonArray ?: return
        val system = gameEngine.inventorySystem
        for (draft in drafts) {
            (draft as? JsonObject)?.let { deliverSecretRealmOverflowDraft(system, it) }
        }
    }
}


/** 单条溢出草稿投递（InventoryNativeForward.deliverDraft 秘境域复用——同一解析/投递通道）。 */
internal fun deliverSecretRealmOverflowDraft(system: InventorySystem, obj: JsonObject) {
    InventoryNativeForward.deliverDraft(system, obj)
}


/** 出发换岗 native 臂（1800）：C++ 执行 releaseDiscipleToIdleInside 的
 *  GameData 段（11 类槽位清理 + 思过/血炼状态重置，零 RNG——
 *  secret_realm_residual_tx.h ①）；gate 释放/Room 生产槽清槽仍由
 *  [finalizeSecretRealmTeam] 收尾。降级返回 false 回退 Kotlin 原路径。 */
internal fun GameEngine.secretRealmStartReleaseNative(memberIds: List<String>): Boolean {
    if (!NativeEngineFlag.authoritative) return false
    // 防御性空安全：测试 mock（未 stub stateSyncServiceRef）返回 null
    val sync: StateSyncService? = stateSyncService
    if (sync == null) return false
    val reply = GameEngineNativeOps.tryExecuteNative(
        stateSyncService = sync,
        actionId = ActionIds.SECRET_REALM_START_RELEASE_TX,
        paramsJson = params {
            put("memberIds", JsonArray(memberIds.map { JsonPrimitive(it) }))
        }
    ) as? JsonObject ?: return false
    return reply["releasedIds"] != null
}

/** 到期兜底（月结被绕过的极端路径）：关闭秘境并拒绝出发；未到期返回 null。
 *
 * Native 臂（1801）：C++ 到期判定 + 关闭状态段（secret_realm_settle 复用——
 * 灵石入钱包/背包清空/会话清场/冷却年/SECT 事件）；关闭邮件重建 + gate 释放经
 * [SecretRealmService.applyExpiryCloseDraft] 通道（continueSecretRealmNative 的
 * EXPIRED 行动扇出同构，backpack 解析失败按空背包兜底）。flag 关/镜像不可用/
 * 失败信封 → null 落穿 Kotlin 原路径（双实现并行契约）。
 */
internal suspend fun GameEngine.rejectIfSecretRealmExpired(): DomainResult<Unit>? {
    val snapshot = stateStore.gameDataSnapshot
    if (!snapshot.secretRealmState.exists || snapshot.gameYear <
        snapshot.secretRealmState.spawnYear + GameConfig.SecretRealm.OPEN_YEARS
    ) {
        return null
    }
    val native = SecretRealmNativeForward.tryForward(
        this, ActionIds.SECRET_REALM_EXPIRY_GUARD_TX
    ) {} as? JsonObject
    if (native != null) {
        if (native["expired"]?.jsonPrimitive?.booleanOrNull != true) {
            // 权威态判定未到期（快照与权威态竞态）→ 以权威态为准放行
            return null
        }
        val backpack = native["backpack"]?.let { bp ->
            runCatching { Json.decodeFromJsonElement<SecretRealmBackpack>(bp) }
                .getOrElse {
                    DomainLog.w("GameEngine", "秘境到期关闭背包解析失败，按空背包处理: ${it.message}")
                    SecretRealmBackpack()
                }
        } ?: SecretRealmBackpack()
        secretRealmService.applyExpiryCloseDraft(
            slotId = stateStore.gameDataSnapshot.currentSlot,
            backpack = backpack,
            memberIds = native.stringSet("memberIds")
        )
        return DomainResult.Failure(AppError.Domain.GameState.NotFound("远古秘境已关闭"))
    }
    stateStore.update { secretRealmService.closeSecretRealmByExpiry(this) }
    return DomainResult.Failure(AppError.Domain.GameState.NotFound("远古秘境已关闭"))
}

/**
 * 读档恢复 native 路径（batch-20a，AUTHORITATIVE）：C++ 会话域判定段
 * （到期关闭/死局重置/成员净化，零 RNG）+ 镜像回写；gate 释放/占用、
 * 关闭邮件重建、溢出草稿投递保留 Kotlin 平台段。失败/降级返回 null 回退。
 *
 * 行动作扇出（与 C++ continueSessionTx 信封同构）：
 * - EXPIRED：applyExpiryCloseDraft 通道（关闭邮件 + gate release——与月变
 *   nativeSettleMonth 信封 secretRealmClose 段同构）→ false
 * - RESET：gate release 释放面 → false（溢出草稿已由 tryForward 投递）
 * - NONE/PURIFIED：读档后 gate 为空重新占用净化后成员（镜像已回写）→ true
 */
internal suspend fun GameEngine.continueSecretRealmNative(): Boolean? {
    val native = SecretRealmNativeForward.tryForward(
        this, ActionIds.SECRET_REALM_CONTINUE_TX
    ) {} as? JsonObject ?: return null
    val releasedIds = native.stringSet("releasedMemberIds")
    return when (native["action"]?.jsonPrimitive?.contentOrNull) {
        "EXPIRED" -> {
            val close = native["secretRealmClose"] as? JsonObject
            if (close != null) {
                val backpack = runCatching {
                    val backpackEl = close["backpack"]
                        ?: return@runCatching SecretRealmBackpack()
                    Json.decodeFromJsonElement<SecretRealmBackpack>(backpackEl)
                }.getOrElse {
                    DomainLog.w("GameEngine", "秘境读档关闭草稿背包解析失败，按空背包处理: ${it.message}")
                    SecretRealmBackpack()
                }
                secretRealmService.applyExpiryCloseDraft(
                    slotId = stateStore.gameDataSnapshot.currentSlot,
                    backpack = backpack,
                    memberIds = releasedIds
                )
            } else {
                // 兜底：信封缺关闭段时仅释放 gate（状态段已由 C++ 清场）
                releasedIds.forEach { assignmentGate.release(it) }
            }
            false
        }
        "RESET" -> {
            releasedIds.forEach { assignmentGate.release(it) }
            false
        }
        else -> {
            // NONE / PURIFIED：读档后 gate 为空，重新占用成员防被分配他职
            // （scanAndRegister 不扫秘境会话——与 Kotlin 原路径 confirmAssign 同面）
            stateStore.gameDataSnapshot.secretRealmSession.members
                .filter { !it.isDead }
                .forEach { member ->
                    assignmentGate.confirmAssign(
                        member.discipleId,
                        SlotRef(SlotCategory.EXPLORATION_TEAM, SECRET_REALM_SLOT_TYPE, SECRET_REALM_SLOT_ID)
                    )
                }
            true
        }
    }
}

/**
 * 出发成功后的平台段收尾（native/Kotlin 两路共用）：gate 先清后登记（防多槽位
 * 残留）+ Room 生产槽清槽 + 状态同步（log-and-continue）。
 */
internal suspend fun GameEngine.finalizeSecretRealmTeam(memberIds: List<String>) {
    memberIds.forEach { assignmentGate.release(it) }
    // 双存储同步：清 Room 生产槽 Repository
    memberIds.forEach { clearDiscipleFromProductionRepository(it) }
    // 队伍成员占用（复用 EXPLORATION_TEAM 槽位，非持久化，读档后由会话重建）
    memberIds.forEach { id ->
        assignmentGate.confirmAssign(
            id, SlotRef(SlotCategory.EXPLORATION_TEAM, SECRET_REALM_SLOT_TYPE, SECRET_REALM_SLOT_ID)
        )
    }
    // log-and-continue：状态同步失败不中断主流程（与 GameEngineAtomicAssign 一致）
    @Suppress("TooGenericExceptionCaught")
    try {
        discipleFacade.syncAllDiscipleStatuses()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.w("GameEngine", "startSecretRealm: syncAllDiscipleStatuses 失败", e)
    }
}

/** native 路径（AUTHORITATIVE）：C++ 结算 + 战报重建 + 事务外哀伤/gate。失败返回 null 回退。 */
internal suspend fun GameEngine.chooseSecretRealmNative(
    optionIndex: Int
): SecretRealmChoiceResult? {
    val engine = this
    val native = SecretRealmNativeForward.tryForward(
        engine, ActionIds.SECRET_REALM_CHOOSE
    ) {
        put("optionIndex", optionIndex)
    } as? JsonObject ?: return null

    val battleObj = native["battle"] as? JsonObject
    val combatLog = if (battleObj != null &&
        battleObj["hasBattle"]?.jsonPrimitive?.contentOrNull == "true"
    ) {
        recordSecretRealmBattleReport(battleObj)
    } else {
        null
    }
    return SecretRealmChoiceResult.Success(
        message = native["message"]?.jsonPrimitive?.contentOrNull ?: "",
        sessionEnded = native["sessionEnded"]?.jsonPrimitive?.booleanOrNull ?: false,
        enteredCombat = native["enteredCombat"]?.jsonPrimitive?.booleanOrNull ?: false,
        combatLog = combatLog,
        victory = native["victory"]?.jsonPrimitive?.booleanOrNull ?: false,
        deadIds = native.stringSet("deadIds"),
        releasedMemberIds = native.stringSet("releasedMemberIds"),
        ambushSucceeded = (native["params"] as? JsonObject)
            ?.get("ambushSucceeded")?.jsonPrimitive?.booleanOrNull ?: false
    ).also { applySecretRealmChoiceSideEffects(it) }
}

/** 选择成功后的公共事务外收尾（死亡哀伤 + 自动结束 gate 释放 + 状态同步）。 */
internal suspend fun GameEngine.applySecretRealmChoiceSideEffects(
    result: SecretRealmChoiceResult.Success
) {
    if (result.deadIds.isNotEmpty()) {
        // log-and-continue：状态同步失败不中断主流程（与 GameEngineAtomicAssign 一致）
        @Suppress("TooGenericExceptionCaught")
        try {
            combatService.processBattleCasualties(
                result.deadIds, emptyMap(), emptyMap(), isOutsideSect = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.w("GameEngine", "秘境战斗伤亡处理失败 deadIds=${result.deadIds}", e)
        }
    }
    // 自动结束（体力耗尽/全灭）：会话已清空，此处释放 gate 占用防弟子卡死
    // （仅手动结束的路径不经过此处）
    if (result.sessionEnded && result.releasedMemberIds.isNotEmpty()) {
        result.releasedMemberIds.forEach { assignmentGate.release(it) }
        @Suppress("TooGenericExceptionCaught")
        try {
            discipleFacade.syncAllDiscipleStatuses()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.w("GameEngine", "chooseSecretRealmOption 自动结束释放失败", e)
        }
    }
}

/** 战报重建（展示通道）：C++ 终态信封 → BattleLogData + recordPlayerBattle 写入。 */
internal suspend fun GameEngine.recordSecretRealmBattleReport(
    battleObj: JsonObject
): BattleLogData {
    val combatLog = BattleExecutionRouter.rebuildBattleLogData(battleObj)
    val victory = battleObj["winner"]?.jsonPrimitive?.contentOrNull == "TEAM"
    val battleType = if (battleObj["type"]?.jsonPrimitive?.contentOrNull == "PVP") {
        BattleType.PVP
    } else {
        BattleType.PVE
    }
    stateStore.update {
        recordPlayerBattle(
            year = gameData.gameYear,
            month = gameData.gameMonth,
            type = battleType,
            attackerName = "玩家探索队伍",
            defenderName = battleObj["defenderName"]?.jsonPrimitive?.contentOrNull ?: "",
            result = if (victory) BattleResult.WIN else BattleResult.LOSE,
            teamMembers = combatLog.teamMembers.map { m ->
                BattleLogMember(
                    id = m.id, name = m.name, realm = m.realm,
                    realmName = m.realmName, hp = m.hp, maxHp = m.maxHp,
                    mp = m.mp, maxMp = m.maxMp, isAlive = m.isAlive,
                    portraitRes = m.portraitRes
                )
            },
            enemies = combatLog.enemies.map { b ->
                BattleLogEnemy(
                    id = b.id, name = b.name, realm = b.realm,
                    realmName = b.realmName, hp = b.hp, maxHp = b.maxHp,
                    isAlive = b.isAlive, portraitRes = b.portraitRes
                )
            },
            rounds = combatLog.rounds.map { r ->
                BattleLogRound(
                    roundNumber = r.roundNumber,
                    actions = r.actions.map { a ->
                        BattleLogAction(
                            type = a.type, attacker = a.attacker,
                            attackerType = a.attackerType, target = a.target,
                            damage = a.damage, damageType = a.damageType,
                            isCrit = a.isCrit, isKill = a.isKill,
                            message = a.message
                        )
                    }
                )
            },
            turns = battleObj["turn"]?.jsonPrimitive?.long?.toInt() ?: 0,
            details = battleObj["details"]?.jsonPrimitive?.contentOrNull ?: "",
            beastsDefeated = battleObj["beastsDefeated"]?.jsonPrimitive?.long?.toInt() ?: 0,
            teamCasualties = battleObj["teamCasualties"]?.jsonPrimitive?.long?.toInt() ?: 0
        )
    }
    return combatLog
}

/** JsonObject 字符串数组字段 → Set<String>（缺失返回空集）。 */
internal fun JsonObject.stringSet(name: String): Set<String> =
    (this[name] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()
