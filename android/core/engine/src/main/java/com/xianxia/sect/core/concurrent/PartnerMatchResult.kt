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
) {

    suspend fun apply(state: MutableGameState) {
        val tables = state.discipleTables

        // 消息栏系统：移除了 consentRequest 弹窗，统一直接配对
        if (consentRequest != null) {
            val maleId = consentRequest.first
            val femaleId = consentRequest.second
            tables.partnerIds[maleId] = femaleId.toString()
            tables.partnerIds[femaleId] = maleId.toString()
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
