package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.monitor.GameTimeProgressMonitor
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmChoiceResult
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmEndReason
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import kotlin.coroutines.cancellation.CancellationException

// GameEngineSecretRealmOps.kt — 远古秘境玩法 GameEngine 扩展入口
// （对照 GameEngineAtomicAssign.kt 的原子事务 + gate 模式）

/** 秘境队伍占用的 gate 槽位类型名（复用 EXPLORATION_TEAM 分类，非持久化） */
private const val SECRET_REALM_SLOT_TYPE = "secret_realm"
/** 秘境队伍占用的 gate 槽位 ID */
private const val SECRET_REALM_SLOT_ID = "secret_realm_session"

/**
 * 出发探索：校验（满 4 人/存活/空闲）→ 写会话（含初始妖兽事件）→ gate 占用队伍成员。
 */
@Suppress("TooGenericExceptionCaught")
suspend fun GameEngine.startSecretRealmExploration(
    memberIds: List<String>
): DomainResult<Unit> = engineContextDispatcher.withEngineContext {
    val result: DomainResult<Unit> = try {
        stateStore.updateAndReturn {
            secretRealmService.startSession(memberIds, this)
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
 * @return true 表示可继续探索
 */
suspend fun GameEngine.continueSecretRealmExploration(): Boolean =
    engineContextDispatcher.withEngineContext {
        val data = stateStore.gameDataSnapshot
        val session = data.secretRealmSession
        if (!session.isActive || !data.secretRealmState.exists ||
            session.secretRealmId != data.secretRealmState.id
        ) {
            // 残留会话死局防御：秘境不存在/不匹配时结算清空，避免永久无法再探索
            // （对抗性审查 B7）
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
        // （对抗性审查 S3：scanAndRegister 不扫秘境会话，须在此补 confirmAssign）
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
    val result = stateStore.updateAndReturn {
        secretRealmService.chooseOption(optionIndex, this)
    }
    if (result is SecretRealmChoiceResult.Success) {
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
        // （对抗性审查 S2：此前仅手动结束释放，自动结束路径泄漏）
        if (result.sessionEnded && result.releasedMemberIds.isNotEmpty()) {
            result.releasedMemberIds.forEach { assignmentGate.release(it) }
            // log-and-continue：状态同步失败不中断主流程（与 GameEngineAtomicAssign 一致）
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
    result
}

/**
 * 主动结束探索：结算背包 → 秘境消失 + 冷却 → 释放队伍成员占用。
 */
suspend fun GameEngine.endSecretRealmExploration() = engineContextDispatcher.withEngineContext {
    // 释放全部成员 gate（含陨落成员——与自动结束路径 releasedMemberIds 一致，
    // 防战斗死亡弟子 gate 残留；对抗性审查 B-L4）
    val memberIds = stateStore.gameDataSnapshot.secretRealmSession.members
        .map { it.discipleId }
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
