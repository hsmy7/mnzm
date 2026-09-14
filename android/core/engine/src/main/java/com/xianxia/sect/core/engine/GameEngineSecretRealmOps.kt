package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.monitor.GameTimeProgressMonitor
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmChoiceResult
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmEndReason
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

// GameEngineSecretRealmOps.kt — 远古秘境玩法 GameEngine 扩展入口
// （对照 GameEngineAtomicAssign.kt 的原子事务 + gate 模式）

/** 秘境队伍占用的 gate 槽位类型名（复用 EXPLORATION_TEAM 分类，非持久化） */
private const val SECRET_REALM_SLOT_TYPE = "secret_realm"
/** 秘境队伍占用的 gate 槽位 ID */
private const val SECRET_REALM_SLOT_ID = "secret_realm_session"

/**
 * 秘境会话域 native 转发（SECRET_REALM_START/CHOOSE/END 经
 * nativeExecute + tryExecuteNative 交互域
 * 转发，Kotlin 回退保留——双实现并行契约）。
 *
 * 契约（与 InventoryNativeForward 同族）：
 * - 仅 AUTHORITATIVE 模式转发；flag 关闭 / native 不可用 / 失败信封 → null，
 *   调用方回退 Kotlin 原实现（校验失败回退即 Kotlin 侧同语义拒绝）。
 * - C++ 状态已变更并镜像回写；溢出邮件草稿经 [deliverOverflowDrafts] 投递。
 */
/**
 * 出发探索：校验（满 4 人/存活/空闲）→ 写会话（含初始妖兽事件）→ gate 占用队伍成员。
 * AUTHORITATIVE 模式经 C++ startSession（校验 + 会话写入 + 初始事件），
 * Kotlin 补平台段（换岗清理/gate/Room 清槽/状态同步）；回退路径语义不变。
 */
