package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.domain.favor.FavorEventProcessor
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 外交事件处理器。
 *
 * 好感度相关事件（月度外交事件、好感度衰减、联盟好感度检查）
 * 已委托给 [FavorEventProcessor]。
 * 本类保留联盟到期检查、AI 联盟和跨宗门联姻的扩展点。
 */
@Singleton
@GameService("DiplomacyEventProcessor")
class DiplomacyEventProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val favorEventProcessor: FavorEventProcessor
) {

    companion object {
        private const val TAG = "DiplomacyEventProc"
    }

    // ── 好感度衰减（委托 FavorEventProcessor）────

    fun processFavorDecay(currentYear: Int) {
        favorEventProcessor.processFavorDecay(currentYear)
    }

    // ── 联盟好感度检查（委托 FavorEventProcessor）────

    fun checkAllianceFavorDrop() {
        favorEventProcessor.checkAllianceFavorDrop()
    }

    // ── 联盟到期 ─────────────────────────────────────

    fun checkAllianceExpiry(year: Int) {
        val data = stateStore.gameData.value
        val expiredAlliances = data.alliances.filter { year - it.startYear >= com.xianxia.sect.core.config.FavorConfig.ALLIANCE_DURATION_YEARS }

        if (expiredAlliances.isEmpty()) return

        stateStore.update { gameData = gameData.copy(
            alliances = gameData.alliances.filter { year - it.startYear < com.xianxia.sect.core.config.FavorConfig.ALLIANCE_DURATION_YEARS },
            worldMapSects = gameData.worldMapSects.map { sect ->
                if (expiredAlliances.any { it.sectIds.contains(sect.id) }) {
                    sect.copy(allianceId = "", allianceStartYear = 0)
                } else sect
            }
        ) }
    }

    // ── 扩展点 ───────────────────────────────────────

    fun processAIAlliances(year: Int) {
        // AI宗门自动结盟逻辑尚未实现，保留为扩展点。
    }

    fun processCrossSectPartnerMatching(year: Int, month: Int) {
        // 跨宗门联姻系统尚未实现，保留为扩展点。
    }
}
