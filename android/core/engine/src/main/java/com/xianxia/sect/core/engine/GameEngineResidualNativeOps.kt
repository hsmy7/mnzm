@file:Suppress("MatchingDeclarationName")  // 文件持残余域 native 转发器对象（batch-23 聚合，S6/batch-13 native 域同构）

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// GameEngineResidualNativeOps.kt — 残余域 native 转发域（batch-23）
//
// ResidualNativeForward 转发器 + 两处 UI 操作面 native 臂（妖兽视图锁定 /
// 设置项字段补丁）。主入口见 GameEngine.kt（lockBeastView/unlockBeastView）
// 与 GameEngineSettingsOps.kt（updateSettings*）；Kotlin 原路径整体保留为
// 降级回退臂（双实现并行契约，README §7.3——删除属 batch-21 终局批）。
//
// 契约（与 PatrolNativeForward / SectAttackNativeForward 同构）：
//  - AUTHORITATIVE 门控：非 AUTHORITATIVE 一律返回 null 走 Kotlin
//  - 镜像服务可空局部守卫（handover findings 13：测试 mock 未 stub
//    stateSyncServiceRef 时返回 null，不得 NPE）
//  - native 失败信封 → tryExecuteNative 返回 null → Kotlin 回退臂重执行
//    原路径（设置项域失败语义 = 未知字段/类型不符，两臂均零写入）
//  - native 成功 → 调用方**只执行事务外残差**（道侣提议清理等），
//    不重复执行 Kotlin 事务体

/**
 * 残余域 native 转发器（AUTHORITATIVE 稳态写者归 C++——妖兽锁定集合与
 * 设置项字段；Kotlin 侧仅保留运行态/平台效应残差）。
 *
 * 降级契约：flag 非 AUTHORITATIVE / 镜像服务缺失 / native 失败信封
 * → null，调用方回退 Kotlin 原实现。
 */
internal object ResidualNativeForward {

    /** 尝试经 C++ 执行残余域事务；成功返回 data，降级返回 null。 */
    @Suppress("ReturnCount")  // 多 return 为降级契约（flag 关/镜像不可用/失败信封逐级返回）
    internal fun tryForward(
        gameEngine: GameEngine,
        actionId: Int,
        paramsBuilder: JsonObjectBuilder.() -> Unit
    ): JsonElement? {
        if (!NativeEngineFlag.authoritative) return null
        // 防御性空安全：测试 mock（未 stub stateSyncServiceRef）返回 null——
        // 先赋可空局部再判空（handover findings 13，与 PatrolNativeForward 同守卫）
        val sync: StateSyncService? = gameEngine.stateSyncService
        if (sync == null) return null
        return GameEngineNativeOps.tryExecuteNative(
            stateSyncService = sync,
            actionId = actionId,
            paramsJson = params(paramsBuilder)
        )
    }
}

// ── native 臂：妖兽视图锁定（主入口 GameEngine.kt）───────────────────────

/**
 * 妖兽视图锁定/解锁 native 臂：成功返回 true（C++ 已写 lockedBeastIds =
 * NativeGameState 顶层段），降级返回 false 走 Kotlin 原实现。
 *
 * 空 beastId 直接返回 false（与 Kotlin 解锁分支的空 id 早退同口径：
 * 零差异写，避免一次无收益的跨语言往返）。
 */
internal fun GameEngine.lockBeastViewNative(beastId: String, locked: Boolean): Boolean {
    if (beastId.isEmpty()) return false
    val reply = ResidualNativeForward.tryForward(this, ActionIds.BEAST_VIEW_LOCK_TX) {
        put("beastId", beastId)
        put("locked", locked)
    }
    return reply != null
}

// ── native 臂：设置项字段补丁（主入口 GameEngineSettingsOps.kt）──────────

/** 设置补丁条目值：布尔开关或 Int 集（灵根数白名单/过滤器类字段） */
internal sealed interface SettingPatchValue {
    /** 布尔开关字段 */
    data class Flag(val value: Boolean) : SettingPatchValue

    /** Int 集字段（Kotlin 侧 Set<Int> —— 协议面为 JSON 整数数组） */
    data class IntSet(val values: Set<Int>) : SettingPatchValue
}

/**
 * 设置项字段补丁 native 臂：成功返回 true（C++ 已写对应 gameData 字段），
 * 降级返回 false 走 Kotlin 原实现（`updateGameData`）。
 *
 * 信封字段面（与 C++ `handleLockBeastTx` SETTINGS_PATCH_TX 分支逐字对应）：
 * bool 开关 → JSON 布尔；Int 集字段 → JSON 整数数组。
 *
 * @param entries 本次实际改动的字段（字段名 → 值）；empty 时不发起调用
 *        （零收益往返）
 */
