@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*

sealed class StateChangeRequest {
    data class UpdateGameDataField(
        val fieldUpdates: Map<String, Any?>
    ) : StateChangeRequest()
    
    data class UpdateSectName(
        val newName: String
    ) : StateChangeRequest()
    
    data class UpdateSectLevel(
        val newLevel: Int
    ) : StateChangeRequest()
    
    data class UpdateSpiritStones(
        val delta: Long
    ) : StateChangeRequest()
    
    data class UpdateDiscipleField(
        val discipleId: String,
        val fieldUpdates: Map<String, Any?>
    ) : StateChangeRequest()
    
    data class UpdateDiscipleCultivation(
        val discipleId: String,
        val cultivationDelta: Double
    ) : StateChangeRequest()
    
    data class UpdateDiscipleRealm(
        val discipleId: String,
        val newRealm: Int
    ) : StateChangeRequest()
    
    data class UpdateDiscipleStatus(
        val discipleId: String,
        val newStatus: DiscipleStatus
    ) : StateChangeRequest()
    
    data class AddDisciple(
        val disciple: Disciple
    ) : StateChangeRequest()
    
    data class RemoveDisciple(
        val discipleId: String
    ) : StateChangeRequest()
    
    data class AddEquipment(
        val equipment: Equipment
    ) : StateChangeRequest()
    
    data class AddPill(
        val pill: Pill
    ) : StateChangeRequest()
    
    data class RemoveItem(
        val itemId: String,
        val itemType: String,
        val quantity: Int = 1
    ) : StateChangeRequest()
}
