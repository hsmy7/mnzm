@file:Suppress("MatchingDeclarationName")  // 文件持攻宗域 native 转发器对象（batch-20b 聚合）

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.field
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// GameEngineSectAttackNativeOps.kt — 攻宗域 native 转发域（batch-20b）
//
// SectAttackNativeForward 转发器 + 两个确定性写段的 native 臂。
// 主入口见 GameEngineBattleOps.kt（attackSect）；Kotlin 原路径整体保留为
// 降级回退臂（双实现并行契约，README §7.3）。
//
// **批次口径（写者审计结论，见 handover §2.51b）**：攻宗的战斗执行覆盖面
// 已在 C++（executeAiBattle / computeCanOccupy / nativeCheckAttackConditions /
// discipleToCombatant 实例语义组装），本批只下沉 UI 触发面仍 Kotlin 独占的
// **零 RNG 写段**——阵亡守军清理（aiSectDisciples 段 + 驻军槽）与魂魄发放。
// **登记不下沉**：战利品生成族（模板抽取走 `Random.Default` 非分区随机域，
// 无法逐位复刻）；occupy/crush 奖励入账（与奖励段同一原子事务）；
// recordSectBattleRecord（与 Kotlin 显示域 battleLogs 同事务，不入 C++ 状态）。

/**
 * 攻宗域 native 转发器（AUTHORITATIVE 稳态下两段确定性写归 C++）。
 *
 * 降级契约：flag 非 AUTHORITATIVE / 镜像服务缺失 / native 失败信封 → null，
 * 调用方回退 Kotlin 原实现。
 */
internal object SectAttackNativeForward {

    /** 尝试经 C++ 执行攻宗域写段；成功返回 data，降级返回 null。 */
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

/**
 * 阵亡守军清理 native 臂：成功返回 true（C++ 已写 aiSectDisciples 段 +
 * 目标宗门驻军槽），降级或空集返回 false 走 Kotlin 原实现。
 *
 * 空阵亡集直接返回 false（零差异写——避免一次无收益的跨语言往返；
 * Kotlin 原实现同样零变更）。
 */
internal fun GameEngine.removeDeadDefendersNative(
    sectId: String,
    defenderPoolSectId: String,
    deadDefenderIds: Set<String>
): Boolean {
    if (deadDefenderIds.isEmpty()) return false
    val reply = SectAttackNativeForward.tryForward(
        this, ActionIds.SECT_ATTACK_REMOVE_DEAD_DEFENDERS_TX
    ) {
        put("sectId", sectId)
        put("defenderPoolSectId", defenderPoolSectId)
        putJsonArray("deadDefenderIds") { deadDefenderIds.forEach { add(it) } }
    }
    return reply?.field("removedFromPool") != null
}

/**
 * 胜方存活弟子魂魄 +1 native 臂：成功返回 true，降级或空集返回 false
 * 走 Kotlin 原实现。
 */
internal fun GameEngine.grantWarSoulPowersNative(sectSurvivorIds: Set<String>): Boolean {
    if (sectSurvivorIds.isEmpty()) return false
    val reply = SectAttackNativeForward.tryForward(
        this, ActionIds.SECT_ATTACK_GRANT_SOUL_POWERS_TX
    ) {
        putJsonArray("sectSurvivorIds") { sectSurvivorIds.forEach { add(it) } }
    }
    return reply?.field("granted") != null
}
