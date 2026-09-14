@file:Suppress("MatchingDeclarationName")  // 文件持巡逻/住所域 native 转发器对象（batch-12 聚合，S6/batch-13 native 域同构）

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.PatrolConfig
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// GameEnginePatrolNativeOps.kt — 巡逻/住所/矿场/年俸域 native 转发域（batch-12）
//
// PatrolNativeForward 转发器 + 九个 UI 操作面入口的 native 臂。
// 主入口见 GameEngineAtomicAssign.kt（住所 2 + 巡逻 4）与
// GameEnginePatrolOps.kt（配置/矿场/年俸 4）；Kotlin 原路径整体保留为
// 降级回退臂（双实现并行契约，README §7.3——删除属 batch-21 终局批）。
//
// 契约（与 ExplorationNativeForward / SecretRealmNativeForward 同构）：
//  - AUTHORITATIVE 门控：非 AUTHORITATIVE 一律返回 null 走 Kotlin
//  - 镜像服务可空局部守卫（handover findings 13：测试 mock 未 stub
//    stateSyncServiceRef 时返回 null，不得 NPE）
//  - native 失败信封 → tryExecuteNative 返回 null → Kotlin 回退臂重执行
//    校验链并产出用户可见文案（C++ message 仅诊断）
//  - native 成功 → 调用方**只执行事务外残差**（assignmentGate / Room
//    生产槽 Repository / 弟子状态同步），不重复执行 Kotlin 事务体

/**
 * 巡逻/住所域 native 转发器（AUTHORITATIVE 稳态写者归 C++——判定链与
 * 槽位写；gate 登记/释放、Room 生产槽清理、弟子状态同步留 Kotlin）。
 *
 * 降级契约：flag 非 AUTHORITATIVE / 镜像服务缺失 / native 失败信封
 * → null，调用方回退 Kotlin 原实现。
 */
internal object PatrolNativeForward {

    /** 尝试经 C++ 执行巡逻域事务；成功返回 data，降级返回 null。 */
    @Suppress("ReturnCount")  // 多 return 为降级契约（flag 关/镜像不可用/失败信封逐级返回）
    internal fun tryForward(
        gameEngine: GameEngine,
        actionId: Int,
        paramsBuilder: JsonObjectBuilder.() -> Unit
    ): JsonElement? {
        if (!NativeEngineFlag.authoritative) return null
        // 防御性空安全：测试 mock（未 stub stateSyncServiceRef）返回 null——
        // 先赋可空局部再判空（handover findings 13，与 ExplorationNativeForward 同守卫）
        val sync: StateSyncService? = gameEngine.stateSyncService
        if (sync == null) return null
        return GameEngineNativeOps.tryExecuteNative(
            stateSyncService = sync,
            actionId = actionId,
            paramsJson = params(paramsBuilder)
        )
    }
}

// ── 回执解析助手（native 信封 → Kotlin 事务外残差驱动参数）──────────────

/** 信封字符串字段（缺失即空串——native 省略空值时与空串等价） */
internal fun JsonElement?.replyStr(name: String): String =
    (this as? JsonObject)?.get(name)?.jsonPrimitive?.contentOrNull ?: ""

/** 信封布尔字段（缺失即 false） */
internal fun JsonElement?.replyBool(name: String): Boolean =
    (this as? JsonObject)?.get(name)?.jsonPrimitive?.booleanOrNull ?: false

