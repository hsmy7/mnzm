package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.str
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

private val TAG = DiscipleFacadeImpl.TAG

/**
 * 弟子生命周期域 native 事务转发臂（batch-14 写者下沉；InventoryNativeTx/
 * tryNativeManualRecruit 同构）。
 *
 * AUTHORITATIVE 门控下把生命周期族事务（disciple_lifecycle_tx.h——校验链
 * 先行失败零写入）经 nativeExecute 转发；tryExecuteNative 成功内含
 * applyDirtyFromNative 镜像回读；失败信封/降级返回 null（调用方回退 Kotlin
 * 原路径重执行校验链——双实现并行契约，用户可见文案由 Kotlin 臂产出）。
 *
 * 残差边界（disciple_lifecycle_tx.h 头注释同口径）：
 * - lifeEvents 瞬态列：拜师双侧日志草稿 → Kotlin 回写
 */
// ── 内部转发 helper ─────────────────────────────────────────────

/** native 事务转发：AUTHORITATIVE 门控 + tryExecuteNative；失败信封/降级返回 null。 */
private fun DiscipleFacadeImpl.lifecycleTx(
    actionId: Int,
    build: JsonObjectBuilder.() -> Unit
): JsonElement? =
    if (!NativeEngineFlag.authoritative) {
        null
    } else {
        // 防御性空安全：生产恒非空，但测试 mock（未 stub stateSyncServiceRef）
        // 返回 null——非空声明的内在检查会在调用点即抛 NPE，须先经可空局部过滤
        val sync: StateSyncService? = gameEngineCore.stateSyncServiceRef
        if (sync == null) {
            null
        } else {
            GameEngineNativeOps.tryExecuteNative(
                stateSyncService = sync,
                actionId = actionId,
                paramsJson = params(build)
            )
        }
    }

// ── 事务 2：拜师（1591） ────────────────────────────────────────

/**
 * 拜师 native 臂。
 *
 * C++ 事务：三相校验 + masterIds 落表；信封附徒/师双侧日志草稿，
 * Kotlin 回写 lifeEvents 瞬态列（与 Kotlin bindApprenticeToMaster 同生命周期）。
 *
 * @return native 已处理时返回 Success；未转发/失败信封返回 null
 */
internal fun DiscipleFacadeImpl.tryNativeApprenticeToMaster(
    discipleId: String,
    masterId: String
): DomainResult<Unit>? {
    val data = lifecycleTx(ActionIds.DISCIPLE_LIFECYCLE_APPRENTICE) {
        put("discipleId", discipleId)
        put("masterId", masterId)
    } ?: return null

    stateStore.update {
        appendLifeEventDraft(discipleId, data.str("apprenticeLogLine"))
        appendLifeEventDraft(masterId, data.str("masterLogLine"))
    }
    DomainLog.i(TAG, "apprenticeToMaster: native bound $discipleId -> $masterId")
    return DomainResult.Success(Unit)
}

/** lifeEvents 草稿回写（镜像滞后窗口/空草稿跳过——mirrorAppendJoinSectLifeEvent 同款防御）。 */
private fun MutableGameState.appendLifeEventDraft(discipleId: String, logLine: String?) {
    if (logLine.isNullOrEmpty()) return
    val intId = discipleId.toIntOrNull() ?: return
    if (intId !in discipleTables.ids) return
    val events = discipleTables.lifeEvents.getOrDefault(intId, emptyList())
    discipleTables.lifeEvents[intId] = events + logLine
}

// ── 事务 4：释放思过（1593 已退役，编号禁复用）────────────────────
// C++ 侧实现与派发已随思过系统下线删除；调用方（DiscipleFacadeImpl.releaseReflectionDisciple）
// 直走 Kotlin 原路径（旧档 REFLECTING 归一化），不再转发。

// ── 事务 5：年俸开关（1594） ────────────────────────────────────

/**
 * 境界年俸开关 native 臂。
 *
 * C++ 事务：yearlySalaryEnabled[realm] 盲写覆写（与 Kotlin 原路径同义无校验）。
 *
 * @return native 已处理时返回 true；未转发/失败信封返回 null
 */
internal fun DiscipleFacadeImpl.tryNativeSalaryToggle(realm: Int, enabled: Boolean): Boolean? {
    val data = lifecycleTx(ActionIds.DISCIPLE_LIFECYCLE_SALARY_TOGGLE) {
        put("realm", realm)
        put("enabled", enabled)
    } ?: return null
    DomainLog.i(TAG, "updateYearlySalaryEnabled: native toggled realm=$realm enabled=$enabled")
    return data.str("toggled") == "true"
}
