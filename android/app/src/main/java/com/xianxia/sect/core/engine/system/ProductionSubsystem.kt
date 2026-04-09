package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.util.StateFlowListUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductionSubsystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "ProductionSubsystem"
        const val SYSTEM_NAME = "ProductionSubsystem"
    }
    
    private val _productionSlots = MutableStateFlow<List<ProductionSlot>>(emptyList())
    val productionSlots: StateFlow<List<ProductionSlot>> = _productionSlots.asStateFlow()
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "ProductionSubsystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "ProductionSubsystem released")
    }
    
    override suspend fun clear() {
        StateFlowListUtils.clearList(_productionSlots)
    }
    
    fun loadProductionData(productionSlots: List<ProductionSlot>) {
        StateFlowListUtils.setList(_productionSlots, productionSlots)
    }
    
    fun getProductionSlots(): List<ProductionSlot> = _productionSlots.value
    
    fun getProductionSlot(index: Int): ProductionSlot? =
        _productionSlots.value.getOrNull(index)
}