@Suppress("TooGenericExceptionCaught")
suspend fun GameEngine.startSecretRealmExploration(
    memberIds: List<String>
): DomainResult<Unit> = engineContextDispatcher.withEngineContext {
    // 入口防御前置（C++ startSession 无到期判定——保持 Kotlin 拒绝语义）
    rejectIfSecretRealmExpired()?.let { return@withEngineContext it }
    val native = SecretRealmNativeForward.tryForward(
        this@startSecretRealmExploration, ActionIds.SECRET_REALM_START
    ) {
        put("memberIds", JsonArray(memberIds.map { JsonPrimitive(it) }))
    }
    if (native != null) {
        // C++ 会话已写入并镜像——补换岗清理段（1800 native 臂接管
        // releaseDiscipleToIdleInside 的 GameData 槽位/状态段；降级回退 Kotlin
        // 原路径；gate/Room 清槽仍由 finalizeSecretRealmTeam 收尾）
        if (!secretRealmStartReleaseNative(memberIds)) {
            stateStore.update {
                memberIds.forEach { releaseDiscipleToIdleInside(this, it) }
            }
        }
        finalizeSecretRealmTeam(memberIds)
        return@withEngineContext DomainResult.Success(Unit)
    }
    val result: DomainResult<Unit> = try {
        stateStore.updateAndReturn {
            // 入口防御：秘境已现世期满（月结被绕过的极端兜底）→ 关闭并拒绝出发
            val realm = gameData.secretRealmState
            if (realm.exists && gameData.gameYear >=
                realm.spawnYear + GameConfig.SecretRealm.OPEN_YEARS
            ) {
                secretRealmService.closeSecretRealmByExpiry(this)
                return@updateAndReturn DomainResult.Failure(
                    AppError.Domain.GameState.NotFound("远古秘境已关闭")
                )
            }
            // 先校验再清理：startSession 校验失败返回 Failure（不抛异常），若先清理
            // 则事务照常提交——队员岗位已被清空但 gate 未清（失败路径不得销毁分配）。
            // 校验通过后换岗清理（出发即换岗——防止同一弟子同时出现在岗位与秘境队伍）
            val sessionResult = secretRealmService.startSession(memberIds, this)
            if (sessionResult is DomainResult.Success) {
                memberIds.forEach { releaseDiscipleToIdleInside(this, it) }
            }
            sessionResult
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 异常转 DomainResult.Failure（不吞异常，语义化返回）
        @Suppress("TooGenericExceptionCaught")
        run {
            DomainLog.e("GameEngine", "startSecretRealmExploration 失败", e)
        }
        DomainResult.Failure(AppError.Domain.GameLoop.Unknown("出发远古秘境失败"))
    }
    if (result is DomainResult.Success) {
        finalizeSecretRealmTeam(memberIds)
    }
    result
}


/**
 * 一键任命：空闲弟子按境界优先（realm 数值小 = 境界高）选出 4 人，返回供 UI 填槽。
 * 不包含已在探索会话中的成员。
 */
suspend fun GameEngine.autoAssignSecretRealmTeam(): List<String> =
    engineContextDispatcher.withEngineContext {
        val data = stateStore.gameDataSnapshot
        val existingIds = data.secretRealmSession.members
            .filter { !it.isDead }
            .map { it.discipleId }
            .toSet()
        val all = stateStore.discipleTables.assembleAll()
        all.filter { it.isAlive && it.status == DiscipleStatus.IDLE && it.id !in existingIds }
            .sortedWith(compareBy<Disciple> { it.realm }
                .thenByDescending { it.realmLayer })
            .take(GameConfig.SecretRealm.TEAM_SIZE)
            .map { it.id }
    }

/**
 * 继续探索（读档后）：校验会话有效并净化已死亡/不存在的成员；
 * 成员净化后为空则自动结算结束。
 *
 * AUTHORITATIVE 模式经 C++ continueSessionTx（到期关闭/死局重置/成员净化，
 * 零 RNG——batch-20a 下沉），Kotlin 补平台段（gate 释放/占用 + 关闭邮件 +
 * 溢出草稿投递）；回退路径语义不变。
 *
 * @return true 表示可继续探索
 */
suspend fun GameEngine.continueSecretRealmExploration(): Boolean =
    engineContextDispatcher.withEngineContext {
        // AUTHORITATIVE 转发——C++ 会话域判定段 + 镜像回写
        continueSecretRealmNative()?.let { return@withEngineContext it }
        val data = stateStore.gameDataSnapshot
        val session = data.secretRealmSession
        // 入口防御：秘境已现世期满（月结被绕过的极端兜底）→ 关闭会话并拒绝继续
        if (data.secretRealmState.exists && data.gameYear >=
            data.secretRealmState.spawnYear + GameConfig.SecretRealm.OPEN_YEARS
        ) {
            stateStore.update { secretRealmService.closeSecretRealmByExpiry(this) }
            return@withEngineContext false
        }
        if (!session.isActive || !data.secretRealmState.exists ||
            session.secretRealmId != data.secretRealmState.id
        ) {
            // 残留会话死局防御：秘境不存在/不匹配时结算清空，避免永久无法再探索
            if (session.isActive) {
                stateStore.update { secretRealmService.endSession(this) }
            }
            return@withEngineContext false
        }
        // 净化：移除已永久死亡/已不存在的成员
        val aliveIds = stateStore.discipleTables.assembleAll()
            .filter { it.isAlive }.map { it.id }.toSet()
        val validMembers = session.members.filter { !it.isDead && it.discipleId in aliveIds }
        if (validMembers.isEmpty()) {
            stateStore.update { secretRealmService.endSession(this) }
            return@withEngineContext false
        }
        if (validMembers.size != session.members.size) {
            stateStore.update {
                gameData = gameData.copy(
                    secretRealmSession = session.copy(members = validMembers)
                )
            }
        }
        // 读档后 gate 为空：重新占用成员，防被分配他职造成分身
        // （scanAndRegister 不扫秘境会话，须在此补 confirmAssign）
        validMembers.forEach { member ->
            assignmentGate.confirmAssign(
                member.discipleId,
                SlotRef(SlotCategory.EXPLORATION_TEAM, SECRET_REALM_SLOT_TYPE, SECRET_REALM_SLOT_ID)
            )
        }
        true
    }

/**
 * 选择事件选项：事务内结算（体力/战斗/掉落/损失/濒死/死亡）→ 事务外触发死亡哀伤。
 */
suspend fun GameEngine.chooseSecretRealmOption(
    optionIndex: Int
): SecretRealmChoiceResult = engineContextDispatcher.withEngineContext {
    // AUTHORITATIVE 转发——C++ 结算（体力/战斗/掉落/损失/濒死/死亡）
    // + 战报经 recordPlayerBattle 在 Kotlin 重建（展示通道非协议）
    chooseSecretRealmNative(optionIndex)?.let { return@withEngineContext it }
    val result = stateStore.updateAndReturn {
        secretRealmService.chooseOption(optionIndex, this)
    }
    if (result is SecretRealmChoiceResult.Success) {
        applySecretRealmChoiceSideEffects(result)
    }
    result
}


/**
 * 主动结束探索：结算背包 → 秘境消失 + 冷却 → 释放队伍成员占用。
 */
suspend fun GameEngine.endSecretRealmExploration() = engineContextDispatcher.withEngineContext {
    // 释放全部成员 gate（含陨落成员——与自动结束路径 releasedMemberIds 一致，
    // 防战斗死亡弟子 gate 残留）
    val memberIds = stateStore.gameDataSnapshot.secretRealmSession.members
        .map { it.discipleId }
    // AUTHORITATIVE 转发——C++ 结算背包入仓 + 秘境清场；gate 释放保留 Kotlin
    val native = SecretRealmNativeForward.tryForward(this@endSecretRealmExploration, ActionIds.SECRET_REALM_END) {
        put("reason", "EXPLORER_END")
    }
    if (native != null) {
        memberIds.forEach { assignmentGate.release(it) }
        @Suppress("TooGenericExceptionCaught")
        try {
            discipleFacade.syncAllDiscipleStatuses()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.w("GameEngine", "endSecretRealm: syncAllDiscipleStatuses 失败", e)
        }
        return@withEngineContext
    }
    stateStore.update {
        secretRealmService.endSession(this, SecretRealmEndReason.EXPLORER_END)
    }
    memberIds.forEach { assignmentGate.release(it) }
    // log-and-continue：状态同步失败不中断主流程（与 GameEngineAtomicAssign 一致）
    @Suppress("TooGenericExceptionCaught")
    try {
        discipleFacade.syncAllDiscipleStatuses()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.w("GameEngine", "endSecretRealm: syncAllDiscipleStatuses 失败", e)
    }
}

/**
 * 进入探索界面：暂停游戏时间（由秘境持有暂停锁，退出时自动恢复）。
 */
suspend fun GameEngine.pauseForSecretRealm() = engineContextDispatcher.withEngineContext {
    gameEngineCore.pauseForSecretRealm()
}

/**
 * 退出探索界面：若暂停由秘境持有则恢复游戏时间。
 */
suspend fun GameEngine.resumeFromSecretRealm() = engineContextDispatcher.withEngineContext {
    gameEngineCore.resumeFromSecretRealm()
}

/**
 * 续约秘境暂停租约：由探索界面每 [GameEngineCore.SECRET_REALM_RENEW_INTERVAL_MS]
 * 调用一次，证明界面仍打开中。续约中断超过 [GameTimeProgressMonitor.STALE_PAUSE_TTL_MS]
 * 后看门狗判定锁残留并自愈（消除 Activity 重建导致 exitExploration 丢失的永久冻结路径）。
 */
suspend fun GameEngine.renewSecretRealmPauseLease() = engineContextDispatcher.withEngineContext {
    gameEngineCore.renewSecretRealmPauseLease()
}
