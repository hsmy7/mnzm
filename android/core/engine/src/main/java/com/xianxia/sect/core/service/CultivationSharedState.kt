package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CultivationSharedState @Inject constructor() {
    private val _highFrequencyData = MutableStateFlow(HighFrequencyData())

    /** 高频修炼数据（Q-2：对外只读，写入经 [updateHighFrequencyData]） */
    val highFrequencyData: StateFlow<HighFrequencyData> = _highFrequencyData.asStateFlow()

    /**
     * 高频数据写入入口（Q-2：仅引擎内部服务调用）。
     *
     * @param transform 基于当前值产出新值（S7 修复：MutableStateFlow.update 原子 CAS，
     *   消除"读-变换-写"窗口——并发调用不丢失更新）
     */
    internal fun updateHighFrequencyData(transform: (HighFrequencyData) -> HighFrequencyData) {
        _highFrequencyData.update { transform(it) }
    }

    val autoEquipDirty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val autoLearnDirty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile var cachedCultivationRates: Map<String, Double> = emptyMap()
    @Volatile var cachedNurtureRates: Map<String, Double> = emptyMap()
    @Volatile var cachedProficiencyRates: Map<String, Map<String, Double>> = emptyMap()

    var diplomacyEventsThisMonth = 0
    var diplomacyEventsMonth = 0
}
