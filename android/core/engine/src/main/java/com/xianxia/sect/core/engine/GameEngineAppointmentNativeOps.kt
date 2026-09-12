@file:Suppress("MatchingDeclarationName") // 文件持转发器对象 + 七入口回执扩展（batch-15 native 域聚合）

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.str
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// GameEngineAppointmentNativeOps.kt — 弟子管理三（任命/驻守/洗炼消耗族）
// native 事务转发（batch-15）。
//
// 七入口 AUTHORITATIVE 稳态写者下沉 appointment_tx.h（C++ 事务成功 =
// GameData 写段已完成且经 StateSyncService 镜像回读），Kotlin 分支**仅执行
// 事务外残差**：
//  - 长老任命/卸任/仓库驻守：Gate 注册表 release/confirmAssign、Room 生产槽
//    Repository 清理、弟子状态同步（PatrolNativeOps 同族机制）；
//  - 洗炼三族：**玉符运行时 totalCount 同步**（[syncJadeRuntimeAfterNative]
//    ——C++ 承扣后运行时未同步会让 checkpointNow 以旧绝对值覆盖写导致玉符
//    回涨，CLAUDE.md 13.3 绝对值覆盖写模型）+ 事务外 publishJadeSymbolState
//    StateNow。
// flag 关闭 / 桥未加载 / 顶层失败信封 → 返回 null，调用方回退 Kotlin 原事务体
// （双实现并行契约，PatrolNativeForward 同族机制）。

/** 任命/驻守/洗炼族 native 转发器（PatrolNativeForward 同族机制）。 */
internal object AppointmentNativeForward {

    /** 尝试经 C++ 执行任命/驻守/洗炼动作；成功时返回 data（镜像已回读）。 */
    @Suppress("ReturnCount") // 多 return 为降级契约（flag 关/镜像不可用逐级返回 null）
    internal fun tryForward(
        gameEngine: GameEngine,
        actionId: Int,
        paramsBuilder: JsonObjectBuilder.() -> Unit
    ): JsonElement? {
        if (!NativeEngineFlag.authoritative) return null
        // 防御性空安全：生产恒非空，但测试 mock（未 stub stateSyncServiceRef）
        // 返回 null——Kotlin 非空参数的内在检查在函数入口即抛 NPE，必须先过滤
        val sync: StateSyncService? = gameEngine.stateSyncService
        if (sync == null) return null
        return GameEngineNativeOps.tryExecuteNative(
            stateSyncService = sync,
            actionId = actionId,
            paramsJson = params(paramsBuilder)
        )
    }
}

/** 长老任命 native 回执（replacedIds 未去重追加序——旧长老在前 + 被清空亲传
 *  列表成员在后；distinct 与 != appointee 过滤由 Kotlin 残差 releaseReplaced
 *  Ids 应用，与 Kotlin collectReplacedIds 消费口径一致）。 */
internal data class ElderAppointReceipt(val replacedIds: List<String>)

/** 长老卸任 native 回执（removedId 空串 = 槽原本无人）。 */
internal data class ElderDismissReceipt(val removedId: String)

/** 仓库驻守 native 回执（oldOccupantId 空串 = 无）。 */
internal data class WarehouseGarrisonReceipt(val oldOccupantId: String)

/** 洗炼灵根 native 回执（jadeAfter = C++ 承扣后余额，运行时同步锚点）。 */
internal data class SpiritRootWashReceipt(
    val newRootType: String,
    val newPityCount: Int,
    val jadeAfter: Int
)

/** 新增特质刷新 native 回执。 */
internal data class TraitRollReceipt(val newId: String, val jadeAfter: Int)

/** 特质单槽洗炼 native 回执。 */
internal data class TraitWashReceipt(
    val newId: String,
    val newPityCount: Int,
    val jadeAfter: Int
)

