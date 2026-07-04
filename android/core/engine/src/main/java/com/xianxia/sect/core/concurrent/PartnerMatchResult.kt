package com.xianxia.sect.core.concurrent

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.MutableGameState

/**
 * [PartnerSystem] 并行 compute 的配对结果。
 *
 * 在 [Dispatchers.Default] 上计算所有配对与忠诚度变更，
 * [apply] 在游戏主线程的 [stateStore.update] 块中原子写入。
 *
 * @param partnerUpdates discipleId → newPartnerId（null=清除配对）
 * @param loyaltyUpdates discipleId → newLoyalty 值
 * @param consentRequest 需要玩家确认的婚姻请求（maleId, femaleId），
 *                       非 null 时忽略 [partnerUpdates]/[loyaltyUpdates]
 */
class PartnerMatchResult(
    private val partnerUpdates: Map<Int, String?>,
    private val loyaltyUpdates: Map<Int, Int>,
    private val consentRequest: Pair<Int, Int>?
) : ParallelPhaseResult {

    override suspend fun apply(state: MutableGameState) {
        val tables = state.discipleTables

        if (consentRequest != null) {
            // 需玩家确认 → 不直接配对，只发通知
            val male = tables.assemble(consentRequest.first)
            val female = tables.assemble(consentRequest.second)
            state.pendingNotification = GameNotification.MarriageRequest(male, female)
            return
        }

        // 普通模式：直接写入配对和忠诚度
        for ((id, partnerId) in partnerUpdates) {
            tables.partnerIds[id] = partnerId
        }
        for ((id, loyalty) in loyaltyUpdates) {
            tables.loyalties[id] = loyalty
        }
    }
}