/** 信封字符串数组（缺失即空列表） */
internal fun JsonElement?.replyStrList(name: String): List<String> =
    ((this as? JsonObject)?.get(name) as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

/** 信封整型数组（缺失即空列表） */
internal fun JsonElement?.replyIntList(name: String): List<Int> =
    ((this as? JsonObject)?.get(name) as? JsonArray)?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
        ?: emptyList()

// ── native 臂：住所（主入口 GameEngineAtomicAssign.kt）──────────────────

/**
 * 住所分配 native 臂：成功返回信封（含被释放 occupant id），降级返回 null。
 */
internal fun GameEngine.assignToResidenceNative(
    buildingInstanceId: String, slotIndex: Int, discipleId: String
): JsonElement? = PatrolNativeForward.tryForward(this, ActionIds.PATROL_ASSIGN_RESIDENCE) {
    put("buildingInstanceId", buildingInstanceId)
    put("slotIndex", slotIndex)
    put("discipleId", discipleId)
}

/**
 * 住所移除 native 臂：成功返回信封（含被移除弟子 id），降级返回 null。
 */
internal fun GameEngine.removeFromResidenceNative(
    buildingInstanceId: String, slotIndex: Int
): JsonElement? = PatrolNativeForward.tryForward(this, ActionIds.PATROL_REMOVE_RESIDENCE) {
    put("buildingInstanceId", buildingInstanceId)
    put("slotIndex", slotIndex)
}

// ── native 臂：巡逻（主入口 GameEngineAtomicAssign.kt）──────────────────

/** 巡逻分配 native 臂：信封含 releasedOccupantId，降级返回 null。 */
internal fun GameEngine.assignPatrolNative(
    discipleId: String, globalIndex: Int
): JsonElement? = PatrolNativeForward.tryForward(this, ActionIds.PATROL_ASSIGN) {
    put("discipleId", discipleId)
    put("globalIndex", globalIndex)
}

/** 巡逻移除 native 臂：信封含 removedDiscipleId，降级返回 null。 */
internal fun GameEngine.removePatrolNative(globalIndex: Int): JsonElement? =
    PatrolNativeForward.tryForward(this, ActionIds.PATROL_REMOVE) {
        put("globalIndex", globalIndex)
    }

/** 巡逻交换 native 臂：信封含 from/to occupant id，降级返回 null。 */
internal fun GameEngine.swapPatrolNative(
    fromGlobalIndex: Int, toGlobalIndex: Int
): JsonElement? = PatrolNativeForward.tryForward(this, ActionIds.PATROL_SWAP) {
    put("fromGlobalIndex", fromGlobalIndex)
    put("toGlobalIndex", toGlobalIndex)
}

/** 批量自动分配 native 臂：信封含 releasedIds/confirmedIds/confirmedIndexes，降级返回 null。 */
internal fun GameEngine.autoAssignPatrolNative(assignments: List<Pair<Int, String>>): JsonElement? =
    PatrolNativeForward.tryForward(this, ActionIds.PATROL_AUTO_ASSIGN) {
        putJsonArray("assignments") {
            for ((globalIndex, discipleId) in assignments) {
                add(JsonObject(mapOf(
                    "globalIndex" to JsonPrimitive(globalIndex),
                    "discipleId" to JsonPrimitive(discipleId)
                )))
            }
        }
    }

// ── native 臂：配置 / 矿场 / 年俸（主入口 GameEnginePatrolOps.kt）──────

/**
 * 巡视配置整表覆写 native 臂（C++ `updatePatrolConfigsTx`——按传入表直接覆写
 * patrolConfigs；"补足到 towerIndex 的默认填位"由 Kotlin 调用方
 * `PatrolTowerViewModel.updatePatrolConfig` 在构表时完成，本事务不重复补位）。
 */
internal fun GameEngine.updatePatrolConfigsNative(configs: List<PatrolConfig>): JsonElement? =
    PatrolNativeForward.tryForward(this, ActionIds.PATROL_UPDATE_CONFIG) {
        putJsonArray("configs") {
            for (config in configs) {
                add(JsonObject(mapOf(
                    "targetRealms" to JsonArray(config.targetRealms.map { JsonPrimitive(it) }),
                    "maxBeastCount" to JsonPrimitive(config.maxBeastCount),
                    "requireFullStatus" to JsonPrimitive(config.requireFullStatus)
                )))
            }
        }
    }

/** 矿场槽位整表覆写 native 臂（C++ SpiritMineSlot 编解码同键名），降级返回 null。 */
internal fun GameEngine.updateSpiritMineSlotsNative(slots: List<SpiritMineSlot>): JsonElement? =
    PatrolNativeForward.tryForward(this, ActionIds.PATROL_UPDATE_SPIRIT_MINE_SLOTS) {
        putJsonArray("slots") {
            for (slot in slots) {
                add(JsonObject(mapOf(
                    "index" to JsonPrimitive(slot.index),
                    "discipleId" to JsonPrimitive(slot.discipleId),
                    "discipleName" to JsonPrimitive(slot.discipleName),
                    "output" to JsonPrimitive(slot.output),
                    "sectId" to JsonPrimitive(slot.sectId),
                    "consecutiveMiningMonths" to JsonPrimitive(slot.consecutiveMiningMonths),
                    "buildingInstanceId" to JsonPrimitive(slot.buildingInstanceId)
                )))
            }
        }
    }

/** 矿场槽位自愈 native 臂（信封含 alignedCount），降级返回 null。 */
internal fun GameEngine.validateAndFixSpiritMineDataNative(): JsonElement? =
    PatrolNativeForward.tryForward(this, ActionIds.PATROL_FIX_SPIRIT_MINE) { }

/** 年俸覆写 native 臂（整型键映射编码为 entries 数组，降级返回 null。 */
internal fun GameEngine.updateYearlySalaryNative(salary: Map<Int, Int>): JsonElement? =
    PatrolNativeForward.tryForward(this, ActionIds.PATROL_UPDATE_YEARLY_SALARY) {
        putJsonArray("yearlySalaryEntries") {
            for ((realm, amount) in salary) {
                add(JsonObject(mapOf(
                    "realm" to JsonPrimitive(realm),
                    "amount" to JsonPrimitive(amount)
                )))
            }
        }
    }