/** 字符串列表字段读取（缺字段/非数组按空列表）。 */
private fun stringList(data: JsonElement, key: String): List<String> =
    ((data as? JsonObject)?.get(key) as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?: emptyList()

/** 整数字段读取（缺字段/非数值返回 null）。 */
private fun JsonElement.intField(name: String): Int? =
    (this as? JsonObject)?.get(name)?.jsonPrimitive?.long?.toInt()

// ── 长老单值槽任命/卸任 ─────────────────────────────────────────────

/** 长老任命 native 臂（C++ 全槽清理 + 槽位写段；null = 降级/失败 → 回退原路径）。 */
internal fun GameEngine.tryAppointElderNative(
    slotType: String,
    discipleId: String
): ElderAppointReceipt? {
    val data = AppointmentNativeForward.tryForward(this, ActionIds.ELDER_APPOINT_TX) {
        put("slotType", slotType)
        put("discipleId", discipleId)
    } ?: return null
    return ElderAppointReceipt(replacedIds = stringList(data, "replacedIds"))
}

/** 长老卸任 native 臂（null = 降级/失败 → 回退原路径）。 */
internal fun GameEngine.tryDismissElderNative(slotType: String): ElderDismissReceipt? {
    val data = AppointmentNativeForward.tryForward(this, ActionIds.ELDER_DISMISS_TX) {
        put("slotType", slotType)
    } ?: return null
    return ElderDismissReceipt(removedId = data.str("removedId") ?: "")
}

// ── 仓库驻守 ────────────────────────────────────────────────────────

/** 仓库驻守 native 臂（C++ 校验 + 旧 occupant 捕获 + 全槽清理 + 条目替换）。 */
internal fun GameEngine.tryAssignWarehouseGarrisonNative(
    buildingInstanceId: String,
    discipleId: String,
    discipleName: String,
    sectId: String
): WarehouseGarrisonReceipt? {
    val data = AppointmentNativeForward.tryForward(this, ActionIds.WAREHOUSE_GARRISON_TX) {
        put("buildingInstanceId", buildingInstanceId)
        put("discipleId", discipleId)
        put("discipleName", discipleName)
        put("sectId", sectId)
    } ?: return null
    return WarehouseGarrisonReceipt(oldOccupantId = data.str("oldOccupantId") ?: "")
}

// ── 洗炼消耗族 ──────────────────────────────────────────────────────

/** 洗炼灵根 native 臂（先扣后抽在 C++ 同事务原子完成；null = 降级/失败信封
 *  ——回退 Kotlin 原路径重执行校验链，失败臂零抽取语义由 C++ 黄金用例守护）。 */
internal fun GameEngine.tryWashSpiritRootNative(
    discipleId: String,
    pityCount: Int,
    cost: Int
): SpiritRootWashReceipt? {
    val data = AppointmentNativeForward.tryForward(this, ActionIds.SPIRIT_ROOT_WASH_TX) {
        put("discipleId", discipleId)
        put("pityCount", pityCount)
        put("cost", cost)
    } ?: return null
    val newRootType = data.str("newRootType") ?: return null
    val newPityCount = data.intField("newPityCount") ?: return null
    val jadeAfter = data.intField("jadeAfter") ?: return null
    return SpiritRootWashReceipt(newRootType, newPityCount, jadeAfter)
}

/** 新增特质刷新 native 臂（C++ 校验 + 扣玉符 + 抽取 + pending 落盘）。 */
internal fun GameEngine.tryRollTraitAddNative(
    discipleId: String,
    type: String,
    cost: Int
): TraitRollReceipt? {
    val data = AppointmentNativeForward.tryForward(this, ActionIds.TRAIT_ADD_ROLL_TX) {
        put("discipleId", discipleId)
        put("type", type)
        put("cost", cost)
    } ?: return null
    val newId = data.str("newId") ?: return null
    val jadeAfter = data.intField("jadeAfter") ?: return null
    return TraitRollReceipt(newId, jadeAfter)
}

/** 新增特质确认 native 臂（零 RNG 纯数据事务；null = 降级/失败 → 回退原路径，
 *  失败信封由 Kotlin 回退臂重执行校验链产出玩家可读文案）。 */
internal fun GameEngine.tryConfirmTraitAddNative(
    discipleId: String,
    type: String,
    newId: String
): Boolean = AppointmentNativeForward.tryForward(this, ActionIds.TRAIT_ADD_CONFIRM_TX) {
    put("discipleId", discipleId)
    put("type", type)
    put("newId", newId)
} != null

/** 特质单槽洗炼 native 臂（排除集含目标自身——禁止"刷回原样"在 C++ 同口径）。 */
internal fun GameEngine.tryWashTraitSlotNative(
    discipleId: String,
    type: String,
    targetId: String,
    pityCount: Int,
    cost: Int
): TraitWashReceipt? {
    val data = AppointmentNativeForward.tryForward(this, ActionIds.TRAIT_WASH_SLOT_TX) {
        put("discipleId", discipleId)
        put("type", type)
        put("targetId", targetId)
        put("pityCount", pityCount)
        put("cost", cost)
    } ?: return null
    val newId = data.str("newId") ?: return null
    val newPityCount = data.intField("newPityCount") ?: return null
    val jadeAfter = data.intField("jadeAfter") ?: return null
    return TraitWashReceipt(newId, newPityCount, jadeAfter)
}

// ── 玉符运行时同步残差（13.3 绝对值覆盖写模型的 native 臂收口）────────

/**
 * 玉符运行时 totalCount 同步（native 洗炼臂成功后调用——batch-15 路线拍板：
 * C++ 承扣 + Kotlin 运行时同步）。
 *
 * C++ 事务已扣减 gameData.jadeSymbols 并经镜像回读；运行时 totalCount 仍持
 * 扣减前值——不同步则 checkpointNow/settleGrants 以旧绝对值覆盖写（玉符回涨）。
 * 经 [JadeSymbolService.deduct] 在事务内递减运行时并绝对值覆写镜像——与本臂
 * 已扣减后的值一致（幂等覆写，JadeSymbolConsumptionGuardTest 白名单语义：
 * 消耗仍收敛于服务唯一入口，Kotlin 臂零直接覆盖写 jadeSymbols 字段）。
 *
 * `total >= cost` 守卫：仅在运行时已锚定（onLoopStart 后）时同步；未锚定窗口
 * （total 恒 0，洗炼 UI 链实际不可达）跳过——checkpointNow 的 lastSampleMs==0
 * 哨兵不写 store、onLoopStart 以快照重锚，天然收敛到 C++ 真相。
 */
fun GameEngine.syncJadeRuntimeAfterNative(cost: Int) {
    val runtime = jadeSymbolService.runtimeState.value
    if (runtime.total >= cost) {
        stateStore.update { jadeSymbolService.deduct(this, cost) }
    }
}
