package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SectScoutInfo
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult



/**
 * CultivationEventProcessor 侦察/外交域 Ops 扩展（P4D）。
 */
    // ── 侦察/外交（原 CultivationEventProcessor 段落） ──────────────────────────────────────────────────────
internal fun CultivationEventProcessor.processScoutInfoExpiryLazy(year: Int, month: Int) {
        val data = stateStore.gameData.value
        val hasExpired = data.scoutInfo.any { (_, info) ->
            year > info.expiryYear || (year == info.expiryYear && month > info.expiryMonth)
        }
        if (!hasExpired) return
        processScoutInfoExpiry(year, month)
    }
internal fun CultivationEventProcessor.processScoutInfoExpiryLazy(year: Int, month: Int, state: MutableGameState) {
        val data = state.gameData
        val hasExpired = data.scoutInfo.any { (_, info) ->
            year > info.expiryYear || (year == info.expiryYear && month > info.expiryMonth)
        }
        if (!hasExpired) return
        processScoutInfoExpiry(year, month, state)
    }
internal fun CultivationEventProcessor.processScoutInfoExpiry(year: Int, month: Int) {
        val data = stateStore.gameData.value
        var hasExpired = false
        val updatedScoutInfo = data.scoutInfo.filter { (_, info) ->
            val isExpired = year > info.expiryYear ||
                (year == info.expiryYear && month > info.expiryMonth)
            if (isExpired) hasExpired = true
            !isExpired
        }
        if (hasExpired) {
            stateStore.update { applyScoutInfoExpiry(this, year, month) }
        }
    }
internal fun CultivationEventProcessor.processScoutInfoExpiry(year: Int, month: Int, state: MutableGameState) {
        applyScoutInfoExpiry(state, year, month)
    }
internal fun CultivationEventProcessor.applyScoutInfoExpiry(state: MutableGameState, year: Int, month: Int) {
        val data = state.gameData
        val updatedScoutInfo = data.scoutInfo.filter { (_, info) ->
            !(year > info.expiryYear || (year == info.expiryYear && month > info.expiryMonth))
        }
        val hasExpired = updatedScoutInfo.size < data.scoutInfo.size
        if (!hasExpired) return
        val updatedWorldMapSects = data.worldMapSects.map { sect ->
            val sectScoutInfo = updatedScoutInfo[sect.id]
            if (sectScoutInfo == null && data.sectDetails[sect.id]?.scoutInfo?.sectId?.isNotEmpty() == true) {
                sect.copy(isKnown = false)
            } else sect
        }
        val updatedDetails = data.sectDetails.toMutableMap()
        updatedScoutInfo.forEach { (sectId, _) ->
            updatedDetails[sectId] = (updatedDetails[sectId] ?: SectDetail(sectId = sectId)).copy(scoutInfo = updatedScoutInfo[sectId] ?: SectScoutInfo())
        }
        data.sectDetails.forEach { (sectId, detail) ->
            if (updatedScoutInfo[sectId] == null && detail.scoutInfo.sectId.isNotEmpty()) {
                updatedDetails[sectId] = detail.copy(scoutInfo = SectScoutInfo())
            }
        }
        state.gameData = state.gameData.copy(scoutInfo = updatedScoutInfo, worldMapSects = updatedWorldMapSects, sectDetails = updatedDetails)
    }
