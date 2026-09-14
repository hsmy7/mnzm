package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.system.materializeBagItemsToWarehouse
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.str
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

private val TAG = DiscipleFacadeImpl.TAG

/** 逐出袋物品物化的年报归因口径（与 Kotlin 原路径逐字一致） */
private const val EXPEL_TRACKING_SOURCE = "disciple_expel"

/** 信封 bagItems 草稿反序列化（快照协议同源字段，未知字段忽略——宽松对齐 from_json） */
private val envelopeJson = Json { ignoreUnknownKeys = true }

/**
 * 弟子生命周期域 native 事务转发臂（batch-14 写者下沉；InventoryNativeTx/
 * tryNativeManualRecruit 同构）。
 *
 * AUTHORITATIVE 门控下把生命周期族五事务（disciple_lifecycle_tx.h——校验链
 * 先行失败零写入）经 nativeExecute 转发；tryExecuteNative 成功内含
 * applyDirtyFromNative 镜像回读；失败信封/降级返回 null（调用方回退 Kotlin
 * 原路径重执行校验链——双实现并行契约，用户可见文案由 Kotlin 臂产出）。
 *
 * 残差边界（ disciple_lifecycle_tx.h 头注释同口径）：
 * - Gate 释放 + Room 生产槽同步：Kotlin 分支在 native 成功后执行（幂等，
 *   数据段在已刷新镜像上为零写入）
 * - 袋物品物化（开袋族，归 batch-11）：信封 bagItems 草稿 → Kotlin 原序物化
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

// ── 事务 1：逐出（1590） ────────────────────────────────────────

/**
 * 逐出 native 臂。
 *
 * C++ 事务：校验链（存在/存活/非血炼）+ 12 类槽位清理（含住所）+ 实例销毁
 * + 派生 map 收口 + 行删除 + 年报脱离计数。Kotlin 残差（native 成功后原序）：
 * Gate 释放 + Room 生产槽同步、袋物品物化回仓库（溢出转邮件，归因
 * "disciple_expel"）。
 *
 * @return native 已处理时返回 Success；未转发/失败信封返回 null
 *         （调用方回退 Kotlin 原路径）
 */
internal fun DiscipleFacadeImpl.tryNativeExpelDisciple(discipleId: String): DomainResult<Unit>? {
    val data = lifecycleTx(ActionIds.DISCIPLE_LIFECYCLE_EXPEL) {
        put("discipleId", discipleId)
    } ?: return null

    // ① Gate 释放 + Room 生产槽同步（clearAllSlotsState 数据段在已刷新
    //    镜像上零命中——幂等 no-op；Gate/Room 为 Kotlin 运行态域）
    discipleService.clearDiscipleFromAllSlots(discipleId)

    // ② 袋物品物化（信封草稿 → Kotlin 物化回仓库；与 Kotlin 原路径同口径：
    //    withTrackingSource 归因 + 溢出自动转邮件）
    val bagItems = parseBagItemDrafts(data)
    if (bagItems.isNotEmpty()) {
        val inventorySystem = discipleService.inventorySystem
        inventorySystem.withTrackingSource(EXPEL_TRACKING_SOURCE) {
            inventorySystem.materializeBagItemsToWarehouse(bagItems)
        }
    }
    DomainLog.i(TAG, "expelDisciple: native expelled $discipleId (bag=${bagItems.size})")
    return DomainResult.Success(Unit)
}

/** 信封 bagItems 草稿解析（缺字段/非数组按空列表）。 */
private fun parseBagItemDrafts(data: JsonElement): List<StorageBagItem> {
    val element = (data as? kotlinx.serialization.json.JsonObject)?.get("bagItems")
        ?: return emptyList()
    return runCatching {
        envelopeJson.decodeFromJsonElement(
            ListSerializer(StorageBagItem.serializer()), element
        )
    }.getOrDefault(emptyList())
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

// ── 事务 4：释放思过（1593） ────────────────────────────────────

/**
 * 释放思过 native 臂。
 *
 * C++ 事务：statusData 思过双键定向移除 + 状态回 IDLE；静默 no-op 分支
 * （解析失败/不存在/已死亡）与 Kotlin 早退同义。状态推导
 * （syncSingleDiscipleStatus）为 Kotlin 运行态域，调用方照原序执行。
 *
 * @return true=已清标记；false=静默 no-op（Kotlin 照原序继续 sync）；
 *         null=未转发/失败信封（调用方回退 Kotlin 原路径）
 */
internal fun DiscipleFacadeImpl.tryNativeReleaseReflection(discipleId: String): Boolean? {
    val data = lifecycleTx(ActionIds.DISCIPLE_LIFECYCLE_RELEASE_REFLECTION) {
        put("discipleId", discipleId)
    } ?: return null
    val written = data.str("written") == "true"
    if (written) {
        DomainLog.i(TAG, "releaseReflectionDisciple: native released $discipleId")
    }
    return written
}

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