internal fun GameEngine.updateSettingsNative(entries: List<Pair<String, SettingPatchValue>>): Boolean {
    if (entries.isEmpty()) return false
    val reply = ResidualNativeForward.tryForward(this, ActionIds.SETTINGS_PATCH_TX) {
        put("patch", buildPatchArray(entries))
    }
    return reply != null
}

/** 设置补丁数组构造（bool → 布尔标量；Set<Int> → 升序整数数组） */
internal fun buildPatchArray(entries: List<Pair<String, SettingPatchValue>>): JsonArray =
    JsonArray(entries.map { (field, value) -> patchEntry(field, value) })

private fun patchEntry(field: String, value: SettingPatchValue): JsonElement =
    buildJsonObject {
        put("field", field)
        when (value) {
            is SettingPatchValue.Flag -> put("value", value.value)
            is SettingPatchValue.IntSet ->
                put("value", JsonArray(value.values.sorted().map { JsonPrimitive(it) }))
        }
    }

// ── native 臂：洗炼 confirm 两入口（主入口 GameEngineSpiritRootOps /
//    GameEngineTraitWashOps —— batch-24）────────────────────────────────
//
// 这两入口的**用户可见文案**（弟子不存在 / 弟子已死亡 / 该特质已不存在）
// 与 C++ 判定链同源——故走 executeRaw 保留失败信封（tryExecuteNative 会把
// 失败降级为 null，丢失分型）。三态返回：
//   - 成功信封 data → ConfirmNativeOutcome.Applied（C++ 已落盘 + 镜像已回读）
//   - 业务拒绝 → ConfirmNativeOutcome.Refused(code, message)
//   - 降级（flag/桥/异常）→ ConfirmNativeOutcome.Unavailable（走 Kotlin 回退臂）

/** 洗炼 confirm native 臂结果三态 */
internal sealed interface ConfirmNativeOutcome {
    /** native 成功：C++ 已落盘（含 checkpoint 与 lifespan 同步） */
    data object Applied : ConfirmNativeOutcome

    /** native 业务拒绝：携带 C++ errorType 与文案（调用方直接映射为用户可见错误） */
    data class Refused(val code: String, val message: String) : ConfirmNativeOutcome

    /** native 不可用（flag 关闭 / 桥未加载 / 异常）→ 调用方回退 Kotlin 原路径 */
    data object Unavailable : ConfirmNativeOutcome
}

@Suppress("ReturnCount")  // 多 return 为降级契约（flag 关/镜像缺失/失败信封逐级返回）
private fun GameEngine.confirmForward(
    actionId: Int,
    paramsBuilder: JsonObjectBuilder.() -> Unit
): ConfirmNativeOutcome {
    if (!NativeEngineFlag.authoritative) return ConfirmNativeOutcome.Unavailable
    // 防御性空安全：测试 mock（未 stub stateSyncServiceRef）返回 null——
    // 先赋可空局部再判空（handover findings 13）
    val sync: StateSyncService? = stateSyncService ?: return ConfirmNativeOutcome.Unavailable
    val result = GameEngineNativeOps.executeRaw(
        stateSyncService = sync,
        actionId = actionId,
        paramsJson = params(paramsBuilder)
    )
    return when {
        result.data != null -> ConfirmNativeOutcome.Applied
        result.refusal != null -> ConfirmNativeOutcome.Refused(
            result.refusal.code, result.refusal.message
        )
        else -> ConfirmNativeOutcome.Unavailable
    }
}

/**
 * 洗炼灵根确认替换 native 臂（GameEngineSpiritRootOps.confirmSpiritRootWash）。
 *
 * 注意：非法灵根串的**预检在 Kotlin 侧先行**（`isValidWashedRootType` 为纯函数，
 * 与 C++ 同源校验），故 native 臂只在预检通过后进入——零差异往返。
 */
internal fun GameEngine.confirmSpiritRootWashNative(
    discipleId: String,
    newRootType: String
): ConfirmNativeOutcome = confirmForward(ActionIds.SPIRIT_ROOT_WASH_CONFIRM_TX) {
    put("discipleId", discipleId)
    put("newRootType", newRootType)
}

/** 特质单槽确认替换 native 臂（GameEngineTraitWashOps.confirmTraitWash）。 */
internal fun GameEngine.confirmTraitWashNative(
    discipleId: String,
    type: String,
    targetId: String,
    newId: String
): ConfirmNativeOutcome = confirmForward(ActionIds.TRAIT_WASH_CONFIRM_TX) {
    put("discipleId", discipleId)
    put("type", type)
    put("targetId", targetId)
    put("newId", newId)
}
