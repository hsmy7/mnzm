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
import kotlinx.serialization.json.put

// GameEngineAppointmentNativeOps.kt — 弟子管理（长老任命/卸任）
// native 事务转发（batch-15）。
//
// AUTHORITATIVE 稳态写者下沉 appointment_tx.h（C++ 事务成功 =
// GameData 写段已完成且经 StateSyncService 镜像回读），Kotlin 分支**仅执行
// 事务外残差**：长老任命/卸任的 Gate 注册表 release/confirmAssign、Room 生产槽
// Repository 清理、弟子状态同步（PatrolNativeOps 同族机制）。
// flag 关闭 / 桥未加载 / 顶层失败信封 → 返回 null，调用方回退 Kotlin 原事务体
// （双实现并行契约，PatrolNativeForward 同族机制）。

/** 长老任命/卸任 native 转发器（PatrolNativeForward 同族机制）。 */
internal object AppointmentNativeForward {

    /** 尝试经 C++ 执行长老任命/卸任动作；成功时返回 data（镜像已回读）。 */
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

/** 字符串列表字段读取（缺字段/非数组按空列表）。 */
private fun stringList(data: JsonElement, key: String): List<String> =
    ((data as? JsonObject)?.get(key) as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?: emptyList()

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
