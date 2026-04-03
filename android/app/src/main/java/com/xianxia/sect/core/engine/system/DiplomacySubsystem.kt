package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.util.StateFlowListUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiplomacySubsystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "DiplomacySubsystem"
        const val SYSTEM_NAME = "DiplomacySubsystem"
    }
    
    private val _alliances = MutableStateFlow<List<Alliance>>(emptyList())
    val alliances: StateFlow<List<Alliance>> = _alliances.asStateFlow()
    
    private val _sectRelations = MutableStateFlow<List<SectRelation>>(emptyList())
    val sectRelations: StateFlow<List<SectRelation>> = _sectRelations.asStateFlow()
    
    private val _sectTradeItems = MutableStateFlow<Map<String, List<MerchantItem>>>(emptyMap())
    val sectTradeItems: StateFlow<Map<String, List<MerchantItem>>> = _sectTradeItems.asStateFlow()
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "DiplomacySubsystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "DiplomacySubsystem released")
    }
    
    override suspend fun clear() {
        StateFlowListUtils.clearList(_alliances)
        StateFlowListUtils.clearList(_sectRelations)
        _sectTradeItems.value = emptyMap()
    }
    
    fun loadDiplomacyData(
        alliances: List<Alliance>,
        sectRelations: List<SectRelation>,
        sectTradeItems: Map<String, List<MerchantItem>> = emptyMap()
    ) {
        StateFlowListUtils.setList(_alliances, alliances)
        StateFlowListUtils.setList(_sectRelations, sectRelations)
        _sectTradeItems.value = sectTradeItems
    }
    
    fun getAlliances(): List<Alliance> = _alliances.value
    
    fun getAllianceById(allianceId: String): Alliance? = 
        StateFlowListUtils.findItemById(_alliances, allianceId) { it.id }
    
    fun addAlliance(alliance: Alliance) = StateFlowListUtils.addItem(_alliances, alliance)
    
    fun removeAlliance(allianceId: String): Boolean = 
        StateFlowListUtils.removeItemById(_alliances, allianceId, getId = { it.id })
    
    fun updateAlliance(allianceId: String, transform: (Alliance) -> Alliance): Boolean =
        StateFlowListUtils.updateItemById(_alliances, allianceId, { it.id }, transform)
    
    fun getSectRelations(): List<SectRelation> = _sectRelations.value
    
    fun getRelationBetween(sectId1: String, sectId2: String): SectRelation? =
        _sectRelations.value.find { 
            (it.sectId1 == sectId1 && it.sectId2 == sectId2) ||
            (it.sectId1 == sectId2 && it.sectId2 == sectId1)
        }
    
    fun updateSectRelations(relations: List<SectRelation>) {
        StateFlowListUtils.setList(_sectRelations, relations)
    }
    
    fun updateSectRelation(sectId1: String, sectId2: String, transform: (SectRelation) -> SectRelation): Boolean {
        return StateFlowListUtils.updateItem(_sectRelations, { relation ->
            (relation.sectId1 == sectId1 && relation.sectId2 == sectId2) ||
            (relation.sectId1 == sectId2 && relation.sectId2 == sectId1)
        }, transform)
    }
    
    fun getSectTradeItems(sectId: String): List<MerchantItem> = 
        _sectTradeItems.value[sectId] ?: emptyList()
    
    fun setSectTradeItems(sectId: String, items: List<MerchantItem>) {
        _sectTradeItems.value = _sectTradeItems.value + (sectId to items)
    }
    
    fun isAlly(sectId: String, playerSectId: String): Boolean {
        return _alliances.value.any { alliance ->
            alliance.sectIds.contains(sectId) && alliance.sectIds.contains(playerSectId)
        }
    }
    
    fun getAlliesOfSect(sectId: String): List<String> {
        return _alliances.value
            .filter { it.sectIds.contains(sectId) }
            .flatMap { it.sectIds.filter { id -> id != sectId } }
    }
}
