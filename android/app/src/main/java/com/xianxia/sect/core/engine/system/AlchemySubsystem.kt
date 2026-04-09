package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.model.AlchemySlot
import com.xianxia.sect.core.util.StateFlowListUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlchemySubsystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "AlchemySubsystem"
        const val SYSTEM_NAME = "AlchemySubsystem"
    }
    
    private val _alchemySlots = MutableStateFlow<List<AlchemySlot>>(emptyList())
    val alchemySlots: StateFlow<List<AlchemySlot>> = _alchemySlots.asStateFlow()
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "AlchemySubsystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "AlchemySubsystem released")
    }
    
    override suspend fun clear() {
        StateFlowListUtils.clearList(_alchemySlots)
    }
    
    fun loadAlchemyData(alchemySlots: List<AlchemySlot>) {
        StateFlowListUtils.setList(_alchemySlots, alchemySlots)
    }
    
    fun getAlchemySlots(): List<AlchemySlot> = _alchemySlots.value
    
    fun getAlchemySlot(index: Int): AlchemySlot? =
        _alchemySlots.value.getOrNull(index)
}
