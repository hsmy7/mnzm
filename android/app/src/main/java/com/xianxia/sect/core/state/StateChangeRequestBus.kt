package com.xianxia.sect.core.state

import android.util.Log
import com.xianxia.sect.core.engine.subsystem.StateChangeRequest
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.event.ErrorEvent
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

class StateChangeException(message: String) : Exception(message)

@Singleton
class StateChangeRequestBus @Inject constructor(
    private val unifiedStateManager: UnifiedGameStateManager,
    private val eventBus: EventBus
) {
    companion object {
        private const val TAG = "StateChangeRequestBus"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val requestChannel = Channel<StateChangeRequest>(capacity = Channel.UNLIMITED)
    val requests: Flow<StateChangeRequest> = requestChannel.receiveAsFlow()
    
    private val errorChannel = Channel<Throwable>(capacity = Channel.UNLIMITED)
    val processingErrors: Flow<Throwable> = errorChannel.receiveAsFlow()
    
    private var isProcessing = false
    
    init {
        startProcessing()
    }
    
    private fun startProcessing() {
        if (isProcessing) return
        isProcessing = true
        
        scope.launch {
            for (request in requestChannel) {
                try {
                    processRequestSuspend(request)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing request: $request", e)
                    errorChannel.trySend(e)
                    eventBus.emitSync(ErrorEvent(
                        errorCode = "STATE_CHANGE_FAILED",
                        message = e.message ?: "Unknown error processing state change"
                    ))
                }
            }
        }
    }
    
    suspend fun submit(request: StateChangeRequest) {
        requestChannel.send(request)
    }
    
    fun submitSync(request: StateChangeRequest): Boolean {
        return requestChannel.trySend(request).isSuccess
    }
    
    fun submitOrThrow(request: StateChangeRequest) {
        val result = requestChannel.trySend(request)
        if (result.isFailure) {
            throw StateChangeException("Failed to submit request: channel full or closed")
        }
    }
    
    suspend fun submitAndWait(request: StateChangeRequest) {
        processRequestSuspend(request)
    }
    
    private suspend fun processRequestSuspend(request: StateChangeRequest) {
        when (request) {
            is StateChangeRequest.UpdateGameDataField -> {
                unifiedStateManager.updateState { state ->
                    applyGameDataFieldUpdates(state, request.fieldUpdates)
                }
            }
            is StateChangeRequest.UpdateSectName -> {
                unifiedStateManager.updateGameData { gameData ->
                    gameData.copy(sectName = request.newName)
                }
            }
            is StateChangeRequest.UpdateSectLevel -> {
                // sectLevel field not present in GameData, skip
            }
            is StateChangeRequest.UpdateSpiritStones -> {
                unifiedStateManager.updateGameData { gameData ->
                    gameData.copy(spiritStones = gameData.spiritStones + request.delta)
                }
            }
            is StateChangeRequest.UpdateDiscipleField -> {
                unifiedStateManager.updateDisciple(request.discipleId) { disciple ->
                    applyDiscipleFieldUpdates(disciple, request.fieldUpdates)
                }
            }
            is StateChangeRequest.UpdateDiscipleCultivation -> {
                unifiedStateManager.updateDisciple(request.discipleId) { disciple ->
                    disciple.copy(cultivation = disciple.cultivation + request.cultivationDelta)
                }
            }
            is StateChangeRequest.UpdateDiscipleRealm -> {
                unifiedStateManager.updateDisciple(request.discipleId) { disciple ->
                    disciple.copy(realm = request.newRealm)
                }
            }
            is StateChangeRequest.UpdateDiscipleStatus -> {
                unifiedStateManager.updateDisciple(request.discipleId) { disciple ->
                    disciple.copy(status = request.newStatus)
                }
            }
            is StateChangeRequest.AddDisciple -> {
                unifiedStateManager.addDisciple(request.disciple)
            }
            is StateChangeRequest.RemoveDisciple -> {
                unifiedStateManager.removeDisciple(request.discipleId)
            }
            is StateChangeRequest.UpdateBuildingSlot -> {
                unifiedStateManager.updateState { state ->
                    val slots = state.gameData.forgeSlots.toMutableList()
                    if (request.slotIndex in slots.indices) {
                        slots[request.slotIndex] = request.slot
                        state.copy(gameData = state.gameData.copy(forgeSlots = slots))
                    } else {
                        state
                    }
                }
            }
            is StateChangeRequest.UpdateAlchemySlot -> {
                unifiedStateManager.updateState { state ->
                    val slots = state.gameData.alchemySlots.toMutableList()
                    if (request.slotIndex in slots.indices) {
                        slots[request.slotIndex] = request.slot
                        state.copy(gameData = state.gameData.copy(alchemySlots = slots))
                    } else {
                        state
                    }
                }
            }
            is StateChangeRequest.AddEquipment -> {
                unifiedStateManager.updateState { state ->
                    state.copy(equipment = state.equipment + request.equipment)
                }
            }
            is StateChangeRequest.AddPill -> {
                unifiedStateManager.updateState { state ->
                    state.copy(pills = state.pills + request.pill)
                }
            }
            is StateChangeRequest.RemoveItem -> {
                unifiedStateManager.updateState { state ->
                    removeItemFromState(state, request.itemId, request.itemType, request.quantity)
                }
            }
        }
    }
    
    private fun applyGameDataFieldUpdates(state: UnifiedGameState, updates: Map<String, Any?>): UnifiedGameState {
        var gameData = state.gameData
        updates.forEach { (field, value) ->
            gameData = when (field) {
                "sectName" -> gameData.copy(sectName = value as? String ?: gameData.sectName)
                "spiritStones" -> gameData.copy(spiritStones = value as? Long ?: gameData.spiritStones)
                "gameDay" -> gameData.copy(gameDay = value as? Int ?: gameData.gameDay)
                "gameMonth" -> gameData.copy(gameMonth = value as? Int ?: gameData.gameMonth)
                "gameYear" -> gameData.copy(gameYear = value as? Int ?: gameData.gameYear)
                else -> gameData
            }
        }
        return state.copy(gameData = gameData)
    }
    
    private fun applyDiscipleFieldUpdates(disciple: Disciple, updates: Map<String, Any?>): Disciple {
        var result = disciple
        updates.forEach { (field, value) ->
            result = when (field) {
                "cultivation" -> result.copy(cultivation = value as? Double ?: result.cultivation)
                "realm" -> result.copy(realm = value as? Int ?: result.realm)
                "status" -> result.copy(status = value as? DiscipleStatus ?: result.status)
                "name" -> result.copy(name = value as? String ?: result.name)
                else -> result
            }
        }
        return result
    }
    
    private fun removeItemFromState(state: UnifiedGameState, itemId: String, itemType: String, quantity: Int): UnifiedGameState {
        return when (itemType) {
            "equipment" -> state.copy(equipment = state.equipment.filterNot { it.id == itemId })
            "manual" -> state.copy(manuals = state.manuals.filterNot { it.id == itemId })
            "pill" -> {
                val pill = state.pills.find { it.id == itemId }
                if (pill != null && pill.quantity > quantity) {
                    state.copy(pills = state.pills.map { 
                        if (it.id == itemId) it.copy(quantity = it.quantity - quantity) else it 
                    })
                } else {
                    state.copy(pills = state.pills.filterNot { it.id == itemId })
                }
            }
            "material" -> {
                val material = state.materials.find { it.id == itemId }
                if (material != null && material.quantity > quantity) {
                    state.copy(materials = state.materials.map { 
                        if (it.id == itemId) it.copy(quantity = it.quantity - quantity) else it 
                    })
                } else {
                    state.copy(materials = state.materials.filterNot { it.id == itemId })
                }
            }
            "herb" -> {
                val herb = state.herbs.find { it.id == itemId }
                if (herb != null && herb.quantity > quantity) {
                    state.copy(herbs = state.herbs.map { 
                        if (it.id == itemId) it.copy(quantity = it.quantity - quantity) else it 
                    })
                } else {
                    state.copy(herbs = state.herbs.filterNot { it.id == itemId })
                }
            }
            "seed" -> {
                val seed = state.seeds.find { it.id == itemId }
                if (seed != null && seed.quantity > quantity) {
                    state.copy(seeds = state.seeds.map { 
                        if (it.id == itemId) it.copy(quantity = it.quantity - quantity) else it 
                    })
                } else {
                    state.copy(seeds = state.seeds.filterNot { it.id == itemId })
                }
            }
            else -> state
        }
    }
    
    fun dispose() {
        scope.cancel()
        isProcessing = false
    }
}
