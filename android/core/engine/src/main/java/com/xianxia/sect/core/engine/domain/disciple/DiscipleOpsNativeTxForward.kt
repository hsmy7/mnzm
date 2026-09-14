package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

// ── W4-A·w3-01 赏赐/服药 native 转发臂 ──────────────────────────────

/** material/herb/seed 赏赐统一转发（扣仓库 + 入袋在 C++ 同一事务）。 */
internal fun DiscipleFacadeImpl.tryNativeReward(
    discipleId: String,
    itemType: String,
    item: RewardSelectedItem,
    quantity: Int
): Boolean = tryDiscipleOpsTxNative(ActionIds.DISCIPLE_OP_REWARD_ITEM) {
    put("discipleId", discipleId)
    put("itemType", itemType)
    put("itemId", item.id)
    put("quantity", quantity)
    put("itemName", item.name)
    put("itemRarity", item.rarity)
} != null

/** lifeEvents 瞬态列草稿回写（镜像滞后窗口/空草稿跳过——appendLifeEventDraft 同款）。 */
internal fun MutableGameState.appendOpsLifeEventDraft(discipleId: String, logLine: String?) {
    if (logLine.isNullOrEmpty()) return
    val intId = discipleId.toIntOrNull() ?: return
    if (intId !in discipleTables.ids) return
    val events = discipleTables.lifeEvents.getOrDefault(intId, emptyList())
    discipleTables.lifeEvents[intId] = events + logLine
}

/**
 * 偷盗判定钩子残差（执法域不下沉——phase_settlement.h 同边界）：
 * facade 丹药链在 moralityAdd 落表后即时判定（morality < MORALITY_THRESHOLD
 * 即触发）；C++ 事务回传 theftCandidate + moralityAfter，本分支以镜像刷新后的
 * 状态按同一判定序原序执行（事务内版本 processSingleDiscipleTheft(id, state)）。
 */
internal fun DiscipleFacadeImpl.applyTheftHookResidual(
    discipleId: String,
    theftCandidate: Boolean,
    moralityAfter: Int
) {
    if (!theftCandidate) return
    val id = discipleId.toIntOrNull() ?: return
    if (moralityAfter >= GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD) return
    stateStore.update {
        lawEnforcementProcessor.processSingleDiscipleTheft(id, this)
    }
}

/** w3-01 赏赐/服药事务转发（AUTHORITATIVE 门控；失败信封/降级返回 null）。 */
internal fun DiscipleFacadeImpl.tryDiscipleOpsTxNative(
    actionId: Int,
    paramsBuilder: JsonObjectBuilder.() -> Unit
): JsonElement? {
    if (!NativeEngineFlag.authoritative) return null
    // 防御性空安全：生产恒非空，测试 mock（未 stub stateSyncServiceRef）返回 null 时降级
    val sync: StateSyncService? = gameEngineCore.stateSyncServiceRef
    if (sync == null) return null
    return GameEngineNativeOps.tryExecuteNative(
        stateSyncService = sync,
        actionId = actionId,
        paramsJson = GameEngineNativeOps.params(paramsBuilder)
    )
}
