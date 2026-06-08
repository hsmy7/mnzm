package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CultivationSharedState @Inject constructor() {
    val highFrequencyData = MutableStateFlow(HighFrequencyData())
    val autoEquipDirty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val autoLearnDirty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile var cachedCultivationRates: Map<String, Double> = emptyMap()
    @Volatile var cachedNurtureRates: Map<String, Double> = emptyMap()
    @Volatile var cachedProficiencyRates: Map<String, Map<String, Double>> = emptyMap()

    var diplomacyEventsThisMonth = 0
    var diplomacyEventsMonth = 0
}
